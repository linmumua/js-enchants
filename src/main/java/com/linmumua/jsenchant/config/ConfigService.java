package com.linmumua.jsenchant.config;

import com.linmumua.jsenchant.enchant.EnchantRarity;
import com.linmumua.jsenchant.script.ScriptSandbox;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code config.yml} 的读取层。
 *
 * <p>为什么不用 {@code JavaPlugin#getConfig()}：本插件的业务入口不是 {@code JavaPlugin}，
 * 也没有 {@code YamlConfiguration} 的类型推断能力。统一走 SnakeYAML 的
 * {@link SafeConstructor} 加受限 {@link LoaderOptions}，与附魔定义解析保持同一套规则。
 *
 * <p>失败原则是「保留上一版并大声告警」：服主手改坏一个缩进不应该让已生效的 Lore 配置
 * 突然退回默认值——那会让玩家看到的物品说明莫名变化。因此 {@link #load()} 返回
 * {@code false} 时，{@link #current()} 仍是上一次成功加载的快照。
 */
public final class ConfigService {

    /**
     * 战利品注入只支持原版结构容器。
     *
     * <p>写死前缀而不是「任意 namespace」：本轮明确不覆盖钓鱼、生物掉落与第三方插件表，
     * 也不允许把 {@code minecraft:chests/} 放宽成 {@code minecraft:entities/}。
     */
    private static final String LOOT_TABLE_PREFIX = "minecraft:chests/";

    /**
     * 铁砧花费的上限。
     *
     * <p>原版铁砧在总花费达到 40 级时直接拒绝合成（Too Expensive），所以插件允许配置的费用
     * 必须留在 40 以下——否则服主一旦写成 50，这本附魔书就永远合不出去，看起来像插件坏了。
     */
    private static final int MAX_ANVIL_LEVEL_COST = 39;

    /**
     * 数据包数值项的最大值。
     *
     * <p>只用来挡住「误填成天文数字」：{@code weight} 与 cost 都是原版整数字段，
     * 填到溢出量级既没有实际意义，也会让附魔台的候选计算变成一个不可能命中的区间。
     */
    private static final int MAX_DATAPACK_VALUE = 1000;

    /** 调色板缺项时的回落模板（与 {@code config.yml} 的 {@code lore.format} 同值）。 */
    private static final String DEFAULT_LORE_FORMAT = "<gray>{display} <dark_gray>{tier}";

    /** 逐档配色默认值，必须与随插件分发的 {@code config.yml} 保持一致。 */
    private static final LorePalette DEFAULT_PALETTE = new LorePalette(
            DEFAULT_LORE_FORMAT,
            "<green>{display} <dark_green>{tier}",
            "<aqua>{display} <dark_aqua>{tier}",
            "<light_purple>{display} <dark_purple>{tier}"
    );

    /**
     * 默认登记的旧模板：上一版把等级写成数字的四档默认模板。
     *
     * <p>升级到「阶位」写法之后，存量物品上用旧模板渲染过的行只有靠这份登记才能被认出来
     * 并清掉；缺了它，旧行会永久残留且 {@code lore_lines} 配额也删不掉。
     */
    private static final List<String> DEFAULT_LEGACY_FORMATS = List.of(
            "<gray>{display} <dark_gray>{level}",
            "<green>{display} <dark_green>{level}",
            "<aqua>{display} <dark_aqua>{level}",
            "<light_purple>{display} <dark_purple>{level}"
    );

    /**
     * 数据包默认参数。
     *
     * <p>这组数字不是随手填的：附魔台的 power 在 0 书架时约 1~8、15 书架时约 15~30，
     * {@code min-cost.base=1} 保证「1 级在低书架就能命中」，{@code max-cost.base=21}
     * 让 15 书架能一路走到高等级。数值写错的症状是「台面有价格、点了没反应」。
     */
    private static final Datapack DEFAULT_DATAPACK = new Datapack(true, 10, 1, 11, 21, 11, 1);

    /** 默认值必须与随插件分发的 {@code config.yml} 保持一致。 */
    private static final Snapshot DEFAULTS = new Snapshot(
            DEFAULT_LORE_FORMAT,
            true,
            DEFAULT_LEGACY_FORMATS,
            DEFAULT_PALETTE,
            DEFAULT_DATAPACK,
            false,
            ScriptSandbox.DEFAULT_PACKAGES,
            List.of("PlayerMoveEvent", "BlockPhysicsEvent", "PlayerInteractAtEntityEvent"),
            new LootBooks(true, 0.10D, List.of(LOOT_TABLE_PREFIX + "*"), 1)
    );

    /** 配置文件很小，给一个宽松但有限的上限，防止误粘贴大文件。 */
    private static final int CODE_POINT_LIMIT = 1 << 16;

    private final Logger logger;
    private final Path file;

    private volatile Snapshot snapshot = DEFAULTS;

    public ConfigService(final Logger logger, final Path file) {
        this.logger = logger;
        this.file = file;
    }

    /** 当前生效配置。加载失败时仍是上一次成功的快照。 */
    public Snapshot current() {
        return this.snapshot;
    }

    /**
     * 重新读取配置文件。
     *
     * @return 成功替换快照时返回 {@code true}；文件缺失、为空或内容非法时返回
     *         {@code false}，并保留上一版快照
     */
    public boolean load() {
        if (!Files.isRegularFile(this.file)) {
            this.logger.warning("配置文件不存在，已保留上一版设置: " + this.file);

            return false;
        }

        final Snapshot parsed;

        try (final Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            final Object root = newYaml().load(reader);

            if (null == root) {
                this.logger.warning("配置文件为空，已保留上一版设置: " + this.file.getFileName());

                return false;
            }

            if (!(root instanceof Map<?, ?> map)) {
                this.logger.warning("配置文件根节点不是映射，已保留上一版设置: " + this.file.getFileName());

                return false;
            }

            parsed = parse(map);
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "读取配置文件失败，已保留上一版设置: " + this.file.getFileName(), exception);

            return false;
        } catch (final RuntimeException exception) {
            // 语法错误、重复键、非法类型都由 SnakeYAML 或本类抛出，一律视为该文件不可用。
            this.logger.log(
                    Level.SEVERE,
                    "配置文件无效，已保留上一版设置: " + this.file.getFileName() + " (" + exception.getMessage() + ")",
                    exception
            );

            return false;
        }

        this.snapshot = parsed;

        return true;
    }

    private static Yaml newYaml() {
        final LoaderOptions options = new LoaderOptions();

        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setProcessComments(false);

        return new Yaml(
                new SafeConstructor(options),
                new Representer(new DumperOptions()),
                new DumperOptions(),
                options
        );
    }

    private Snapshot parse(final Map<?, ?> root) {
        String loreFormat = DEFAULTS.loreFormat();
        boolean loreEnabled = DEFAULTS.loreEnabled();
        List<String> loreLegacyFormats = DEFAULTS.loreLegacyFormats();
        LorePalette lorePalette = DEFAULTS.lorePalette();

        final Object loreNode = root.get("lore");

        if (null != loreNode) {
            if (!(loreNode instanceof Map<?, ?> lore)) {
                throw new InvalidConfig("lore 必须是映射");
            }

            final Object format = lore.get("format");

            if (null != format) {
                if (!(format instanceof String text)) {
                    throw new InvalidConfig("lore.format 必须是字符串（含 MiniMessage 标签时请加引号）");
                }

                if (text.isBlank()) {
                    throw new InvalidConfig("lore.format 不能为空");
                }

                loreFormat = text;
            }

            loreEnabled = bool(lore, "enabled", loreEnabled);
            loreLegacyFormats = this.legacyFormats(lore);
            lorePalette = this.palette(lore, loreFormat, loreLegacyFormats);
        }

        return new Snapshot(
                loreFormat,
                loreEnabled,
                loreLegacyFormats,
                lorePalette,
                datapack(root),
                bool(root, "debug", DEFAULTS.debug()),
                packages(root),
                events(root),
                lootBooks(root)
        );
    }

    /**
     * {@code lore.legacy-formats}：过去用过、现在<b>只</b>用于识别旧 Lore 行的模板。
     *
     * <p>为什么要这一项：旧行是靠「纯文本完全相等」识别的，模板的文本形态一改（例如等级从
     * {@code Lv.3} 换成阶位 {@code III}），存量物品上的旧行就再也对不上，会永久残留且
     * {@code lore_lines} 配额也删不掉。
     *
     * <p><b>宽松读取</b>：这一项的用处只是「让旧行能被清掉」，写错了最坏等于没配；
     * 而整份拒绝会让快照退回内置默认模板，反而让更多旧行失联。所以这里只告警不抛异常。
     */
    private List<String> legacyFormats(final Map<?, ?> lore) {
        final Object node = lore.get("legacy-formats");

        if (null == node) {
            return List.of();
        }

        if (!(node instanceof List<?> list)) {
            this.logger.severe(
                    "lore.legacy-formats 必须是字符串列表，实际为 '" + node + "'；该项已按空列表处理。"
            );

            return List.of();
        }

        final List<String> parsed = new ArrayList<>(list.size());

        for (final Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) {
                this.logger.severe(
                        "lore.legacy-formats 只接受非空的模板字符串，已忽略 '" + entry + "'。"
                );

                continue;
            }

            parsed.add(text.trim());
        }

        return List.copyOf(parsed);
    }

    /**
     * {@code lore.rarity}：按稀有度统一配色。
     *
     * <p>四档缺项一律回落到 {@code lore.format}，而不是内置默认色：升级前写下的
     * {@code config.yml} 没有这一节，回落到旧模板才能保证「改完插件颜色不变」；
     * 反过来用内置默认色，老服主会看到物品说明突然换色。
     *
     * <p>整节缺席时四档全部回落到 {@code lore.format}，等于维持单色——这是旧配置的正常表现，
     * 不是错误，所以只在没有 {@code lore.format} 自定义时才看得出来。
     *
     * @param legacyFormats 随调色板一起带上的 {@code lore.legacy-formats}（见 {@link LorePalette}）
     */
    private LorePalette palette(final Map<?, ?> lore, final String loreFormat, final List<String> legacyFormats) {
        final Object node = lore.get("rarity");

        if (null == node) {
            return new LorePalette(loreFormat, loreFormat, loreFormat, loreFormat, legacyFormats);
        }

        if (!(node instanceof Map<?, ?> rarity)) {
            throw new InvalidConfig("lore.rarity 必须是映射（common / uncommon / rare / epic）");
        }

        return new LorePalette(
                template(rarity, "common", loreFormat),
                template(rarity, "uncommon", loreFormat),
                template(rarity, "rare", loreFormat),
                template(rarity, "epic", loreFormat),
                legacyFormats
        );
    }

    /**
     * 单个稀有度的 Lore 模板。
     *
     * <p>必须含 {@code {display}}，且必须含 {@code {level}} 或 {@code {tier}} 之一：
     * 少 {@code {display}} 的物品上只剩等级，少等级占位符的只剩名字，两种情况都
     * 「看不出来是模板写错了」。等级用阿拉伯数字还是阶位罗马数字随模板自己选。
     *
     * <p><b>但这里刻意不整份拒绝</b>：模板决定物品 Lore 的**纯文本形态**，而旧物品的
     * 自有 Lore 行是靠"纯文本完全相等"识别的。一旦整份配置因一个笔误被拒绝、快照回退到
     * 默认模板，{@code legacyOwnLineMatcher} 就再也认不出存量旧行——它们会永久残留，
     * 连 {@code lore_lines} 配额都删不掉。所以单项写错只让该项回落到 {@code lore.format}
     * 并记 SEVERE，危害远小于整份回退。
     */
    private String template(final Map<?, ?> rarity, final String key, final String fallback) {
        final Object value = rarity.get(key);

        if (null == value) {
            return fallback;
        }

        if (!(value instanceof String text) || text.isBlank()) {
            return this.rejectedTemplate(key, value, "必须是非空字符串（含 MiniMessage 标签时请加引号）", fallback);
        }

        if (!text.contains("{display}")) {
            return this.rejectedTemplate(
                    key, text, "必须包含 {display} 占位符，且含 {level} 或 {tier} 之一", fallback
            );
        }

        if (!text.contains("{level}") && !text.contains("{tier}")) {
            return this.rejectedTemplate(
                    key, text, "必须包含 {level} 或 {tier} 占位符（前者是数字 3，后者是阶位 III）", fallback
            );
        }

        return text;
    }

    /** 单项模板写错：告警并回落到 {@code lore.format}，不牵连其它项与整份配置。 */
    private String rejectedTemplate(final String key, final Object value, final String reason, final String fallback) {
        this.logger.severe(
                "lore.rarity." + key + " " + reason + "，实际为 '" + value + "'；"
                        + "该项已回落到 lore.format。注意：若这里写错，旧物品上的 Lore 行可能无法被识别清理。"
        );

        return fallback;
    }

    /**
     * {@code datapack}：把启用附魔写成原版数据包，使其能被附魔台刷到。
     *
     * <p>缺节时用默认参数（升级场景），写错才整份拒绝——与 {@code loot-books} 同一条原则。
     */
    private static Datapack datapack(final Map<?, ?> root) {
        final Datapack fallback = DEFAULTS.datapack();
        final Object node = root.get("datapack");

        if (null == node) {
            return fallback;
        }

        if (!(node instanceof Map<?, ?> map)) {
            throw new InvalidConfig("datapack 必须是映射");
        }

        final int[] minCost = cost(map, "min-cost", fallback.minCostBase(), fallback.minCostPerLevel());
        final int[] maxCost = cost(map, "max-cost", fallback.maxCostBase(), fallback.maxCostPerLevel());

        return new Datapack(
                bool(map, "enabled", fallback.enabled()),
                integer(map, "datapack.weight", map.get("weight"), fallback.weight(), 1),
                minCost[0],
                minCost[1],
                maxCost[0],
                maxCost[1],
                integer(map, "datapack.anvil-cost", map.get("anvil-cost"), fallback.anvilCost(), 1)
        );
    }

    /**
     * {@code datapack.min-cost} / {@code datapack.max-cost} 的 {@code base} 与 {@code per-level}。
     *
     * @return 长度为 2 的数组：{@code [0]=base}（允许 0）、{@code [1]=per-level}（必须 ≥1）
     */
    private static int[] cost(
            final Map<?, ?> map,
            final String key,
            final int fallbackBase,
            final int fallbackPerLevel
    ) {
        final Object node = map.get(key);

        if (null == node) {
            return new int[]{fallbackBase, fallbackPerLevel};
        }

        if (!(node instanceof Map<?, ?> cost)) {
            throw new InvalidConfig("datapack." + key + " 必须是映射（含 base 与 per-level）");
        }

        return new int[]{
                // base 允许 0：1 级门槛 0 是合法配置（只是更容易刷出来）。
                integer(cost, "datapack." + key + ".base", cost.get("base"), fallbackBase, 0),
                // per-level 必须 ≥1：0 会让高等级永不增长，附魔台永远只出 1 级。
                integer(cost, "datapack." + key + ".per-level", cost.get("per-level"), fallbackPerLevel, 1)
        };
    }

    /**
     * 读取一个非负整数配置项。
     *
     * <p>不写该项时用默认值；写了就必须是落在 {@code [min, MAX_DATAPACK_VALUE]} 的整数——
     * {@code 2.5} 与 {@code "10"} 都拒绝，两者都会让人以为改的配置根本没生效。
     */
    private static int integer(
            final Map<?, ?> map,
            final String path,
            final Object value,
            final int fallback,
            final int min
    ) {
        if (null == value) {
            return fallback;
        }

        if (!(value instanceof Number number)) {
            throw new InvalidConfig(path + " 必须是整数，实际为 '" + value + "'");
        }

        final double parsed = number.doubleValue();

        if (parsed != Math.rint(parsed) || min > parsed || MAX_DATAPACK_VALUE < parsed) {
            throw new InvalidConfig(
                    path + " 必须是 " + min + " 到 " + MAX_DATAPACK_VALUE + " 之间的整数，实际为 '" + value + "'"
            );
        }

        return (int) parsed;
    }

    /**
     * {@code loot-books}：原版结构容器战利品里追加插件附魔书。
     *
     * <p>旧配置没有这一节时必须落到明确默认值（而不是「关掉」）：缺节和写错是两件事，
     * 缺节是升级场景，写错才应该整份拒绝并保留上一版。
     */
    private static LootBooks lootBooks(final Map<?, ?> root) {
        final LootBooks fallback = DEFAULTS.lootBooks();
        final Object node = root.get("loot-books");

        if (null == node) {
            return fallback;
        }

        if (!(node instanceof Map<?, ?> map)) {
            throw new InvalidConfig("loot-books 必须是映射");
        }

        return new LootBooks(
                bool(map, "enabled", fallback.enabled()),
                chance(map, fallback.chance()),
                tables(map, fallback.tables()),
                anvilLevelCost(map, fallback.anvilLevelCost())
        );
    }

    /**
     * {@code loot-books.chance}：每次容器生成时「出一本书」的总概率。
     *
     * <p>只接受数字字面量：写成字符串 {@code "0.5"} 拒绝整份配置，而不是替服主静默转换。
     */
    private static double chance(final Map<?, ?> map, final double fallback) {
        final Object value = map.get("chance");

        if (null == value) {
            return fallback;
        }

        if (!(value instanceof Number number)) {
            throw new InvalidConfig("loot-books.chance 必须是 0 到 1 之间的数字，实际为 '" + value + "'");
        }

        final double parsed = number.doubleValue();

        // YAML 的 .nan/.inf 也会走到这里，一并拒绝：非有限值没法当作概率使用。
        if (!Double.isFinite(parsed) || 0.0D > parsed || 1.0D < parsed) {
            throw new InvalidConfig("loot-books.chance 必须是 0 到 1 之间的有限数字，实际为 '" + value + "'");
        }

        return parsed;
    }

    /** {@code loot-books.anvil-level-cost}：铁砧上应用一本书扣的经验等级。 */
    private static int anvilLevelCost(final Map<?, ?> map, final int fallback) {
        final Object value = map.get("anvil-level-cost");

        if (null == value) {
            return fallback;
        }

        if (!(value instanceof Number number)) {
            throw new InvalidConfig("loot-books.anvil-level-cost 必须是整数等级，实际为 '" + value + "'");
        }

        final double parsed = number.doubleValue();

        // NaN 与小数都在这里被拒：花费是「等级」，2.5 级在原版铁砧里没有意义。
        if (parsed != Math.rint(parsed) || 1.0D > parsed || MAX_ANVIL_LEVEL_COST < parsed) {
            throw new InvalidConfig(
                    "loot-books.anvil-level-cost 必须是 1 到 " + MAX_ANVIL_LEVEL_COST
                            + " 之间的整数（原版铁砧 40 级起拒绝合成），实际为 '" + value + "'"
            );
        }

        return (int) parsed;
    }

    /**
     * {@code loot-books.tables}：允许注入的战利品表。
     *
     * <p>只接受 {@code minecraft:chests/} 前缀下的精确 key 或尾部 {@code *} 通配；
     * 空列表直接拒绝——想关闭功能应该写 {@code enabled: false}，而不是留一个空表让人以为坏了。
     */
    private static List<String> tables(final Map<?, ?> map, final List<String> fallback) {
        final Object value = map.get("tables");

        if (null == value) {
            return fallback;
        }

        if (!(value instanceof List<?> list)) {
            throw new InvalidConfig("loot-books.tables 必须是战利品表 key 的字符串列表，实际为 '" + value + "'");
        }

        if (list.isEmpty()) {
            throw new InvalidConfig("loot-books.tables 不能是空列表：想关闭本功能请把 enabled 设为 false");
        }

        final List<String> parsed = new ArrayList<>(list.size());

        for (final Object node : list) {
            if (!(node instanceof String text)) {
                throw new InvalidConfig("loot-books.tables 只接受字符串，实际为 '" + node + "'");
            }

            final String key = text.trim();

            if (!isChestTableKey(key)) {
                throw new InvalidConfig(
                        "loot-books.tables 只接受 " + LOOT_TABLE_PREFIX + " 下的原版容器战利品表"
                                + "（精确 key 或以 * 结尾的通配），实际为 '" + key + "'"
                );
            }

            parsed.add(key);
        }

        return List.copyOf(parsed);
    }

    /**
     * 容器战利品表 key 的形态检查：{@code minecraft:chests/<path>}，{@code *} 只允许出现在末尾。
     *
     * <p>逐字符校验而不是只判前缀：{@code minecraft:chests/../../x} 或中间夹 {@code *}
     * 都会让「匹配到哪张表」变得不可预测。
     */
    private static boolean isChestTableKey(final String key) {
        if (!key.startsWith(LOOT_TABLE_PREFIX)) {
            return false;
        }

        final String path = key.substring(LOOT_TABLE_PREFIX.length());

        if (path.isEmpty()) {
            return false;
        }

        for (int index = 0; index < path.length(); index++) {
            final char character = path.charAt(index);

            if ('*' == character) {
                if (index != path.length() - 1) {
                    return false;
                }

                continue;
            }

            if (('a' > character || 'z' < character)
                    && ('0' > character || '9' < character)
                    && '/' != character
                    && '_' != character
                    && '.' != character
                    && '-' != character) {
                return false;
            }
        }

        return true;
    }

    /**
     * {@code scripts.allowed-packages}：脚本能触达的第三方包前缀。
     *
     * <p>整体替换而非追加——放开了什么必须一眼能从配置文件读出来，不能靠猜测底层默认值。
     * 硬拒绝清单（反射 / IO / Runtime 等）不在这里，它是代码里钉死的。
     */
    private static List<String> packages(final Map<?, ?> root) {
        final Object scriptsNode = root.get("scripts");

        if (null == scriptsNode) {
            return DEFAULTS.allowedPackages();
        }

        if (!(scriptsNode instanceof Map<?, ?> scripts)) {
            throw new InvalidConfig("scripts 必须是映射");
        }

        final List<String> parsed = strings(scripts, "allowed-packages");

        return parsed.isEmpty() ? DEFAULTS.allowedPackages() : parsed;
    }

    /**
     * {@code events.blocked}：默认禁止注册的事件简名。
     *
     * <p>只约束「用户自定义事件名」，内置短名（ATTACK/MINE/RIGHT_CLICK 等）走各自的
     * 特化绑定，永远不受这里影响——否则把 {@code PlayerInteractEvent} 写进本表
     * 会顺手让 RIGHT_CLICK 失效。
     */
    private static List<String> events(final Map<?, ?> root) {
        final Object eventsNode = root.get("events");

        if (null == eventsNode) {
            return DEFAULTS.blockedEvents();
        }

        if (!(eventsNode instanceof Map<?, ?> events)) {
            throw new InvalidConfig("events 必须是映射");
        }

        // 显式写空列表表示「我一个都不想禁」；不写才用默认值。
        final Object blockedNode = events.get("blocked");

        if (null == blockedNode) {
            return DEFAULTS.blockedEvents();
        }

        final List<String> parsed = strings(events, "blocked");

        return parsed;
    }

    private static List<String> strings(final Map<?, ?> map, final String key) {
        final Object value = map.get(key);

        if (null == value) {
            return List.of();
        }

        if (!(value instanceof List<?> list)) {
            throw new InvalidConfig(key + " 必须是字符串列表，实际为 '" + value + "'");
        }

        final List<String> parsed = new ArrayList<>(list.size());

        for (final Object node : list) {
            if (!(node instanceof String text) || text.isBlank()) {
                throw new InvalidConfig(key + " 只接受非空字符串，实际为 '" + node + "'");
            }

            parsed.add(text.trim());
        }

        return List.copyOf(parsed);
    }

    /**
     * 布尔值必须是真正的 {@code true}/{@code false}：写成 {@code yes}/{@code 1} 时拒绝整份配置，
     * 而不是悄悄按默认值走——静默回退会让人以为改的配置根本没生效。
     */
    private static boolean bool(final Map<?, ?> map, final String key, final boolean fallback) {
        final Object value = map.get(key);

        if (null == value) {
            return fallback;
        }

        if (value instanceof Boolean flag) {
            return flag;
        }

        throw new InvalidConfig(key + " 必须是 true 或 false（不要加引号），实际为 '" + value + "'");
    }

    /** 配置内容不可用；由 {@link #load()} 统一转成一条告警并保留上一版。 */
    private static final class InvalidConfig extends RuntimeException {

        private InvalidConfig(final String message) {
            super(message, null, false, false);
        }
    }

    /**
     * 生效配置快照。整体替换，不原地修改。
     *
     * @param loreFormat        Lore 行模板（legacy 字段：{@code lore.rarity} 缺项时的回落模板）
     * @param loreEnabled       是否接管物品 Lore
     * @param loreLegacyFormats {@code lore.legacy-formats}：过去用过、只用于识别旧 Lore 行的模板
     * @param lorePalette       按稀有度取用的 Lore 模板
     * @param datapack          原版附魔数据包生成设置
     * @param debug             诊断开关
     * @param allowedPackages   脚本可触达的第三方包前缀（硬拒绝清单不在此列）
     * @param blockedEvents     默认禁止注册的事件简名；不影响内置短名
     * @param lootBooks         原版容器战利品注入设置
     */
    public record Snapshot(
            String loreFormat,
            boolean loreEnabled,
            List<String> loreLegacyFormats,
            LorePalette lorePalette,
            Datapack datapack,
            boolean debug,
            List<String> allowedPackages,
            List<String> blockedEvents,
            LootBooks lootBooks
    ) {

        /**
         * 防御拷贝：调用方拿到的表不能再被别处改掉，否则重载后的行为会和配置对不上；
         * {@code null} 按空列表处理，渲染路径不必到处判空。
         */
        public Snapshot {
            loreLegacyFormats = null == loreLegacyFormats ? List.of() : List.copyOf(loreLegacyFormats);
        }

        /**
         * 旧签名构造：调色板与数据包取内置默认值。
         *
         * <p>保留它是为了让「只关心 Lore 与调试开关」的调用方（测试替身、脚本侧拼装）
         * 不必一次性写全九个分量，也避免这些调用方在新增配置段时被迫改动。
         */
        public Snapshot(
                final String loreFormat,
                final boolean loreEnabled,
                final boolean debug,
                final List<String> allowedPackages,
                final List<String> blockedEvents,
                final LootBooks lootBooks
        ) {
            this(
                    loreFormat,
                    loreEnabled,
                    DEFAULT_LEGACY_FORMATS,
                    DEFAULT_PALETTE,
                    DEFAULT_DATAPACK,
                    debug,
                    allowedPackages,
                    blockedEvents,
                    lootBooks
            );
        }
    }

    /**
     * 按稀有度统一使用的 Lore 模板。
     *
     * <p>配色只在这一处定义：改一处即对全部附魔生效，不需要逐条 yml 改 {@code lore}。
     *
     * @param common   普通（{@link EnchantRarity#COMMON}）
     * @param uncommon 罕见（{@link EnchantRarity#UNCOMMON}）
     * @param rare     稀有（{@link EnchantRarity#RARE}）
     * @param epic     史诗（{@link EnchantRarity#EPIC}）
     * @param legacyFormats {@code lore.legacy-formats}：只用于识别旧 Lore 行，不参与渲染。
     *                      它挂在这里是因为 {@code EnchantApplier} 的唯一生产构造点在
     *                      {@code JsEnchantPlugin}，而那一处原样透传的配置对象只有本记录；
     *                      挂在别处就得改那个调用点。
     */
    public record LorePalette(
            String common,
            String uncommon,
            String rare,
            String epic,
            List<String> legacyFormats
    ) {

        /** 防御拷贝 + 缺省空列表，理由同 {@link Snapshot}。 */
        public LorePalette {
            legacyFormats = null == legacyFormats ? List.of() : List.copyOf(legacyFormats);
        }

        /**
         * 只描述四档颜色的旧签名。
         *
         * <p>保留它是为了让「只关心配色」的调用方（测试替身、脚本侧拼装）不必关心
         * 旧模板登记表——那一项缺省即空，等于「不清理旧行」。
         */
        public LorePalette(
                final String common,
                final String uncommon,
                final String rare,
                final String epic
        ) {
            this(common, uncommon, rare, epic, DEFAULT_LEGACY_FORMATS);
        }

        /**
         * 取某一档的模板。
         *
         * @param rarity 稀有度；{@code null} 视作 {@link EnchantRarity#COMMON}，
         *               调用方（历史物品、缺失定义）不必自己兜底
         * @return 该档模板，永不为 {@code null}
         */
        public String templateFor(final EnchantRarity rarity) {
            if (null == rarity) {
                return this.common;
            }

            return switch (rarity) {
                case UNCOMMON -> this.uncommon;
                case RARE -> this.rare;
                case EPIC -> this.epic;
                case COMMON -> this.common;
            };
        }
    }

    /**
     * 原版附魔数据包设置。
     *
     * <p>这些值直接写进 {@code data/jsenchants/enchantment/<id>.json}：{@code min_cost} 与
     * {@code max_cost} 决定附魔台在某个书架数下能不能抽到某个等级，数值配错的表现是
     * 「台面有价格、点了没反应」，不是报错。
     *
     * @param enabled        是否生成数据包；关闭时不动世界目录里的已有文件
     * @param weight         附魔台候选池里的权重，必须 &gt; 0（0 永远抽不到）
     * @param minCostBase    {@code min_cost.base}，允许 0
     * @param minCostPerLevel {@code min_cost.per_level_above_first}，必须 &gt; 0
     * @param maxCostBase    {@code max_cost.base}，允许 0
     * @param maxCostPerLevel {@code max_cost.per_level_above_first}，必须 &gt; 0
     * @param anvilCost      {@code anvil_cost}，必须 ≥ 1
     */
    public record Datapack(
            boolean enabled,
            int weight,
            int minCostBase,
            int minCostPerLevel,
            int maxCostBase,
            int maxCostPerLevel,
            int anvilCost
    ) {
    }

    /**
     * 战利品注入设置。
     *
     * @param enabled        是否在原版容器战利品里追加插件附魔书
     * @param chance         每个容器生成时出一本书的总概率（0~1）；先抽总概率再选附魔，
     *                       所以附魔数量增长不会让掉率膨胀
     * @param tables         允许注入的原版容器战利品表（{@code minecraft:chests/} 精确 key
     *                       或尾部 {@code *} 通配）
     * @param anvilLevelCost 铁砧上应用一本书扣的经验等级
     */
    public record LootBooks(boolean enabled, double chance, List<String> tables, int anvilLevelCost) {

        /** 防御拷贝：调用方拿到的表不能再被别处改掉，否则重载后的行为会和配置对不上。 */
        public LootBooks {
            tables = List.copyOf(tables);
        }
    }
}
