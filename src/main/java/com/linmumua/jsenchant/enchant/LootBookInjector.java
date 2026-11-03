package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.JsEnchantPlugin;
import com.linmumua.jsenchant.config.ConfigService;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * 原版结构容器的战利品里追加本插件的附魔书。
 *
 * <p><b>只追加，不接管</b>：原版已抽出的战利品保持原样，本监听器只在末尾加一本，
 * 也从不修改战利品表、数据包或世界文件——箱子打开前那一瞬的事件才是唯一的写入点。
 *
 * <p><b>掉率与附魔数量解耦</b>：先按 {@code loot-books.chance} 抽一次「这次到底出不出书」，
 * 再从当前全部启用定义里等概率选一本。候选池变大只会改变「出哪一本」，不会让总掉率膨胀。
 *
 * <p><b>候选池就是注册表当前快照</b>：不维护任何名单，所以新增定义自动进入候选，
 * 停用、删除、重载立刻生效；演示附魔（例如 {@code packet_notice}）同样在池内。
 *
 * <p><b>线程</b>：本方法只在生成战利品的区域线程执行，且只读取 volatile 的配置快照与
 * 附魔表，并写事件自带的战利品列表——不读别的区域的方块、实体或世界状态。
 */
public final class LootBookInjector implements Listener {

    /** 附魔书工厂。抽成接口是为了让选择规则在没有服务端的单测里也能跑。 */
    interface BookFactory {

        ItemStack create(EnchantDefinition definition, int level);
    }

    private final Supplier<ConfigService.LootBooks> settings;
    private final Supplier<Collection<EnchantDefinition>> candidates;
    private final BookFactory books;
    private final RandomGenerator random;

    public LootBookInjector(final JsEnchantPlugin plugin, final EnchantedBookService books) {
        this(
                () -> plugin.config().current().lootBooks(),
                () -> candidatesOf(plugin),
                books::create,
                // 战利品生成会落在各自的区域线程上，Random 本身线程安全，够用且不需要额外锁。
                new Random()
        );
    }

    LootBookInjector(
            final Supplier<ConfigService.LootBooks> settings,
            final Supplier<Collection<EnchantDefinition>> candidates,
            final BookFactory books,
            final RandomGenerator random
    ) {
        this.settings = settings;
        this.candidates = candidates;
        this.books = books;
        this.random = random;
    }

    /**
     * 容器战利品生成。
     *
     * <p>{@code ignoreCancelled} 只在服务端派发时生效；方法内部再判一次取消，
     * 这样直接调用（测试、或别的插件转发）时的行为与线上一致。
     */
    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(final LootGenerateEvent event) {
        if (event.isCancelled() || event.isPlugin()) {
            // 插件自己给的战利品不再二次注入，避免「插件送礼又掉书」的叠加。
            return;
        }

        final ConfigService.LootBooks loot = this.settings.get();

        if (!loot.enabled()) {
            return;
        }

        final String table = tableKey(event);

        // 只认配置里的原版容器表；钓鱼、生物掉落与第三方表都不在覆盖范围内。
        if (null == table || !acceptsTable(loot.tables(), table)) {
            return;
        }

        final Optional<Roll> roll = select(this.random, loot.chance(), this.candidates.get());

        if (roll.isEmpty()) {
            return;
        }

        final ItemStack book = this.books.create(roll.get().definition(), roll.get().level());

        if (null == book) {
            // 书没造出来（定义刚被停用、PDC 写入失败等）：宁可不给，也不塞一个无效物品进去。
            return;
        }

        // 追加而非替换：原版掉落一项不少，且一次事件最多加一本。
        event.getLoot().add(book);
    }

    /**
     * 战利品表是否落在允许清单内。
     *
     * <p>清单在配置层已经收紧到 {@code minecraft:chests/} 前缀，这里只负责精确 key 与
     * 尾部 {@code *} 通配的匹配。
     */
    static boolean acceptsTable(final List<String> patterns, final String table) {
        for (final String pattern : patterns) {
            if (pattern.endsWith("*")) {
                if (table.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.equals(table)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 抽一次：先总概率，再从候选池等概率取一本、等级均匀落在 {@code 1..max-level}。
     *
     * @return 本次要生成的附魔书；没抽中或池子为空时为 {@link Optional#empty()}
     */
    static Optional<Roll> select(
            final RandomGenerator random,
            final double chance,
            final Collection<EnchantDefinition> definitions
    ) {
        if (0.0D >= chance || definitions.isEmpty()) {
            return Optional.empty();
        }

        // 概率 1 不必浪费一次抽样，也让「必出」的测试不依赖具体随机序列。
        if (1.0D > chance && random.nextDouble() >= chance) {
            return Optional.empty();
        }

        // 定序拷贝：候选池顺序必须与注册表快照一致，抽到哪本才可复现。
        final List<EnchantDefinition> pool = List.copyOf(definitions);
        final EnchantDefinition definition = pool.get(random.nextInt(pool.size()));
        final int level = 1 >= definition.maxLevel()
                ? 1
                : random.nextInt(1, definition.maxLevel() + 1);

        return Optional.of(new Roll(definition, level));
    }

    private static String tableKey(final LootGenerateEvent event) {
        final LootTable table = event.getLootTable();
        final NamespacedKey key = null == table ? null : table.getKey();

        return null == key ? null : key.getNamespace() + ":" + key.getKey();
    }

    /**
     * 当前启用定义。
     *
     * <p>注册表在装配期创建，重载整体替换；万一事件早于装配到达，空集合就是不生成，
     * 不允许在事件线程上出现 {@code NullPointerException}。
     */
    private static Collection<EnchantDefinition> candidatesOf(final JsEnchantPlugin plugin) {
        final EnchantRegistry registry = plugin.getEnchantRegistry();

        return null == registry ? List.of() : registry.all().values();
    }

    /** 本次抽中的附魔与等级。 */
    record Roll(EnchantDefinition definition, int level) {
    }
}
