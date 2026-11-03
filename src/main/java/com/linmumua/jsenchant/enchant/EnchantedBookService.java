package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.JsEnchantPlugin;
import com.linmumua.jsenchant.config.ConfigService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 附魔书：把「哪个附魔、几级」写进 {@code ENCHANTED_BOOK}。
 *
 * <p><b>双写</b>：数据包生效时同一本书上写两份数据——
 * <ul>
 *   <li>原版 {@code stored_enchantments}（用 {@link VanillaEnchantBridge#enchantment(String)} 解析）；
 *       原版铁砧、客户端 tooltip、光效都认它，所以这种书交给原版铁砧处理，
 *       见 {@link BookAnvilListener}；</li>
 *   <li>本插件的 {@code book_enchant} + {@code book_level}，旧书兼容与自研铁砧靠它。</li>
 * </ul>
 * 数据包没生效时只写 PDC 那一份，与改造前完全一致——旧书继续可用，新书退化成旧书。
 *
 * <p><b>数据与装备严格隔离</b>：装备的等级键是 {@code e_<id>}，书用的是
 * {@code book_enchant}（附魔 id）+ {@code book_level}（整数等级）两个键。
 * 两者 namespace 相同（已发布的 {@code jsenchants}），键名完全不同，所以：
 * <ul>
 *   <li>手拿一本附魔书不会触发任何装备效果——{@link EnchantApplier#getLevels} 还额外显式
 *       排除 {@link Material#ENCHANTED_BOOK}，因为书上的原版组件是 {@code stored_enchantments}，
 *       被当成装备附魔读出来就会凭空触发效果；</li>
 *   <li>书上的数据不会被 {@link EnchantApplier#getLevels} 当成附魔读出来，
 *       反之装备也不会被 {@link #read} 当成书。</li>
 * </ul>
 *
 * <p><b>识别只靠 PDC，不靠 Lore 或名字</b>：Lore 与展示名是给人看的，可以被重命名、
 * 被别的插件改写；用它判断「这是不是本插件的书」会立刻变成可伪造的入口。
 * 因此 {@link #read} 只读 PDC，并且要求 id 非空、等级是 1 以上的整数；
 * 「等级是否超过当前 {@code max-level}」「附魔是否还存在」由调用方拿当前定义再判
 * （见 {@link BookAnvilListener}），因为重载之后定义可能已经变了。
 *
 * <p><b>展示</b>：名称沿用 {@code lore.format} 的文本形态，颜色按附魔稀有度取
 * （{@link ConfigService.LorePalette}），与装备上的附魔行是同一套 MiniMessage 写法；适用材质用客户端可翻译的
 * {@link Component#translatable(net.kyori.adventure.translation.Translatable)}，
 * 不在代码里硬编码任何物品译名。
 */
public final class EnchantedBookService {

    /** 书的附魔 id 键。与装备的 {@code e_<id>} 完全分离，不可复用。 */
    static final String BOOK_ID_KEY = "book_enchant";

    /** 书的等级键。必须是整数，1 以上。 */
    static final String BOOK_LEVEL_KEY = "book_level";

    private static final NamespacedKey ID_KEY =
            new NamespacedKey(EnchantApplier.DATA_NAMESPACE, BOOK_ID_KEY);
    private static final NamespacedKey LEVEL_KEY =
            new NamespacedKey(EnchantApplier.DATA_NAMESPACE, BOOK_LEVEL_KEY);

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /*
     * 书上的两行说明。项目里「发给玩家的文本」集中在 messages.yml，但那是另一份负责人的文件，
     * 而且书 Lore 是随物品走的静态展示、不需要热重载，所以这两句钉在这里。
     * 物品名本身不走这里：适用材质一律用客户端译名。
     */
    private static final String APPLICABLE_LABEL = "适用物品：";
    private static final String ANY_ITEM_LABEL = "任意物品";
    private static final String LEVEL_LABEL = "等级：";
    private static final String SEPARATOR = "、";

    private final JsEnchantPlugin plugin;

    /**
     * @param plugin 用来每次现取配置快照——调色板**不缓存**：{@code /jse reload} 后
     *               装备 Lore 行会立刻换色，书若缓存旧调色板就会停在旧颜色上。
     */
    public EnchantedBookService(final JsEnchantPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 造一本附魔书。战利品注入（{@code LootBookInjector}）与后续任何「发书」入口都用这个。
     *
     * <p>数据包生效时同一本书上写两份数据：原版 {@code stored_enchantments}（原版铁砧与客户端认它）
     * 与插件自己的 {@code book_enchant}/{@code book_level}（旧书兼容与自研铁砧认它）。
     * 数据包没生效时只写后者，书退化成旧格式，仍然能用。
     *
     * @param definition 当前生效的附魔定义
     * @param level      要写进书的等级，必须落在 {@code 1..max-level}
     * @return 书；参数非法或写入失败时返回 {@code null}。返回 {@code null} 表示「这次不要发书」，
     *         调用方不应该用一件空书或普通书顶替
     */
    public ItemStack create(final EnchantDefinition definition, final int level) {
        if (null == definition || 1 > level || level > definition.maxLevel()) {
            // 严格拒绝而不是夹取：越界等级来自调用方的 bug 或已被改过的数据，
            // 悄悄改成合法值只会让「为什么这书是 3 级」永远查不出来。
            return null;
        }

        final ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        final ItemMeta meta = book.getItemMeta();

        if (null == meta || !write(meta.getPersistentDataContainer(), definition.id(), level)) {
            return null;
        }

        final Enchantment vanilla = vanillaEnchant(definition.id());

        if (null != vanilla) {
            // 必须显式用 EnchantmentStorageMeta：书的附魔落在 STORED_ENCHANTMENTS 组件里，
            // 与装备的 ENCHANTMENTS 是两个不同的原版组件。靠 addEnchant 的自动路由是碰运气——
            // 万一落到 enchantments，"这是组件书"的判定（铁砧分流）和客户端展示都会一起错。
            if (meta instanceof EnchantmentStorageMeta storage) {
                storage.addStoredEnchant(vanilla, level, true);
            }
        }

        // 快照只取一次：模板与调色板必须来自同一份配置，否则 reload 恰好夹在两次读取之间
        // 会出现「旧模板 + 新颜色」这种半新半旧的展示。
        final ConfigService.Snapshot snapshot = this.snapshot();
        final Presentation presentation = presentation(definition, level, snapshot, snapshot.lorePalette());

        meta.displayName(presentation.name());
        meta.lore(presentation.lore().isEmpty() ? null : presentation.lore());

        return book.setItemMeta(meta) ? book : null;
    }

    /**
     * 读一本书上的附魔数据。
     *
     * <p><b>双读</b>：PDC 的 {@code book_level} 与原版 {@code stored_enchantments} 取较大值。
     * 两边在正常流程里始终同值；分开读是为了不放过「等级只落在某一侧」的书
     * （别的插件复制出来的、或只写了组件的），否则上限校验会漏判
     * 「书 3 级、定义已经降到 2 级」这种真实现象。
     *
     * @return 本插件的书且数据完整时返回 {@link BookData}；不是本插件的书、材质不对或数据不完整时返回
     *         {@code null}。上限与定义是否仍生效由调用方按当前快照判
     */
    public BookData read(final ItemStack item) {
        if (null == item || Material.ENCHANTED_BOOK != item.getType()) {
            return null;
        }

        final ItemMeta meta = item.getItemMeta();

        if (null == meta) {
            return null;
        }

        final BookData fromData = read(meta.getPersistentDataContainer());

        return null == fromData ? null : withStored(fromData, this.storedLevel(meta, fromData.id()));
    }

    /**
     * 把原版组件上的等级合进书数据（取较大值，纯函数）。
     */
    static BookData withStored(final BookData data, final int storedLevel) {
        if (null == data || storedLevel <= data.level()) {
            return data;
        }

        return new BookData(data.id(), storedLevel);
    }

    /** 写入书的两个键。等级只校验「正整数」，上限由 {@link #create} 与铁砧按当前定义判。 */
    static boolean write(final PersistentDataContainer container, final String id, final int level) {
        if (null == container || null == id || id.isBlank() || 1 > level) {
            return false;
        }

        container.set(ID_KEY, PersistentDataType.STRING, id);
        container.set(LEVEL_KEY, PersistentDataType.INTEGER, level);

        return true;
    }

    /** 读附魔数据；id 与等级必须都存在且合法。 */
    static BookData read(final PersistentDataContainer container) {
        if (null == container) {
            return null;
        }

        final String id = container.get(ID_KEY, PersistentDataType.STRING);
        final Integer level = container.get(LEVEL_KEY, PersistentDataType.INTEGER);

        if (null == id || id.isBlank() || null == level || 1 > level) {
            return null;
        }

        return new BookData(id, level);
    }

    /**
     * 这件物品数据上是否带有「本插件的书」的痕迹（哪怕数据是坏的）。
     *
     * <p>铁砧需要区分两件事：第二格根本不是我们的书（一根手指都不碰，原版附魔书与普通铁砧流程照旧），
     * 和「是我们的书但数据坏了」（必须拒绝产出结果）。{@link #read} 对两者都返回 {@code null}，
     * 所以另给一个只看键是否存在的判定。
     */
    static boolean marked(final PersistentDataContainer container) {
        if (null == container) {
            return false;
        }

        return container.has(ID_KEY) || container.has(LEVEL_KEY);
    }

    /**
     * 书上要显示的展示名与 Lore。
     *
     * <p>名称沿用 {@code lore.format} 的文本形态，颜色改由 {@link ConfigService.LorePalette} 按附魔稀有度给
     * ——装备上的附魔行走同一套模板，书和装备才不会出现两套写法。
     * Lore 两行分别是适用材质与等级区间。
     * {@code lore.enabled=false} 时只给名称——那是服主对「接管物品 Lore」的全局开关，
     * 附魔书不该绕过它。
     *
     * <p>做成纯函数返回组件、而不是直接写元数据：构造 {@code ItemStack} 需要注册好的服务端，
     * 而这里要证明的规则（模板、行数、不加英文名）不依赖服务端。
     */
    static Presentation presentation(
            final EnchantDefinition definition,
            final int level,
            final ConfigService.Snapshot snapshot,
            final ConfigService.LorePalette palette
    ) {
        final String template = null == palette ? snapshot.loreFormat() : palette.templateFor(definition.rarity());
        final Component name = MINI_MESSAGE.deserialize(
                EnchantApplier.formatLine(template, definition, level)
        );

        if (!snapshot.loreEnabled()) {
            return new Presentation(name, List.of());
        }

        return new Presentation(name, List.of(
                Component.text(APPLICABLE_LABEL, NamedTextColor.DARK_GRAY).append(applicable(definition)),
                Component.text(LEVEL_LABEL, NamedTextColor.DARK_GRAY)
                        .append(Component.text(level + " / " + definition.maxLevel(), NamedTextColor.GRAY))
        ));
    }

    /** 适用材质：用客户端译名，按材质名排序保证多次生成的 Lore 完全一致。 */
    private static Component applicable(final EnchantDefinition definition) {
        if (definition.items().isEmpty()) {
            // 定义里 items 留空表示不限制物品，与 EnchantDefinition#supports 的语义一致。
            return Component.text(ANY_ITEM_LABEL, NamedTextColor.GRAY);
        }

        final List<Material> materials = new ArrayList<>(definition.items());

        materials.sort(Comparator.comparing(Material::name));

        final List<Component> names = new ArrayList<>(materials.size());

        for (final Material material : materials) {
            names.add(Component.translatable(material).colorIfAbsent(NamedTextColor.GRAY));
        }

        return Component.join(
                JoinConfiguration.separator(Component.text(SEPARATOR, NamedTextColor.DARK_GRAY)),
                names
        );
    }

    /**
     * 当前配置快照。
     *
     * <p>每次现取而不是构造时缓存：{@code /jse reload} 之后 Lore 模板要立刻跟着变，
     * 而本服务实例在整个启用期只有一个。
     */
    private ConfigService.Snapshot snapshot() {
        return this.plugin.config().current();
    }

    /**
     * 把本插件的 id 解析成原版附魔。
     *
     * <p>{@link VanillaEnchantBridge#enchantment(String)} 自己就是「能不能写原版组件」的唯一判断入口：
     * 注册表不可用、数据包未加载、该 id 已下线都返回 {@code null}，这里据此只写 PDC。
     *
     * @return 不能写原版组件时返回 {@code null}（书退化成旧格式，仍然能用）
     */
    private static Enchantment vanillaEnchant(final String id) {
        return VanillaEnchantBridge.enchantment(id);
    }

    /** 书上原版组件记录的等级；没有则为 0。 */
    private int storedLevel(final ItemMeta meta, final String id) {
        final Enchantment vanilla = vanillaEnchant(id);

        if (null == vanilla) {
            return 0;
        }

        // 与写入侧对称：只认 STORED_ENCHANTMENTS，不从 enchantments 里取——
        // 后者在书上不该有值，读到就说明是别的插件写错了地方，不该被当成本插件书的等级。
        return meta instanceof EnchantmentStorageMeta storage ? storage.getStoredEnchantLevel(vanilla) : 0;
    }

    /**
     * 一本书的内容。
     *
     * @param id    附魔 id（等于 {@code enchants/*.yml} 里的 {@code id}）
     * @param level 书上写的等级，{@code >= 1}
     */
    public record BookData(String id, int level) {
    }

    /**
     * 书上要显示的展示名与 Lore。
     *
     * @param name 展示名，来自 {@code lore.format}
     * @param lore 说明行；{@code lore.enabled=false} 时为空列表（表示不写 Lore）
     */
    record Presentation(Component name, List<Component> lore) {
    }
}
