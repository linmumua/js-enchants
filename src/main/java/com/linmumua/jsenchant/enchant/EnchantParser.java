package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.trigger.EventClassResolver;
import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从 {@code enchants/*.yml} 读取附魔定义。
 *
 * <p>统一使用 SnakeYAML，不用 Bukkit 的 {@code YamlConfiguration}——后者对嵌套结构和
 * 类型推断的处理不适合本框架的自定义格式。
 *
 * <p>解析走 {@link SafeConstructor} 加受限的 {@link LoaderOptions}：这份文件由服主手改，
 * 既不能借 YAML 标签实例化宿主类，也不能让重复键、别名炸弹或超大文件拖垮服务端。
 *
 * <p>错误处理原则是「大声失败」：类型写错（例如 {@code chance: high}）必须拒绝整条定义，
 * 不能悄悄退回默认值——静默回退会让附魔看上去存在却永远不触发，比报错更难排查。
 *
 * <p>本类只负责把单个文件解析成定义或失败原因；「一个文件坏了要不要发布整批」由
 * {@link EnchantRegistry} 决定（当前策略是整批不发布，旧快照原样保留）。
 */
public final class EnchantParser {

    /** 单个定义文件的体积上限，防止误粘贴大文件。 */
    private static final int CODE_POINT_LIMIT = 1 << 20;

    private final Logger logger;

    /** 默认禁止注册的事件简名/全名；来自 {@code config.yml} 的 {@code events.blocked}。 */
    private final List<String> blockedEvents;

    public EnchantParser(final Logger logger) {
        this(logger, List.of());
    }

    public EnchantParser(final Logger logger, final List<String> blockedEvents) {
        this.logger = logger;
        this.blockedEvents = null == blockedEvents ? List.of() : List.copyOf(blockedEvents);
    }

    /**
     * 解析单个文件。
     *
     * @return 解析失败时返回 {@code null}
     */
    public EnchantDefinition parse(final Path file) {
        return this.parseOutcome(file).definition();
    }

    /**
     * 解析单个文件并保留失败原因。
     *
     * <p>热重载报告需要「哪个文件、为什么」失败，所以原因不能只写进日志就丢掉。
     * 解析本身仍然只做一次，失败与成功互斥。
     */
    ParseOutcome parseOutcome(final Path file) {
        try (final Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final Object loaded = newYaml().load(reader);

            if (!(loaded instanceof Map<?, ?> map)) {
                return this.reject(file, "顶层不是映射（应为 key: value 结构）");
            }

            return new ParseOutcome(this.build(map, file), null);
        } catch (final InvalidDefinition exception) {
            return this.reject(file, exception.getMessage());
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "读取附魔文件失败: " + file.getFileName(), exception);

            return new ParseOutcome(null, "读取失败: " + exception.getMessage());
        } catch (final RuntimeException exception) {
            // 重复键、未知标签、超限等都由 SnakeYAML 抛出，这里一律视为该文件损坏。
            this.logger.log(Level.SEVERE, "解析附魔文件失败: " + file.getFileName(), exception);

            return new ParseOutcome(null, "YAML 解析失败: " + exception.getMessage());
        }
    }

    private ParseOutcome reject(final Path file, final String error) {
        this.logger.warning("附魔定义无效，已跳过 " + file.getFileName() + ": " + error);

        return new ParseOutcome(null, error);
    }

    /**
     * 每次解析新建一个 {@link Yaml}：SnakeYAML 的实例不适合多线程共享，
     * 而构造开销相对读文件可以忽略。
     */
    private static Yaml newYaml() {
        final LoaderOptions options = new LoaderOptions();

        // 重复键默认会「后者覆盖前者」且没有任何提示，服主手改时极易丢配置。
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setProcessComments(false);

        return new Yaml(
                new SafeConstructor(options),
                new Representer(new DumperOptions()),
                new DumperOptions(),
                options
        );
    }

    private EnchantDefinition build(final Map<?, ?> map, final Path file) {
        final String fallbackId = stripExtension(file.getFileName().toString());

        final String id = this.string(map, "id", fallbackId).toLowerCase(Locale.ROOT);

        if (!id.matches("[a-z0-9_-]+")) {
            throw new InvalidDefinition("id 只允许小写字母、数字、下划线、连字符，实际为 '" + id + "'");
        }

        final TriggerBinding trigger = this.trigger(map);

        final Set<Material> items = new LinkedHashSet<>();

        for (final Object node : this.rawList(map, "items")) {
            if (!(node instanceof String raw)) {
                // 写成数字（例如 items: [1, 2]）几乎一定是把「数量」写到了物品名位置，
                // 静默忽略会让附魔变成「所有物品都能附着」，比直接报错更难排查。
                throw new InvalidDefinition("items 只接受物品名字符串，实际为 '" + node + "'");
            }

            final Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));

            if (null == material) {
                this.logger.warning("未知物品类型 '" + raw + "'，已忽略 (" + file.getFileName() + ")");

                continue;
            }

            items.add(material);
        }

        final Object chanceNode = map.get("chance");
        double chanceBase = 0.0D;
        double chancePerLevel = 0.0D;

        if (chanceNode instanceof Map<?, ?> chance) {
            chanceBase = this.number(chance, "base", 0.0D);
            chancePerLevel = this.number(chance, "per-level", 0.0D);
        } else if (null != chanceNode) {
            // 直接写 chance: 0.25 的简写形式
            chanceBase = this.number(map, "chance", 0.0D);
        }

        final int maxLevel = Math.max(1, (int) this.number(map, "max-level", 1.0D));

        // 必须在 maxLevel 之后解析：缺省值由等级推导，顺序反过来会拿到默认等级。
        final EnchantRarity rarity = this.rarity(map, maxLevel);

        return new EnchantDefinition(
                id,
                this.string(map, "display", id),
                maxLevel,
                Collections.unmodifiableSet(items),
                this.slots(map),
                trigger,
                Math.max(0L, (long) this.number(map, "cooldown-ms", 0.0D)),
                chanceBase,
                chancePerLevel,
                this.string(map, "script", id + ".js"),
                this.bool(map, "enabled", true),
                rarity,
                this.options(map)
        );
    }

    /**
     * 读取 {@code options}：一段完全由附魔自己解释的自定义选项，框架只做类型把关。
     *
     * <p>为什么要有这个通道：像连锁采集「允许连锁哪些方块」这种需求，是单个附魔的知识，
     * 不该为它在本框架里加一个字段——加一个字段就要改定义、改解析、改每个构造点，
     * 而需求方只是想让脚本多读一份配置。
     *
     * <p>取值范围刻意压得很窄：<b>字符串 / 数字 / 布尔</b>，以及它们的<b>一维列表</b>。
     * 嵌套映射一律拒绝——放行嵌套就等于把 SnakeYAML 反序列化出来的任意结构交给脚本，
     * 既难排查（脚本拿到的是个「看起来有值」的怪对象），也给了配置一份不该有的表达力。
     *
     * <p>列表统一规整成字符串列表（数字/布尔按文本存）：脚本侧只会把它当字符串列表读，
     * 留一份混合类型的 List 只会让 {@code ctx.optionList(...)} 的返回值随配置漂移。
     *
     * <p>缺省为空映射；写了但不是映射则整条定义拒绝，与 {@code items}/{@code chance}
     * 的「大声失败」策略一致。
     */
    private Map<String, Object> options(final Map<?, ?> map) {
        final Object node = map.get("options");

        if (null == node) {
            return Map.of();
        }

        if (!(node instanceof Map<?, ?> raw)) {
            throw new InvalidDefinition("options 必须是 key: value 映射，实际为 '" + node + "'");
        }

        final Map<String, Object> parsed = new LinkedHashMap<>();

        for (final Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new InvalidDefinition("options 的键必须是字符串，实际为 '" + entry.getKey() + "'");
            }

            parsed.put(key, optionValue(key, entry.getValue()));
        }

        return parsed;
    }

    /** 单个选项值：标量原样保留，列表规整成字符串列表，其余一律拒绝。 */
    private static Object optionValue(final String key, final Object value) {
        if (null == value) {
            throw new InvalidDefinition(
                    "options." + key + " 不能为空值（要么写值，要么删掉这一项）"
            );
        }

        if (value instanceof Map<?, ?>) {
            throw new InvalidDefinition(
                    "options." + key + " 不支持嵌套映射；只接受字符串/数字/布尔，或它们的列表"
            );
        }

        if (value instanceof List<?> list) {
            final List<String> items = new ArrayList<>(list.size());

            for (final Object item : list) {
                if (null == item || item instanceof Map<?, ?> || item instanceof List<?>) {
                    // 列表里再套列表/映射同样拒绝：脚本侧只会按字符串数组读它。
                    throw new InvalidDefinition(
                            "options." + key + " 的列表元素只能是字符串/数字/布尔，实际含 '" + item + "'"
                    );
                }

                items.add(String.valueOf(item));
            }

            return List.copyOf(items);
        }

        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }

        throw new InvalidDefinition(
                "options." + key + " 只接受字符串/数字/布尔或它们的列表，实际为 '" + value + "'"
        );
    }

    /**
     * 读取 {@code rarity}：{@code common|uncommon|rare|epic}，大小写不敏感。
     *
     * <p>缺省时按最大等级推导（{@link EnchantRarity#fromMaxLevel(int)}），
     * 而不是统一取 common——存量 yml 全都没有该字段，统一取 common 会让按稀有度配色变成单色。
     *
     * <p>写错则整条拒绝，与 bool/number 的策略一致：静默回退会让配色莫名跳档，
     * 而配置里明明写着一个值，服主不会想到去查解析器。
     */
    private EnchantRarity rarity(final Map<?, ?> map, final int maxLevel) {
        final Object value = map.get("rarity");

        if (null == value) {
            return EnchantRarity.fromMaxLevel(maxLevel);
        }

        final EnchantRarity parsed = EnchantRarity.byId(String.valueOf(value));

        if (null == parsed) {
            throw new InvalidDefinition(
                    "rarity 只能是 common/uncommon/rare/epic 之一，实际为 '" + value + "'"
            );
        }

        return parsed;
    }

    /**
     * 解析 {@code trigger}：先当内置短名，再当事件类名。
     *
     * <p>顺序很重要。内置短名有特化语义（DEFEND 与 ATTACK 是同一个事件的两个视角），
     * 必须先匹配；否则用户写 {@code EntityDamageByEntityEvent} 就会绕过角色过滤。
     *
     * <p>写事件类名时若该事件正是某个短名的底层事件，会被归一成那个短名——
     * 这样 {@code BlockBreakEvent} 与 {@code MINE} 完全等价，不会出现「同一个事件
     * 被注解监听和事件桥各触发一次」。
     */
    private TriggerBinding trigger(final Map<?, ?> map) {
        final Object node = map.get("trigger");
        final String raw = null == node ? "ATTACK" : String.valueOf(node).trim();

        final TriggerType builtin = TriggerType.parse(raw);

        if (null != builtin) {
            // 内置短名不受 events.blocked 影响：那是一张「别订阅这些高频事件」的表，
            // 而短名的监听器是框架自己控制的固定集合。
            return TriggerBinding.builtin(builtin);
        }

        if (this.blocked(raw)) {
            throw new InvalidDefinition(
                    "事件 '" + raw + "' 默认禁止注册（见 config.yml 的 events.blocked，"
                            + "确需使用请把该项从列表移除）"
            );
        }

        final Class<? extends Event> eventClass = EventClassResolver.resolve(raw);

        if (null == eventClass) {
            throw new InvalidDefinition(
                    "未知的 trigger '" + raw + "'：既不是内置短名，也找不到对应的 Bukkit 事件"
            );
        }

        return TriggerBinding.of(eventClass);
    }

    /** 简名与全限定名都参与比较，避免服主两种写法各写一遍。 */
    private boolean blocked(final String raw) {
        final String name = raw.trim();
        final int dot = name.lastIndexOf('.');
        final String simple = dot < 0 ? name : name.substring(dot + 1);

        for (final String entry : this.blockedEvents) {
            if (entry.equalsIgnoreCase(name) || entry.equalsIgnoreCase(simple)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 读取 {@code slots}：触发时去哪些装备槽找附魔物品。
     *
     * <p>缺省为只有主手——与引入本字段前的行为完全一致。护甲类附魔必须显式写出槽位，
     * 否则永远读不到（框架不会替你猜「这个附魔应该挂在护腿上」）。
     *
     * <p>{@code MAIN_HAND} 是 {@code HAND} 的可读别名，两个都接受。
     */
    private Set<EquipmentSlot> slots(final Map<?, ?> map) {
        final Set<EquipmentSlot> parsed = new LinkedHashSet<>();

        for (final Object node : this.rawList(map, "slots")) {
            if (!(node instanceof String raw)) {
                throw new InvalidDefinition("slots 只接受槽位名字符串，实际为 '" + node + "'");
            }

            final EquipmentSlot slot = parseSlot(raw);

            if (null == slot) {
                throw new InvalidDefinition("未知的装备槽 '" + raw + "'（可用：MAIN_HAND/off_hand/head/chest/legs/feet）");
            }

            parsed.add(slot);
        }

        if (parsed.isEmpty()) {
            parsed.add(EquipmentSlot.HAND);
        }

        return Collections.unmodifiableSet(parsed);
    }

    private static EquipmentSlot parseSlot(final String raw) {
        final String normalized = raw.trim().toUpperCase(Locale.ROOT);

        if ("MAIN_HAND".equals(normalized)) {
            return EquipmentSlot.HAND;
        }

        for (final EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.name().equals(normalized)) {
                return slot;
            }
        }

        return null;
    }

    private String string(final Map<?, ?> map, final String key, final String fallback) {
        final Object value = map.get(key);

        return null == value ? fallback : String.valueOf(value);
    }

    private boolean bool(final Map<?, ?> map, final String key, final boolean fallback) {
        final Object value = map.get(key);

        if (null == value) {
            return fallback;
        }

        if (value instanceof Boolean flag) {
            return flag;
        }

        throw new InvalidDefinition(key + " 必须是 true 或 false（不要加引号），实际为 '" + value + "'");
    }

    private double number(final Map<?, ?> map, final String key, final double fallback) {
        final Object value = map.get(key);

        if (null == value) {
            return fallback;
        }

        if (value instanceof Number numeric) {
            return numeric.doubleValue();
        }

        throw new InvalidDefinition(key + " 必须是数字，实际为 '" + value + "'");
    }

    private List<?> rawList(final Map<?, ?> map, final String key) {
        final Object value = map.get(key);

        if (null == value) {
            return List.of();
        }

        if (value instanceof List<?> list) {
            return list;
        }

        if (value instanceof String single) {
            return List.of(single);
        }

        // 写错成数字或布尔时不能当成空列表——空列表意味着「所有物品都能附着」。
        throw new InvalidDefinition(key + " 必须是字符串列表或单个字符串，实际为 '" + value + "'");
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');

        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * 单文件解析结果，定义与失败原因互斥。
     *
     * @param definition 解析成功时的定义，失败时为 {@code null}
     * @param error      失败原因，成功时为 {@code null}
     */
    record ParseOutcome(EnchantDefinition definition, String error) {

        boolean success() {
            return null != this.definition;
        }
    }

    /** 定义内容不可用；由 {@link #parseOutcome(Path)} 统一转成一条告警并跳过该文件。 */
    private static final class InvalidDefinition extends RuntimeException {

        private InvalidDefinition(final String message) {
            super(message, null, false, false);
        }
    }
}
