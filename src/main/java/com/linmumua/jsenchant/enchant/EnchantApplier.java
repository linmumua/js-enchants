package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.config.ConfigService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 附魔的读写层：等级存在 PDC（并在数据包生效时双写原版附魔组件），展示存在 Lore。
 *
 * <p>为什么 PDC 是权威数据：{@code org.bukkit.Registry} 曾经是只读接口，插件无法向
 * {@code ENCHANTMENT} 注册表添加条目，所以自定义附魔一直由 PDC 承载。现在改由数据包把
 * 附魔注册进原版注册表，于是<b>额外</b>写一份原版组件（附魔台、原版铁砧、光效都能识别它），
 * 但 PDC 那份<b>既不迁移也不删除</b>：
 *
 * <ul>
 *   <li><b>写</b>：{@link #apply} 同时写 PDC 与原版组件，且仅当数据包已生效
 *       （{@link VanillaEnchantBridge#enchantment(String)} 能解析出该 id）才写组件；数据包没加载时
 *       退化为纯 PDC，与改造前完全一致，物品不会坏。</li>
 *   <li><b>读</b>：{@link #getLevel} / {@link #getLevels} 合并两侧取较大值，是唯一读取入口。
 *       派发、悬停、铁砧都不得各自实现。</li>
 *   <li><b>删</b>：{@link #strip} 两侧都删，返回值语义仍是「原先是否存在」。</li>
 * </ul>
 *
 * <p><b>附魔书不是装备</b>：{@link Material#ENCHANTED_BOOK} 上的原版组件落在
 * {@code stored_enchantments}，而 {@code ItemMeta} 读附魔时对书有别名倾向——手持一本
 * 带组件的附魔书若被当装备读出来，就会凭空触发装备效果。因此所有读取入口都显式排除附魔书。
 *
 * <p><b>HIDE_ENCHANTS 按附魔构成重算</b>：原版 tooltip 会把附魔名统一染灰/红，稀有度配色只能
 * 由我们的 Lore 行提供；但 {@code HIDE_ENCHANTS} 是<b>全物品</b>标记，打上后连锋利、耐久这些
 * 原版附魔行也会一起消失。而附魔台产出的恰恰是「原版附魔 + 本插件附魔」的混合物品。所以：
 * 物品上存在<b>非本插件</b>的原版附魔就<b>不打</b>该标记（宁可重复一行也不丢原版信息），
 * 只有全是本插件附魔时才打标记消除重复。这个判定在每次 {@link #apply} / {@link #redrawLore}
 * 时重算，因此「先纯插件附魔、后来被加上锋利」时标记会被摘掉，不会出现永久隐藏。
 *
 * <p>Lore 管理方式：本框架渲染的行追加在末尾，并带一个不可见标记（写在
 * {@link Component#insertion()} 上，不改变任何显示文本）。重绘时只清理
 * <b>带标记的行</b>，再按当前全部附魔重新追加——所以第三方插件追加到同一物品上的
 * Lore 行不会被误删，反复附着也不会堆叠重复行。
 *
 * <p><b>模板的文本形态是可以改的，但要付一笔登记费</b>：旧行的识别方式是「纯文本完全相等」，
 * 所以换个写法（例如等级从 {@code Lv.3} 改成阶位 {@code III}）之后，用旧写法渲染出来的存量行
 * 就再也对不上当前模板。为此有 {@code lore.legacy-formats}：那里登记<b>过去用过</b>的模板，
 * 它们<b>只</b>参与「哪些行算我自己的」这一判断，从不参与渲染。漏登记的后果是旧行永久残留
 * 且 {@code lore_lines} 配额也删不掉——所以改模板形态的那一次提交必须同时补上登记。
 *
 * <p>等级可以用 {@code {level}}（阿拉伯数字）或 {@code {tier}}（罗马数字阶位）表达，
 * 由模板自己选；两者可以同时出现，也可以都不出现（只显示附魔名）。
 *
 * <p>数据格式约定：PDC 的 namespace 固定为 {@link #DATA_NAMESPACE}（历史上等于
 * {@code plugin.yml} 的 {@code name} 小写，即 {@code jsenchants}），键名见
 * {@link #LORE_LINES_KEY} 与 {@link #LEVEL_PREFIX}。
 * 这些属于已发布物品的数据格式，改 group/artifactId/包名、甚至改插件名都不能动它们，
 * 否则旧物品上的附魔与 Lore 会全部失联。因此这里不再从 {@code plugin.getName()} 推导，
 * 而是钉成常量。
 */
public final class EnchantApplier {

    /**
     * 物品数据的 PDC namespace，属于已发布格式，不可修改。
     */
    static final String DATA_NAMESPACE = "jsenchants";

    /**
     * Lore 行数的 PDC 键名。旧物品上已存在此键，不可改名。
     *
     * <p>新版本不再依赖它定位自有行（改用标记），但仍持续写入，
     * 一方面给旧版本/旧物品提供兼容线索，另一方面限制旧物品遗留行的清理数量。
     */
    static final String LORE_LINES_KEY = "lore_lines";

    /** 等级键前缀，实际键为 {@code e_<id>}。属于对外数据格式，不可改名。 */
    static final String LEVEL_PREFIX = "e_";

    /**
     * 自有 Lore 行的标记，写在 {@link Component#insertion()} 上。
     * 玩家在物品 Lore 里看不到它，但它让我们能精确地只删自己的行。
     */
    private static final String LORE_MARKER = "jsenchant-lore";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final EnchantRegistry registry;
    private final NamespacedKey loreLinesKey;
    private final String namespace;

    private final String loreFormat;
    private final boolean loreEnabled;
    private final ConfigService.LorePalette palette;

    /** {@code lore.legacy-formats}：只用于识别旧行，绝不参与渲染。永不为 {@code null}。 */
    private final List<String> legacyFormats;

    /**
     * @param loreFormat {@code lore.format}，既是配色调色板缺项时的兜底模板，也是识别旧 Lore 行的依据
     * @param palette    按稀有度取色的模板表；{@code null} 时退回 {@code loreFormat}
     */
    public EnchantApplier(
            final Plugin plugin,
            final EnchantRegistry registry,
            final String loreFormat,
            final boolean loreEnabled,
            final ConfigService.LorePalette palette
    ) {
        // legacy-formats 随调色板一起传进来：本类的唯一生产构造点在 JsEnchantPlugin，
        // 而那一个调用点透传的配置对象只有 palette（{@code lore.format} 与开关是裸字符串/布尔）。
        // 与其为一条清理线索去改调用点，不如让 palette 顺带携带它。
        this(plugin, registry, loreFormat, loreEnabled, palette, null == palette ? null : palette.legacyFormats());
    }

    /**
     * @param legacyFormats {@code lore.legacy-formats}：{@code null} 按空列表处理
     */
    public EnchantApplier(
            final Plugin plugin,
            final EnchantRegistry registry,
            final String loreFormat,
            final boolean loreEnabled,
            final ConfigService.LorePalette palette,
            final List<String> legacyFormats
    ) {
        this.registry = registry;
        this.namespace = DATA_NAMESPACE;
        this.loreLinesKey = new NamespacedKey(DATA_NAMESPACE, LORE_LINES_KEY);
        this.loreFormat = loreFormat;
        this.loreEnabled = loreEnabled;
        this.palette = palette;
        this.legacyFormats = null == legacyFormats ? List.of() : List.copyOf(legacyFormats);

        // 插件改名不再影响数据 namespace，但要留下痕迹，否则排查旧物品失联时没有任何线索。
        if (null != plugin && !DATA_NAMESPACE.equals(plugin.getName().toLowerCase(Locale.ROOT))) {
            plugin.getLogger().warning(
                    "物品数据 namespace 固定为 '" + DATA_NAMESPACE + "'（当前插件名为 '" + plugin.getName()
                            + "'）。这是已发布格式，不可修改，否则旧物品上的附魔会失联。"
            );
        }
    }

    /**
     * 给物品附加附魔。等级会被夹到定义允许的范围。
     *
     * <p><b>双写</b>：PDC 是权威数据，总是写；原版组件只在数据包已生效且能解析该 id 时才写，
     * 因此数据包没加载时行为与改造前完全一致。
     *
     * @return 实际生效的等级，物品不可用或类型不支持时返回 0
     */
    public int apply(final ItemStack item, final EnchantDefinition definition, final int level) {
        if (null == item || item.getType().isAir() || !definition.supports(item.getType())) {
            return 0;
        }

        final ItemMeta meta = item.getItemMeta();

        if (null == meta) {
            return 0;
        }

        final int clamped = definition.clampLevel(level);

        meta.getPersistentDataContainer().set(
                this.levelKey(definition.id()),
                PersistentDataType.INTEGER,
                clamped
        );

        final Enchantment vanilla = vanillaEnchant(item.getType(), definition.id());

        if (null != vanilla) {
            // ignoreLevelRestriction=true：等级上限由我们自己的定义（数据包里的 max_level）说了算，
            // 不让 Bukkit 按原版默认上限二次拦截。
            meta.addEnchant(vanilla, clamped, true);
        }

        item.setItemMeta(meta);
        this.redrawLore(item);

        return clamped;
    }

    /**
     * 读取物品上某个附魔的等级，没有则返回 0。
     *
     * <p><b>双读</b>：PDC 与原版组件取较大值。旧物品只有 PDC、新物品两侧都有、
     * 原版铁砧贴上去的物品只有组件——三种情况都能读出来。
     */
    public int getLevel(final ItemStack item, final String id) {
        if (null == item || item.getType().isAir()) {
            return 0;
        }

        final ItemMeta meta = item.getItemMeta();

        if (null == meta) {
            return 0;
        }

        final Integer stored = meta.getPersistentDataContainer()
                .get(this.levelKey(id), PersistentDataType.INTEGER);

        final int fromData = null == stored ? 0 : stored;

        if (!readsAsEquipment(item.getType())) {
            // 书上的原版组件是 stored_enchantments，不是装备附魔：只认 PDC 那一份。
            return fromData;
        }

        return Math.max(fromData, ownVanillaLevel(meta, id));
    }

    /**
     * 读取物品上所有本框架附魔的等级。
     *
     * <p>派发与悬停预览的热路径都走这里，因此不整表 clone、不反射：PDC 侧本来就只遍历自有键，
     * 原版侧只在物品确实带附魔（{@code hasEnchants()}）时遍历那几条。
     */
    public Map<String, Integer> getLevels(final ItemStack item) {
        final Map<String, Integer> levels = new LinkedHashMap<>();

        if (null == item || item.getType().isAir()) {
            return levels;
        }

        if (!readsAsEquipment(item.getType())) {
            // 显式排除附魔书：书上只有 book_* 键，而原版组件读出来的会是 stored_enchantments。
            // 手拿一本带组件的书若被当成装备，就会凭空触发装备效果。
            return levels;
        }

        final ItemMeta meta = item.getItemMeta();

        if (null == meta) {
            return levels;
        }

        final PersistentDataContainer container = meta.getPersistentDataContainer();

        for (final NamespacedKey key : container.getKeys()) {
            if (!this.namespace.equals(key.getNamespace())) {
                continue;
            }

            final String value = key.getKey();

            if (!value.startsWith(LEVEL_PREFIX)) {
                continue;
            }

            final Integer level = container.get(key, PersistentDataType.INTEGER);

            if (null != level) {
                levels.put(value.substring(LEVEL_PREFIX.length()), level);
            }
        }

        if (meta.hasEnchants()) {
            // 合并原版侧：原版铁砧用数据包附魔书贴上去的物品只有组件、没有 PDC 键。
            for (final Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                final NamespacedKey key = entry.getKey().getKey();

                if (!this.namespace.equals(key.getNamespace())) {
                    continue;
                }

                levels.merge(key.getKey(), entry.getValue(), Math::max);
            }
        }

        return levels;
    }

    /**
     * 移除物品上的某个附魔。
     *
     * <p>两侧都删，但返回值语义保持「移除前是否存在」：只有组件、没有 PDC 键的物品
     * （原版附魔书贴上去的那种）也必须能删掉并返回 {@code true}。
     *
     * @return 移除前是否存在
     */
    public boolean strip(final ItemStack item, final String id) {
        if (null == item || item.getType().isAir()) {
            return false;
        }

        final ItemMeta meta = item.getItemMeta();

        if (null == meta) {
            return false;
        }

        final NamespacedKey key = this.levelKey(id);
        boolean present = meta.getPersistentDataContainer().has(key, PersistentDataType.INTEGER);

        if (present) {
            meta.getPersistentDataContainer().remove(key);
        }

        final Enchantment vanilla = vanillaEnchant(item.getType(), id);

        if (null != vanilla && meta.hasEnchant(vanilla)) {
            meta.removeEnchant(vanilla);
            present = true;
        }

        if (!present) {
            return false;
        }

        item.setItemMeta(meta);
        this.redrawLore(item);

        return true;
    }

    /**
     * 重绘 Lore：清理本框架自己的行（含旧版本遗留的无标记行），
     * 再按当前全部附魔重新追加到末尾。第三方追加的行原样保留。
     *
     * <p>顺带按物品当前的附魔构成重算 {@code HIDE_ENCHANTS}（见类注释）：这一步必须在每次
     * 重绘时重做，否则「先纯插件附魔、后来被加上锋利」的物品会永久看不见原版行。
     */
    public void redrawLore(final ItemStack item) {
        if (null == item || item.getType().isAir()) {
            return;
        }

        final ItemMeta meta = item.getItemMeta();

        if (null == meta) {
            return;
        }

        boolean touched = false;

        final boolean shouldHide = hideEnchants(this.loreEnabled, vanillaKeys(meta, item.getType()));

        switch (flagAction(meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS), shouldHide)) {
            case HIDE -> {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                touched = true;
            }
            case SHOW -> {
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
                touched = true;
            }
            case NONE -> {
                // 已经是想要的状态：不写元数据，避免无谓的物品改动。
            }
        }

        if (this.loreEnabled) {
            final Integer previousLines = meta.getPersistentDataContainer()
                    .get(this.loreLinesKey, PersistentDataType.INTEGER);

            final List<Component> fresh = new ArrayList<>();

            for (final Map.Entry<String, Integer> entry : this.getLevels(item).entrySet()) {
                final EnchantDefinition definition = this.registry.get(entry.getKey());

                if (null == definition) {
                    continue;
                }

                fresh.add(renderLine(this.template(definition), definition, entry.getValue()));
            }

            final List<Component> merged = rebuildLore(
                    meta.lore(),
                    previousLines,
                    fresh,
                    this.legacyOwnLineMatcher()
            );

            meta.lore(merged.isEmpty() ? null : merged);
            meta.getPersistentDataContainer().set(this.loreLinesKey, PersistentDataType.INTEGER, fresh.size());
            touched = true;
        }

        if (touched) {
            item.setItemMeta(meta);
        }
    }

    /**
     * 这个附魔该用哪份模板渲染。
     *
     * <p>配色只换颜色、不换文本形态以外的东西都行——但注意：文本形态一旦改过，
     * 改之前用过的模板必须登记进 {@code lore.legacy-formats}，否则存量旧行认不出来。
     * 当前实现正是靠这条把等级从 {@code Lv.3} 迁到阶位 {@code III}。
     *
     * <p>注意 MiniMessage 语义：定义里的 {@code display} 自带颜色标签时会覆盖模板颜色，
     * 那种情况下能看出稀有度的只有等级那一段。
     */
    private String template(final EnchantDefinition definition) {
        return null == this.palette ? this.loreFormat : this.palette.templateFor(definition.rarity());
    }

    /**
     * 按物品的附魔构成决定要不要打 {@code HIDE_ENCHANTS}（纯函数，便于无服务端回归）。
     *
     * <p>{@code HIDE_ENCHANTS} 是全物品标记：一旦打上，物品上<b>其它</b>原版附魔行也会消失。
     * 所以只有「一条非本插件的原版附魔都没有」时才敢隐藏原版行；
     * 混合物品宁可让我们的彩色行与原版灰行并存，也不丢原版信息。
     *
     * <p>{@code lore.enabled=false} 时我们不再写彩色行，隐藏原版行就等于让玩家什么都看不到，
     * 因此此时永不打标记。
     *
     * @param present 物品上当前存在的原版附魔（key 的 namespace 用来区分归属）
     */
    static boolean hideEnchants(final boolean loreEnabled, final Collection<NamespacedKey> present) {
        if (!loreEnabled) {
            return false;
        }

        boolean own = false;

        for (final NamespacedKey key : present) {
            if (null == key) {
                continue;
            }

            if (!DATA_NAMESPACE.equals(key.getNamespace())) {
                // 有别的原版附魔：隐藏原版行会让玩家看不到锋利/耐久，直接放弃隐藏。
                return false;
            }

            own = true;
        }

        return own;
    }

    /**
     * 该对标记位做什么。
     *
     * <p>单独立出来是为了让「后来被加上原版附魔时标记必须被摘掉」这条回归能被直接断言：
     * 只看「该不该隐藏」不够，摘除那一半同样会坏。
     */
    static FlagAction flagAction(final boolean currentlyHidden, final boolean shouldHide) {
        if (currentlyHidden == shouldHide) {
            return FlagAction.NONE;
        }

        return shouldHide ? FlagAction.HIDE : FlagAction.SHOW;
    }

    /** 物品附魔 key 里的 namespace 是不是本插件。 */
    static boolean isOwnEnchant(final NamespacedKey key) {
        return null != key && DATA_NAMESPACE.equals(key.getNamespace());
    }

    /**
     * 这件材质上的原版附魔算不算「装备附魔」。
     *
     * <p>附魔书上的原版组件落在 {@code stored_enchantments}，而 {@code ItemMeta} 读附魔时对书有
     * 别名倾向：手拿一本带组件的书若被当成装备读出来，就会凭空触发装备效果。所以读取入口
     * 都先过这一道，书一律不算。
     */
    static boolean readsAsEquipment(final Material material) {
        return Material.ENCHANTED_BOOK != material;
    }

    /** 物品上所有原版附魔的 key；附魔书不算（它的组件在 {@code stored_enchantments}）。 */
    private static List<NamespacedKey> vanillaKeys(final ItemMeta meta, final Material material) {
        if (null == meta || !readsAsEquipment(material) || !meta.hasEnchants()) {
            return List.of();
        }

        final Set<Enchantment> enchantments = meta.getEnchants().keySet();
        final List<NamespacedKey> keys = new ArrayList<>(enchantments.size());

        for (final Enchantment enchantment : enchantments) {
            keys.add(enchantment.getKey());
        }

        return keys;
    }

    /**
     * 把本插件的 id 解析成原版附魔。
     *
     * <p>{@link VanillaEnchantBridge#enchantment(String)} 自己就是「能不能写原版组件」的唯一判断入口：
     * 注册表不可用、数据包未加载、该 id 已下线都会返回 {@code null}，这里据此退化为纯 PDC。
     *
     * @return 不能写原版组件时返回 {@code null}：附魔书（由 {@link EnchantedBookService} 负责
     *         {@code stored_enchantments}）、数据包未生效、或该 id 不在数据包里
     */
    private static Enchantment vanillaEnchant(final Material material, final String id) {
        if (!readsAsEquipment(material)) {
            return null;
        }

        return VanillaEnchantBridge.enchantment(id);
    }

    /** 原版组件里该附魔的等级；没有则为 0。只遍历物品上实际存在的附魔。 */
    private static int ownVanillaLevel(final ItemMeta meta, final String id) {
        if (!meta.hasEnchants()) {
            return 0;
        }

        for (final Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
            final NamespacedKey key = entry.getKey().getKey();

            if (isOwnEnchant(key) && key.getKey().equalsIgnoreCase(id)) {
                return entry.getValue();
            }
        }

        return 0;
    }

    /**
     * 旧物品遗留行的判定器：纯文本能对上「某个已知附魔在某个等级下、由某个已知模板的渲染结果」
     * 才算自有行。
     *
     * <p>候选来自整个注册表而不只是当前附魔，这样即便某个附魔已被 {@link #strip} 掉，
     * 它的旧行也仍然能被认出来并清掉。
     *
     * <p>模板一侧收三套：{@code lore.format}、各稀有度模板、以及 {@code lore.legacy-formats}。
     * 前两套保证「只改了颜色」的旧行仍认得出；第三套是为「文本形态改过」（例如等级从
     * {@code Lv.3} 换成 {@code III}）准备的——漏登记的话，那种旧行就永久残留。
     *
     * <p>清理数量仍受 {@code lore_lines} 历史行数约束（见 {@link #rebuildLore}），
     * 所以登记了更多模板也不会变成「按模板成片删别人的行」。
     */
    private Predicate<Component> legacyOwnLineMatcher() {
        final List<String> templates = new ArrayList<>(6 + this.legacyFormats.size());

        templates.add(this.loreFormat);

        if (null != this.palette) {
            // 四档全收：纯文本会剥掉颜色，四档同形态时这是一份重复集合，形态不同时
            // 只是让清理更彻底——数量上限由 lore_lines 兜着。
            templates.add(this.palette.common());
            templates.add(this.palette.uncommon());
            templates.add(this.palette.rare());
            templates.add(this.palette.epic());
        }

        templates.addAll(this.legacyFormats);

        return legacyOwnLineMatcher(this.registry.all().values(), templates);
    }

    /**
     * 旧行判定器（纯函数，便于无服务端回归）。
     *
     * @param definitions 候选附魔；{@code null} 元素被忽略
     * @param templates   候选模板；空白项被忽略，空模板渲染出的空文本不会成为候选
     *                    （否则任何空 Lore 行都会被当成自有行）
     */
    static Predicate<Component> legacyOwnLineMatcher(
            final Collection<EnchantDefinition> definitions,
            final Collection<String> templates
    ) {
        final Set<String> known = legacyCandidates(definitions, templates);

        return line -> null != line && known.contains(PLAIN_TEXT.serialize(line));
    }

    /**
     * 模板 × 定义 × 等级的全部渲染纯文本。
     *
     * <p>等级上界用 {@link EnchantDefinition#maxLevel()}：更低等级的行由「同一模板 + 更小 level」
     * 覆盖，超出上界的行在物品上不可能由本框架产生。
     */
    static Set<String> legacyCandidates(
            final Collection<EnchantDefinition> definitions,
            final Collection<String> templates
    ) {
        final Set<String> known = new HashSet<>();

        if (null == definitions || null == templates) {
            return known;
        }

        for (final EnchantDefinition definition : definitions) {
            if (null == definition) {
                continue;
            }

            for (int level = 1; level <= definition.maxLevel(); level++) {
                for (final String template : templates) {
                    if (null == template || template.isBlank()) {
                        continue;
                    }

                    final String plainText = renderPlainText(template, definition, level);

                    if (!plainText.isBlank()) {
                        known.add(plainText);
                    }
                }
            }
        }

        return known;
    }

    /**
     * 合并 Lore：只删自有行，第三方行保持原有相对顺序。
     *
     * @param existing     物品当前的 Lore，可为 {@code null}
     * @param previousLines 旧版本记录的自有行数，无记录为 {@code null}
     * @param fresh        本次要追加的自有行（已带标记）
     * @param legacyOwnLine 旧版本无标记行的内容判定器
     * @return 合并后的 Lore，调用方负责决定空表是否落成 {@code null}
     */
    static List<Component> rebuildLore(
            final List<Component> existing,
            final Integer previousLines,
            final List<Component> fresh,
            final Predicate<Component> legacyOwnLine
    ) {
        final List<Component> merged = new ArrayList<>(null == existing ? List.of() : existing);

        int marked = 0;

        for (int index = merged.size() - 1; index >= 0; index--) {
            if (isOwnLoreLine(merged.get(index))) {
                merged.remove(index);
                marked++;
            }
        }

        if (null != previousLines) {
            // 上限用旧版本记录的行数兜底：再像也不会把第三方的行成片清掉。
            int budget = previousLines - marked;

            for (int index = merged.size() - 1; index >= 0 && budget > 0; index--) {
                if (!legacyOwnLine.test(merged.get(index))) {
                    continue;
                }

                merged.remove(index);
                budget--;
            }
        }

        merged.addAll(fresh);

        return merged;
    }

    /** 该行是否为本框架渲染的行。 */
    static boolean isOwnLoreLine(final Component line) {
        return null != line && LORE_MARKER.equals(line.insertion());
    }

    /** 渲染一行 Lore 并打上隐藏标记。 */
    static Component renderLine(
            final String loreFormat,
            final EnchantDefinition definition,
            final int level
    ) {
        return MINI_MESSAGE.deserialize(formatLine(loreFormat, definition, level)).insertion(LORE_MARKER);
    }

    /** 该行的显示文本，用于识别旧版本遗留的无标记行。 */
    private static String renderPlainText(
            final String loreFormat,
            final EnchantDefinition definition,
            final int level
    ) {
        return PLAIN_TEXT.serialize(MINI_MESSAGE.deserialize(formatLine(loreFormat, definition, level)));
    }

    /**
     * 按模板填充一行文本（不解析 MiniMessage、不打标记）。
     *
     * <p>装备 Lore 与附魔书的展示名共用这一份模板，否则「附魔叫什么、几级」会随物品类型
     * 出现两套写法。调用方负责决定是否加隐藏标记。
     *
     * <p>等级有两个占位符：{@code {level}} 是阿拉伯数字（{@code 3}），{@code {tier}} 是阶位
     * （{@code III}）——阶位读起来是「这附魔厉不厉害」，数字读起来是「还能升几级」。
     * 用哪个由模板决定，两个都写也不会冲突。
     */
    static String formatLine(final String loreFormat, final EnchantDefinition definition, final int level) {
        return loreFormat
                .replace("{display}", definition.display())
                .replace("{level}", String.valueOf(level))
                .replace("{tier}", romanTier(level));
    }

    /**
     * 等级 → 阶位罗马数字：{@code 1→I … 10→X}。
     *
     * <p>超出 1..10 一律回落成阿拉伯数字而不是抛异常、也不是拼 {@code XI}：
     * 附魔定义允许 {@code max-level} 很大，模板里写 {@code {tier}} 不该因为某个附魔
     * 开到 12 级就让整条 Lore 渲染失败；而 {@code 0}/{@code -1} 也会走到这里
     * （{@code getLevel} 的缺失值语义是 0），同样必须给出可读结果。
     */
    static String romanTier(final int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }

    /** 等级键名，形如 {@code e_fireball}。 */
    static String levelKeyName(final String id) {
        return LEVEL_PREFIX + id.toLowerCase(Locale.ROOT);
    }

    private NamespacedKey levelKey(final String id) {
        return new NamespacedKey(DATA_NAMESPACE, levelKeyName(id));
    }

    /**
     * {@code HIDE_ENCHANTS} 这位该怎么动。
     *
     * <p>分三个方向而不是一个布尔：摘除和施加同样重要——物品从「纯插件附魔」变成
     * 「混合附魔」时如果不摘，原版行就永久看不见。
     */
    enum FlagAction {

        /** 打上标记，隐藏原版附魔行。 */
        HIDE,

        /** 摘掉标记，让原版附魔行重新显示。 */
        SHOW,

        /** 已经是对的，不用写元数据。 */
        NONE
    }
}
