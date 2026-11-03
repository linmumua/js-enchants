package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.config.ConfigService;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 把启用的附魔写成原版数据包，使其能被附魔台刷到。
 *
 * <p>为什么要数据包：{@code org.bukkit.Registry} 是只读接口，插件无法向
 * {@code ENCHANTMENT} 注册表添加条目；而附魔表本身就是数据驱动的，一个 JSON 文件
 * 就能注册附魔，不需要引导器，也不会把「注册失败」升级成「插件起不来」。
 *
 * <p>要让附魔台真的刷到，下面几条必须同时成立（缺任何一条都表现为「台面有价格、
 * 点了没反应」而不是报错）：
 * <ol>
 *   <li>附魔 id 出现在 {@code #minecraft:in_enchanting_table} 这个 tag 里；</li>
 *   <li>{@code primary_items} 覆盖目标物品；</li>
 *   <li>{@code min_cost}/{@code max_cost} 覆盖附魔台算出的 power；</li>
 *   <li>{@code weight > 0}；</li>
 *   <li>目标物品自带 {@code enchantable} 组件（原版工具/装备默认有）。</li>
 * </ol>
 *
 * <p><b>幂等</b>：每次重写全部 JSON、删除已下线附魔的旧文件、保留目录里的其它内容。
 *
 * <p><b>可测性</b>：{@link #render(Collection, ConfigService.Datapack)} 是纯函数
 * （不碰 Bukkit 静态、不碰文件系统），{@link #write(Collection, ConfigService.Datapack, Path)}
 * 只是把它的结果落盘的薄 IO 层。
 */
public final class EnchantDatapackWriter {

    /** 数据包目录名，与附魔命名空间一致：{@code world/datapacks/jsenchants/}。 */
    static final String PACK_ID = "jsenchants";

    /** 附魔的命名空间。与 PDC 的 namespace 相同，物品上的 {@code jsenchants:<id>} 才对得上。 */
    static final String NAMESPACE = "jsenchants";

    /**
     * {@code pack.mcmeta} 的数据包格式主版本。
     *
     * <p>取值来自服务端 {@code version.json} 的 {@code pack_version.data_major}：
     * 26.3 是 {@code 121}。这不是可以猜的数字——写错会让整个数据包被服务端判为不兼容
     * （表现为一条「不兼容的数据包」提示，附魔一个都不注册）。换服务端版本时同步改这里。
     *
     * <p>注意别拿资源包那套 {@code resource_major}(97) 来反推，数据包与资源包不是一套编号。
     */
    static final int PACK_FORMAT = 121;

    /**
     * 26.x 起 {@code pack.mcmeta} 支持成对的范围字段 {@code min_format}/{@code max_format}，
     * 形式为 {@code [major, minor]}。
     *
     * <p>本服务端上其它 26.3 数据包都是「{@code pack_format} + 成对范围」的写法
     * （例如 CraftEngine 的 {@code pack_format:97, min_format:[97,1], max_format:[97,1]}）。
     * 多写这两个字段是安全的，少写则可能被判为不支持新版格式而整包失效——
     * 而整包失效的症状恰好是「附魔台有价格、点了没反应」，没有直接报错，最难排查。
     */
    static final int PACK_FORMAT_MINOR = 0;

    /**
     * 原版 {@code en_us} 只提供 {@code enchantment.level.1..10}。
     *
     * <p>超过 10 的等级不是「不能注册」——跳过注册等于该附魔在附魔台与铁砧里彻底消失，
     * 比 tooltip 多出几个字严重得多，所以照常写入并逐条告警。
     */
    static final int MAX_LANG_LEVEL = 10;

    /** 附魔台 tag：只有在这里出现的附魔才会进入附魔台候选池。 */
    private static final String TABLE_TAG = "data/minecraft/tags/enchantment/in_enchanting_table.json";

    private static final String PACK_META = "pack.mcmeta";

    private static final String ENCHANTMENT_DIR = "data/" + NAMESPACE + "/enchantment";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final Logger logger;

    public EnchantDatapackWriter(final Logger logger) {
        this.logger = logger;
    }

    /**
     * 生成数据包内容（纯函数）。
     *
     * @param definitions 当前定义；{@code enabled=false} 的被跳过（注册表本就不该给出它们）
     * @param datapack    数据包参数
     * @return 文件名（相对数据包根目录）到 JSON 文本的映射，以及需要逐条告警的问题
     */
    public static Plan render(
            final Collection<EnchantDefinition> definitions,
            final ConfigService.Datapack datapack
    ) {
        final Map<String, String> files = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> tagged = new ArrayList<>();

        // 固定按 id 排序：重写后文件内容与 tag 顺序稳定，服主对比新旧文件时不会被乱序干扰。
        final List<EnchantDefinition> sorted = new ArrayList<>(null == definitions ? List.of() : definitions);

        sorted.sort(Comparator.comparing(EnchantDefinition::id));

        for (final EnchantDefinition definition : sorted) {
            if (!definition.enabled()) {
                continue;
            }

            if (definition.items().isEmpty()) {
                // 空 items 在插件里表示「不限制物品」，但数据包里没有等价写法：
                // 写空列表会被原版当成「任何物品都不支持」，退化成该附魔永远刷不到。
                // 这里选择不写入并告警——「限制物品」是数据包注册的必要条件。
                warnings.add(
                        "附魔 '" + definition.id() + "' 没有写 items，已跳过不进数据包"
                                + "（数据包里 items 为空会导致该附魔永远刷不到，请补上 items 后重载）"
                );

                continue;
            }

            if (MAX_LANG_LEVEL < definition.maxLevel()) {
                warnings.add(
                        "附魔 '" + definition.id() + "' 的 max-level=" + definition.maxLevel()
                                + " 超过 10：照常注册，但客户端会把等级后缀显示成 'enchantment.level."
                                + definition.maxLevel() + "' 原文。要修掉请把 max-level 降到 10 以内，"
                                + "或提供补充 enchantment.level.N 的语言资源包"
                );
            }

            files.put(ENCHANTMENT_DIR + "/" + definition.id() + ".json", enchantmentJson(definition, datapack));
            tagged.add(NAMESPACE + ":" + definition.id());
        }

        files.put(TABLE_TAG, tableTagJson(tagged));
        files.put(PACK_META, packMeta());

        return new Plan(files, warnings);
    }

    /**
     * 把 {@link #render} 的结果落盘到 {@code <worldFolder>/datapacks/jsenchants/}。
     *
     * <p>失败只告警：数据包注册不上最多是「附魔台刷不到」，其他功能（触发、Lore、铁砧）
     * 不依赖它，所以任何 IO 异常都不能阻断插件启用。
     *
     * @param worldFolder 世界文件夹（{@code World#getWorldFolder()}），{@code null} 表示没有可用世界
     */
    public Report write(
            final Collection<EnchantDefinition> definitions,
            final ConfigService.Datapack datapack,
            final Path worldFolder
    ) {
        if (!datapack.enabled()) {
            // 关闭时连清理都不做：世界目录里的文件是服主自己决定要不要保留的东西。
            return Report.skipped(List.of());
        }

        if (null == worldFolder) {
            return Report.skipped(List.of("没有可用世界，数据包未生成（附魔台维度不可用）"));
        }

        final Plan plan = render(definitions, datapack);
        final Path root = worldFolder.resolve("datapacks").resolve(PACK_ID);

        int enchantments = 0;

        for (final String name : plan.files().keySet()) {
            if (name.startsWith(ENCHANTMENT_DIR + "/")) {
                enchantments++;
            }
        }

        try {
            for (final Map.Entry<String, String> file : plan.files().entrySet()) {
                final Path target = root.resolve(file.getKey());

                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (final IOException exception) {
            this.logger.log(Level.WARNING, "写入附魔数据包失败，附魔台维度不可用: " + root, exception);

            return Report.skipped(plan.warnings());
        }

        final int removed = this.removeStale(root.resolve(ENCHANTMENT_DIR), plan.files().keySet());

        for (final String warning : plan.warnings()) {
            this.logger.warning(warning);
        }

        this.logger.info(
                "附魔数据包已写入 " + root + "：附魔 " + enchantments + " 个"
                        + (0 < removed ? "，清理已下线 " + removed + " 个" : "")
                        + "。数据包只在世界加载时生效，需 /reload 或重启后附魔台才能刷到。"
        );

        return new Report(true, enchantments, removed, plan.warnings());
    }

    /**
     * 删除已下线附魔留下的 JSON。
     *
     * <p>只动 {@code data/jsenchants/enchantment/} 下的 {@code *.json}：数据包目录里可能有
     * 服主自己加的文件或别的子目录，一律不碰。删不掉的（被占用等）只告警。
     *
     * @param directory 附魔 JSON 目录
     * @param keep      本次应当存在的相对路径集合
     * @return 实际删除的文件数
     */
    private int removeStale(final Path directory, final Collection<String> keep) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }

        int removed = 0;

        try (final Stream<Path> stream = Files.list(directory)) {
            for (final Path path : stream.toList()) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".json")) {
                    continue;
                }

                if (keep.contains(ENCHANTMENT_DIR + "/" + path.getFileName())) {
                    continue;
                }

                try {
                    Files.delete(path);
                    removed++;
                } catch (final IOException exception) {
                    this.logger.log(Level.WARNING, "清理已下线附魔的旧数据文件失败: " + path, exception);
                }
            }
        } catch (final IOException exception) {
            this.logger.log(Level.WARNING, "列举附魔数据包目录失败，旧文件未清理: " + directory, exception);
        }

        return removed;
    }

    /**
     * 单个附魔的 JSON。
     *
     * <p>字段名对着原版 {@code Enchantment}（{@code description / exclusive_set / effects}）
     * 与 {@code Enchantment.EnchantmentDefinition}（其余八项）的编解码器写：
     * 少一个必填字段整个文件不加载，字段名拼错也是同样的表现。
     *
     * <p>{@code exclusive_set} 与 {@code effects} 在编解码器里都是可选的，所以这里**空就直接省略**，
     * 不写 {@code []} / {@code {}}。依据是原版自己的数据文件：{@code looting.json} 不写
     * {@code exclusive_set}，两者都存在时才写。省略与"写空"语义等价，但少一处解析歧义——
     * 这个 JSON 一旦解析失败，整个数据包连带所有附魔一起消失，代价太大。
     *
     * <p>我们不给 {@code effects}：原版侧不提供任何行为（没有属性加成、没有伤害修改），
     * 全部效果由 JS 脚本实现。这也是"注册出来是合法但无效果的附魔"的原因。
     */
    static String enchantmentJson(final EnchantDefinition definition, final ConfigService.Datapack datapack) {
        final String items = itemList(definition.items());

        return "{\n"
                + "  \"description\": " + description(definition) + ",\n"
                + "  \"supported_items\": " + items + ",\n"
                + "  \"primary_items\": " + items + ",\n"
                + "  \"weight\": " + datapack.weight() + ",\n"
                + "  \"max_level\": " + definition.maxLevel() + ",\n"
                + "  \"min_cost\": " + cost(datapack.minCostBase(), datapack.minCostPerLevel()) + ",\n"
                + "  \"max_cost\": " + cost(datapack.maxCostBase(), datapack.maxCostPerLevel()) + ",\n"
                + "  \"anvil_cost\": " + datapack.anvilCost() + ",\n"
                + "  \"slots\": " + slotList(definition.slots()) + "\n"
                + "}\n";
    }

    /**
     * 展示名转 JSON 文本组件。
     *
     * <p>刻意用 {@code {"text": "..."}} 而不是 {@code {"translate": "enchantment.jsenchants.<id>"}}：
     * 后者要求服主装一个语言资源包，否则 tooltip 会露出翻译键原文。display 是 MiniMessage，
     * 这里取纯文本，颜色仍由插件自己的 Lore 行负责（原版 {@code getFullname} 会把名字统一染灰，
     * 所以把颜色写进数据包也没用）。
     */
    private static String description(final EnchantDefinition definition) {
        final String plain = plainText(definition);

        return "{\"text\": \"" + escape(plain) + "\"}";
    }

    /** display 的纯文本；display 全是标签或为空时退回 id，避免出现没有名字的附魔。 */
    private static String plainText(final EnchantDefinition definition) {
        final String display = definition.display();

        if (null == display || display.isBlank()) {
            return definition.id();
        }

        try {
            final String plain = PLAIN_TEXT.serialize(MINI_MESSAGE.deserialize(display)).trim();

            return plain.isEmpty() ? definition.id() : plain;
        } catch (final RuntimeException exception) {
            // MiniMessage 解析失败不该让整批数据包写不出来：退回原文（至少还能认出来是谁）。
            return display;
        }
    }

    private static String cost(final int base, final int perLevel) {
        return "{\"base\": " + base + ", \"per_level_above_first\": " + perLevel + "}";
    }

    /**
     * 物品列表：{@code ["minecraft:diamond_sword", ...]}。
     *
     * <p>用 {@link TreeSet} 去重并排序：同一材质写两遍在 JSON 里没有意义，
     * 而 yml 的 {@code Set} 迭代顺序不稳定，排序后重写才不会每次 diff 都变。
     */
    private static String itemList(final Collection<Material> materials) {
        final TreeSet<String> ids = new TreeSet<>();

        for (final Material material : materials) {
            if (null != material) {
                ids.add("minecraft:" + materialId(material));
            }
        }

        return list(ids);
    }

    /**
     * 材质的原版 id。
     *
     * <p>{@link Material#getKey()} 在部分版本里要读原版注册表，单测环境下不可用，
     * 因此退回「枚举名小写」——现代版本的 {@code Material} 枚举名与原版 id 一一对应。
     */
    private static String materialId(final Material material) {
        try {
            return material.getKey().getKey();
        } catch (final RuntimeException exception) {
            return material.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 触发槽位到原版 {@code slots} 的映射。
     *
     * <p>原版要的是「槽位组」的 key（{@code mainhand / offhand / head / chest / legs / feet /
     * body / saddle / any}），不是 Bukkit 的枚举名。写错的值会让该附魔文件直接不加载。
     */
    static String slotList(final Collection<EquipmentSlot> slots) {
        if (null == slots || slots.isEmpty()) {
            // 与 yml 缺省一致：默认只看主手。
            return "[\"mainhand\"]";
        }

        final TreeSet<String> names = new TreeSet<>();

        for (final EquipmentSlot slot : slots) {
            if (null != slot) {
                names.add(slotId(slot));
            }
        }

        return names.isEmpty() ? "[\"mainhand\"]" : list(names);
    }

    private static String slotId(final EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> "mainhand";
            case OFF_HAND -> "offhand";
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            case BODY -> "body";
            case SADDLE -> "saddle";
        };
    }

    private static String list(final Collection<String> values) {
        final StringBuilder json = new StringBuilder("[");

        boolean first = true;

        for (final String value : values) {
            if (!first) {
                json.append(", ");
            }

            json.append('"').append(escape(value)).append('"');
            first = false;
        }

        return json.append(']').toString();
    }

    /**
     * 附魔台 tag：把我们的 id 追加进 {@code #minecraft:in_enchanting_table}。
     *
     * <p>{@code replace:false} 是让原版已有的 {@code #minecraft:non_treasure} 继续生效；
     * 写成 {@code true} 会把原版附魔全部挤出附魔台——这是最难察觉的破坏，所以有测试钉住。
     */
    private static String tableTagJson(final List<String> values) {
        return "{\n  \"replace\": false,\n  \"values\": " + list(values) + "\n}\n";
    }

    private static String packMeta() {
        // 三个字段一起写：pack_format 给旧读取路径，min/max_format 给 26.x 的新范围校验。
        // 只写前者可能被判为不支持新版格式，整包失效且不报错。
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + PACK_FORMAT + ",\n"
                + "    \"min_format\": [" + PACK_FORMAT + ", " + PACK_FORMAT_MINOR + "],\n"
                + "    \"max_format\": [" + PACK_FORMAT + ", " + PACK_FORMAT_MINOR + "],\n"
                + "    \"description\": \"JsEnchants 自定义附魔注册（自动生成，请勿手改）\"\n"
                + "  }\n"
                + "}\n";
    }

    /**
     * JSON 字符串转义。
     *
     * <p>只处理 JSON 必须转义的两种字符加控制字符：展示名里的中文直接按 UTF-8 落盘，
     * 服务端读 JSON 时按 UTF-8 解码，不需要（也不该）转成 {@code \\uXXXX}。
     */
    private static String escape(final String text) {
        final StringBuilder escaped = new StringBuilder(text.length() + 8);

        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);

            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (0x20 > character) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }

    /**
     * 待落盘的数据包内容。
     *
     * @param files    相对数据包根目录的路径到 JSON 文本
     * @param warnings 需要逐条告警的问题（跳过的附魔、等级越界等）
     */
    public record Plan(Map<String, String> files, List<String> warnings) {

        public Plan {
            // 保留插入顺序（附魔按 id 排序后加入）：落盘顺序与诊断顺序都稳定。
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * 落盘结果。
     *
     * @param written      是否成功写入（失败时其余字段无意义）
     * @param enchantments 写入的附魔 JSON 数量
     * @param removed      清理掉的已下线附魔文件数量
     * @param warnings     逐条告警文本（已同时写进日志）
     */
    public record Report(boolean written, int enchantments, int removed, List<String> warnings) {

        public Report {
            warnings = List.copyOf(warnings);
        }

        static Report skipped(final List<String> warnings) {
            return new Report(false, 0, 0, warnings);
        }
    }
}
