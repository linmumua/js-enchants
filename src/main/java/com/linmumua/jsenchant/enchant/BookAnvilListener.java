package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.JsEnchantPlugin;
import com.linmumua.jsenchant.config.ConfigService;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 铁砧：把本插件附魔书应用到装备上。
 *
 * <p><b>只碰自己的输入，且只碰旧式书</b>：第二格必须是<b>本插件的书</b>（PDC 里有
 * {@code book_enchant} / {@code book_level} 的痕迹），<b>并且</b>这本书没有带本插件的原版附魔组件。
 * 一旦书上有 {@code stored_enchantments}（数据包生效后由 {@link EnchantedBookService} 双写，
 * namespace 是我们的），就整件放行给原版铁砧——原版自己会做同级 +1、异级取高、
 * {@code max_level} 封顶、按 {@code anvil_cost} 扣等级并消耗 1 本，规则与我们一致，
 * 我们再介入一次只会双重介入。原版附魔书、耐久修理、纯重命名的普通铁砧流程同样一律原样放过。
 *
 * <p><b>合并规则照原版</b>：装备上没有该附魔就直接写书的等级；已有则同级升一级、
 * 异级取较高者，两者都不超过定义的 {@code max-level}。算不出提升、材质不支持、
 * 定义已停用/删除、书数据非法时一律拒绝——拒绝就是「结果槽留空」，
 * 主材料与书一律不修改，玩家不会花掉经验。
 *
 * <p><b>书上的等级双读</b>：{@link EnchantedBookService#read} 会把 PDC 与原版组件取较大值，
 * 因此「书 3 级、定义已经降到 2 级」这类越界书一定会被 {@link #usable} 拒绝，而不会因为只读了
 * PDC 那一侧就漏判。
 *
 * <p><b>消耗由原版完成</b>（26.3 实测，直接读服务端字节码确认，不是「设了 result 就算数」）：
 * <ul>
 *   <li>{@code AnvilView#setRepairCost(n)} 写的是 {@code AnvilMenu.cost} 这个 DataSlot，
 *       玩家取走结果时 {@code AnvilMenu#onTake} 用 {@code giveExperienceLevels(-cost)} 扣等级；</li>
 *   <li>{@code AnvilView#setRepairItemCountCost(1)} 让 {@code onTake} 从第二格 {@code shrink(1)}，
 *       而不是把整摞书一次性清空；</li>
 *   <li>{@code AnvilMenu#mayPickup} 在本版允许 {@code cost >= 0}（也就是可以免费取走），
 *       只额外要求玩家等级不低于费用。因此「费用至少 1 级」是<b>本插件的设计默认</b>
 *       （配置层同样只接受 1..39），不是服务端强制的下限。</li>
 * </ul>
 * 预览阶段（{@link PrepareAnvilEvent}）不扣任何东西，只有玩家真正把结果拖出来才会消耗。
 *
 * <p><b>重载后的有效性</b>：预览事件每次槽位变化都会重算，因此重载立刻影响下一次预览；
 * 但「已经显示着的那个结果」不会自己重算。所以取走时（{@link InventoryClickEvent}）会按当前
 * 定义与配置**再算一遍**：算不出来就取消这次点击并清空结果槽；算得出来但与屏幕上看到的不一致
 * （结果或费用变了）也先取消、把界面对齐，等玩家下一次点击才成交——绝不在玩家没看到的情况下
 * 按新价扣费、或悄悄换掉他即将取走的那件物品。
 *
 * <p><b>线程</b>：两个事件都在玩家所在区域线程派发，这里只读 volatile 的配置快照与附魔表，
 * 只写事件自带的物品栏槽位。
 */
public final class BookAnvilListener implements Listener {

    /** 主材料槽。 */
    private static final int BASE_SLOT = 0;

    /** 附加材料槽（这里就是附魔书）。 */
    private static final int BOOK_SLOT = 1;

    /** 结果槽。 */
    private static final int RESULT_SLOT = 2;

    private final JsEnchantPlugin plugin;
    private final EnchantedBookService books;

    public BookAnvilListener(final JsEnchantPlugin plugin, final EnchantedBookService books) {
        this.plugin = plugin;
        this.books = books;
    }

    /**
     * 预览：算出结果并定价。
     *
     * <p>这是 {@code PrepareAnvilEvent}，不是 {@code Cancellable}；拒绝的写法是
     * {@code setResult(null)}（原版随后会把 null 写回结果槽）。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepare(final PrepareAnvilEvent event) {
        final AnvilInventory inventory = event.getInventory();
        final ItemStack book = inventory.getItem(BOOK_SLOT);

        if (!isOurs(book)) {
            // 不是本插件的书：原版附魔书、修理与改名全都不归这里管。
            return;
        }

        final ItemStack base = inventory.getItem(BASE_SLOT);

        if (isEmpty(base)) {
            // 只有书没有装备：没有可附着的对象，保持原版行为（通常本来也没有结果）。
            return;
        }

        final ItemStack result = this.combine(base, book, event.getResult());

        event.setResult(result);

        if (null == result) {
            return;
        }

        this.price(event.getView(), this.levelCost());
    }

    /**
     * 取出：按当前定义与配置重新校验一次，并把「屏幕上看到的东西」对齐了才允许成交。
     *
     * <p>要点是<b>不能让玩家替过期界面买单</b>：
     * <ul>
     *   <li>重算不出来（定义停了、书坏了、没提升）→ 取消并清空结果槽；</li>
     *   <li>重算出来但与展示不同（结果物品变了，或费用/消耗数量不是当前配置）→ 取消，
     *       把结果与价格同步成当前值，等玩家<b>下一次</b>点击才真正成交；</li>
     *   <li>两者一致 → 什么都不动，交给原版扣经验与材料。</li>
     * </ul>
     * 费用在每次事件里只读一次快照：判定与写入用同一个值，不会因为两次读之间发生重载
     * 而出现「判定用旧价、写的是新价」。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTake(final InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView view) || RESULT_SLOT != event.getRawSlot()) {
            return;
        }

        final AnvilInventory inventory = view.getTopInventory();
        final ItemStack book = inventory.getItem(BOOK_SLOT);

        if (!isOurs(book)) {
            return;
        }

        final ItemStack displayed = inventory.getItem(RESULT_SLOT);

        if (isEmpty(displayed)) {
            // 结果槽空着：原版本来就不允许取走，这里不需要额外动作。
            return;
        }

        final int cost = this.levelCost();
        final ItemStack fresh = this.combine(inventory.getItem(BASE_SLOT), book, displayed);
        final boolean resultChanged = null != fresh && !fresh.isSimilar(displayed);

        final TakeAction action = decideTake(
                null != fresh,
                resultChanged,
                view.getRepairCost(),
                cost,
                view.getRepairItemCountCost()
        );

        if (TakeAction.REJECT == action) {
            // 预览之后定义被停用/删除、书被换成了别的等级：这次点击作废。
            event.setCancelled(true);
            inventory.clear(RESULT_SLOT);

            return;
        }

        if (TakeAction.REFRESH == action) {
            if (resultChanged) {
                inventory.setItem(RESULT_SLOT, fresh);
            }

            this.price(view, cost);
            event.setCancelled(true);
        }
    }

    /**
     * 取走时的动作。
     */
    enum TakeAction {

        /** 展示与计价都还是当前值：不动，交给原版扣除与成交。 */
        TAKE,

        /** 结果或计价已过期：本次点击作废，同步成当前值，等下一次点击成交。 */
        REFRESH,

        /** 已经算不出结果：作废并清空结果槽。 */
        REJECT
    }

    /**
     * 取走的判定策略（纯函数，便于在没有服务端的环境里回归）。
     *
     * @param available           按当前定义重算后是否还有结果
     * @param resultChanged       重算结果与结果槽里展示的那件是否不同
     * @param displayedCost       界面当前显示的费用（{@code view.getRepairCost()}）
     * @param expectedCost        按当前配置应扣的费用（本次事件只取一次的同一个快照）
     * @param displayedCountCost  界面当前的第二格消耗数量（{@code view.getRepairItemCountCost()}）
     */
    static TakeAction decideTake(
            final boolean available,
            final boolean resultChanged,
            final int displayedCost,
            final int expectedCost,
            final int displayedCountCost
    ) {
        if (!available) {
            return TakeAction.REJECT;
        }

        // 消耗数量必须是 1：原版在它为 0 时会把第二格整摞清掉，那等于白吃玩家一摞书。
        if (resultChanged || displayedCost != expectedCost || 1 != displayedCountCost) {
            return TakeAction.REFRESH;
        }

        return TakeAction.TAKE;
    }

    /**
     * 合并本体。
     *
     * @param base     主材料（装备）；调用方已保证非空
     * @param book     第二格（已确认是本插件的书）
     * @param vanilla  原版刚算出来的结果，用于保留玩家在铁砧里输入的重命名
     * @return 要放进结果槽的物品；任何一条规则不满足时返回 {@code null}
     */
    private ItemStack combine(final ItemStack base, final ItemStack book, final ItemStack vanilla) {
        if (isEmpty(base)) {
            return null;
        }

        final EnchantedBookService.BookData data = this.books.read(book);

        final EnchantRegistry registry = this.plugin.getEnchantRegistry();
        final EnchantDefinition definition = null == registry || null == data ? null : registry.get(data.id());

        if (!usable(data, definition)) {
            // 书数据坏了、定义已停用/删除、或书的等级超出当前上限：拒绝，而不是尽量猜或夹取。
            return null;
        }

        if (!applicable(base.getType(), definition)) {
            return null;
        }

        final EnchantApplier applier = this.plugin.getApplier();

        // applier 在配置重载后整体替换，这里每次现取，绝不缓存旧实例。
        if (null == applier) {
            return null;
        }

        final ItemStack output = outputBase(base, vanilla);
        final int current = applier.getLevel(output, definition.id());
        final int merged = mergeLevel(current, data.level(), definition.maxLevel());

        if (merged <= current) {
            // 没有提升（同级已封顶、书的等级不高于装备）：原版铁砧在这种情况下也不产出。
            return null;
        }

        if (0 >= applier.apply(output, definition, merged)) {
            return null;
        }

        return output;
    }

    /**
     * 这本书配上当前定义还能不能用。
     *
     * <p>三条拒绝理由各自独立：书数据本身不合法、定义已被停用/删除（注册表里查不到）、
     * 书的等级超出当前 {@code max-level}（定义被下调，或书被外部改过）。
     * 上限用当前定义而不是书里记的等级判——重载之后以现在的定义为准。
     *
     * <p>{@code data.level()} 已经过 {@link EnchantedBookService#read} 的双读合并（PDC 与原版组件
     * 取较大值），所以这里不需要再各自读一遍。
     */
    static boolean usable(
            final EnchantedBookService.BookData data,
            final EnchantDefinition definition
    ) {
        return null != data
                && null != definition
                && 1 <= data.level()
                && data.level() <= definition.maxLevel();
    }

    /**
     * 结果物品的起点：主材料的完整拷贝，只额外继承「玩家在铁砧里输入的重命名」。
     *
     * <p>为什么不直接把原版结果当起点：原版结果里可能已经带着本插件刚写进去的
     * {@code e_<id>}（取走时重算的那条路径，{@code event.getResult()} 就是上一次的成品），
     * 拿它当起点会让 {@code current} 读到已经合并过的等级，合法的操作会被误判成「没有提升」。
     * 它也可能带着一本被动过手脚的书塞进来的原版附魔——照抄就等于凭空造附魔。
     *
     * <p>所以只挑一件东西过来：展示名。这与原版行为一致——重命名与附魔可以同时生效，
     * 而 PDC、Lore、原版附魔全都来自主材料本身。
     */
    private static ItemStack outputBase(final ItemStack base, final ItemStack vanilla) {
        final ItemStack output = base.clone();

        if (null == vanilla || vanilla.getType() != base.getType()) {
            return output;
        }

        final ItemMeta from = vanilla.getItemMeta();
        final ItemMeta to = output.getItemMeta();

        if (null == from || null == to) {
            return output;
        }

        final Component renamed = from.displayName();

        if (null == renamed || renamed.equals(to.displayName())) {
            return output;
        }

        to.displayName(renamed);
        output.setItemMeta(to);

        return output;
    }

    /**
     * 合并后的等级。
     *
     * <p>规则与原版附魔书一致：没有该附魔时取书的等级；同级升一级；异级取较高者。
     * 结果一律不超过 {@code maxLevel}。
     *
     * @param currentLevel 装备上现有的等级，0 表示没有
     * @param bookLevel    书上写的等级（调用方已确认合法）
     * @param maxLevel     定义上限
     * @return 合并后的等级；没有提升时返回的值 {@code <= currentLevel}
     */
    static int mergeLevel(final int currentLevel, final int bookLevel, final int maxLevel) {
        final int merged;

        if (0 >= currentLevel) {
            merged = bookLevel;
        } else if (currentLevel == bookLevel) {
            merged = currentLevel + 1;
        } else {
            merged = Math.max(currentLevel, bookLevel);
        }

        // 上限兜到至少 1：定义里写成 0 时也只能产出 1 级，不能写出「0 级附魔」。
        return Math.min(Math.max(1, maxLevel), merged);
    }

    /**
     * 这件材质能不能承载该附魔。
     *
     * <p>{@code items} 为空表示不限制物品（与 {@link EnchantDefinition#supports} 一致）。
     * 额外拒绝把书本身当主材料：那样产出的会是一件「带 {@code e_<id>} 的书」，
     * 而手持这本书就会触发装备效果——这正是书与装备数据必须隔离的那条线。
     */
    static boolean applicable(final Material material, final EnchantDefinition definition) {
        if (null == material || null == definition) {
            return false;
        }

        if (Material.ENCHANTED_BOOK == material) {
            return false;
        }

        return definition.supports(material);
    }

    /**
     * 当前配置下应用一本书要扣的等级费用。
     *
     * <p>每次事件只调用一次，判定与写入都用同一个值：重载随时可能在两次读取之间发生，
     * 读两次就可能出现「按旧价判定、按新价写入」。
     */
    private int levelCost() {
        final ConfigService.LootBooks loot = this.plugin.config().current().lootBooks();

        // 至少 1 级是本插件的设计默认（配置层同样只接受 1..39）。服务端本身允许 0（免费取出），
        // 所以这里不是被服务端逼出来的下限。
        return Math.max(1, loot.anvilLevelCost());
    }

    /** 定价：等级费用 + 只消耗一本书。 */
    private void price(final AnvilView view, final int cost) {
        view.setRepairCost(cost);

        // 数量必须是 1：原版在它为 0 时会把第二格整摞清掉。
        view.setRepairItemCountCost(1);
    }

    /**
     * 第二格是否由本监听器接管。
     *
     * <p>两个条件缺一不可：PDC 里有我们书的痕迹（哪怕数据是坏的，也要拦下来拒绝而不是放行），
     * 且这本书<b>没有</b>带本插件的原版附魔组件。带了组件的书是数据包生效后发的，
     * 原版铁砧能自己算，放行即可。
     */
    private boolean isOurs(final ItemStack book) {
        if (isEmpty(book)) {
            return false;
        }

        final ItemMeta meta = book.getItemMeta();

        if (null == meta || !EnchantedBookService.marked(meta.getPersistentDataContainer())) {
            return false;
        }

        return !hasOwnVanillaComponent(meta);
    }

    /** 这本书上是否带着本插件的原版附魔（即 {@code stored_enchantments} 里有我们的 id）。 */
    static boolean hasOwnVanillaComponent(final ItemMeta meta) {
        if (null == meta) {
            return false;
        }

        // 书的附魔在 STORED_ENCHANTMENTS，不在 enchantments。只查 hasEnchants() 会永远为 false，
        // 于是所有带组件的书都被判成"旧式纯 PDC 书"，分流整条失效、还按自研费用计价。
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            final Collection<Enchantment> stored = storage.getStoredEnchants().keySet();
            final List<NamespacedKey> storedKeys = new ArrayList<>(stored.size());

            for (final Enchantment enchantment : stored) {
                storedKeys.add(enchantment.getKey());
            }

            if (hasOwnVanillaComponent(storedKeys)) {
                return true;
            }
        }

        // 再查装备侧组件：有人可能把我们的附魔写进了 enchantments（例如物品被当成装备改过）。
        if (!meta.hasEnchants()) {
            return false;
        }

        final Collection<Enchantment> enchantments = meta.getEnchants().keySet();
        final List<NamespacedKey> keys = new ArrayList<>(enchantments.size());

        for (final Enchantment enchantment : enchantments) {
            keys.add(enchantment.getKey());
        }

        return hasOwnVanillaComponent(keys);
    }

    /**
     * 纯判定：这些附魔 key 里有没有本插件的。
     *
     * <p>单独抽出来是为了能在没有服务端的环境里回归「带组件的书放行、纯 PDC 书接管」这条分流。
     */
    static boolean hasOwnVanillaComponent(final Collection<NamespacedKey> keys) {
        for (final NamespacedKey key : keys) {
            if (EnchantApplier.isOwnEnchant(key)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isEmpty(final ItemStack item) {
        return null == item || item.getType().isAir();
    }
}
