package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.config.ConfigService;
import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版战利品注入的两条业务承诺：<b>总掉率只由配置决定</b>（附魔越多不该掉得越多），
 * 以及<b>只追加不接管</b>（原版掉落一项不少，一次最多一本）。
 *
 * <p>这些测试不碰真实服务端：候选池来自 {@link EnchantRegistry} 的真实快照，
 * 随机源是注入的 {@link Random}，附魔书用工厂替身，事件用 {@code Proxy} 造出最小替身。
 */
class LootBookInjectorTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final String DEFAULT_TABLE = "minecraft:chests/simple_dungeon";

    private static EnchantDefinition definition(final String id, final int maxLevel) {
        return new EnchantDefinition(
                id, id, maxLevel, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, id + ".js", true
        );
    }

    private static ConfigService.LootBooks loot(final boolean enabled, final double chance) {
        return new ConfigService.LootBooks(enabled, chance, List.of("minecraft:chests/*"), 1);
    }

    /** 记录调用次数的最小书工厂替身；{@code fail=true} 时模拟「造不出来」。 */
    private static final class FakeBooks implements LootBookInjector.BookFactory {

        private final List<Integer> levels = new ArrayList<>();
        private boolean fail;

        @Override
        public ItemStack create(final EnchantDefinition definition, final int level) {
            this.levels.add(level);

            return this.fail ? null : new FakeBook();
        }

        private int calls() {
            return this.levels.size();
        }
    }

    /** {@code ItemStack} 的受保护无参构造器不碰服务端注册表，测试里只用来「占位计数」。 */
    private static final class FakeBook extends ItemStack {

        private FakeBook() {
            super();
        }
    }

    private static Object proxy(final Class<?> type, final String method, final Object value) {
        return Proxy.newProxyInstance(
                LootBookInjectorTest.class.getClassLoader(),
                new Class<?>[]{type},
                (instance, called, args) -> {
                    if (called.getName().equals(method)) {
                        return value;
                    }

                    if ("toString".equals(called.getName())) {
                        return "fake-" + type.getSimpleName();
                    }

                    if ("hashCode".equals(called.getName())) {
                        return System.identityHashCode(instance);
                    }

                    if ("equals".equals(called.getName())) {
                        return instance == args[0];
                    }

                    return null;
                }
        );
    }

    private static LootGenerateEvent event(
            final String tableKey,
            final boolean plugin,
            final List<ItemStack> loot
    ) {
        final int split = tableKey.indexOf(':');
        final LootTable table = (LootTable) proxy(
                LootTable.class,
                "getKey",
                new NamespacedKey(tableKey.substring(0, split), tableKey.substring(split + 1))
        );
        final World world = (World) proxy(World.class, "getName", "world");
        final InventoryHolder holder = (InventoryHolder) proxy(InventoryHolder.class, "toString", "holder");

        return new LootGenerateEvent(world, null, holder, table, null, loot, plugin);
    }

    private static LootBookInjector injector(
            final ConfigService.LootBooks loot,
            final List<EnchantDefinition> candidates,
            final LootBookInjector.BookFactory books,
            final long seed
    ) {
        return new LootBookInjector(() -> loot, () -> candidates, books, new Random(seed));
    }

    @Test
    void 概率为零时永不出书() {
        final List<EnchantDefinition> pool = List.of(definition("a", 3), definition("b", 3));

        for (int seed = 0; seed < 100; seed++) {
            assertTrue(
                    LootBookInjector.select(new Random(seed), 0.0D, pool).isEmpty(),
                    "概率 0 必须是「一本书都不掉」"
            );
        }
    }

    @Test
    void 概率为一时每次必出书() {
        final List<EnchantDefinition> pool = List.of(definition("a", 3));

        for (int seed = 0; seed < 100; seed++) {
            assertTrue(LootBookInjector.select(new Random(seed), 1.0D, pool).isPresent());
        }
    }

    @Test
    void 空候选池不出书() {
        assertTrue(LootBookInjector.select(new Random(1L), 1.0D, List.of()).isEmpty());
    }

    @Test
    void 等级落在定义上限之内且覆盖全档() {
        final EnchantDefinition single = definition("single", 1);
        final EnchantDefinition five = definition("five", 5);
        final Random random = new Random(424242L);
        final Set<Integer> seen = new HashSet<>();

        for (int draw = 0; draw < 400; draw++) {
            final int level = LootBookInjector.select(random, 1.0D, List.of(five)).orElseThrow().level();

            assertTrue(1 <= level && 5 >= level, "等级必须在 1..5 之间，实际为 " + level);
            seen.add(level);
        }

        assertEquals(Set.of(1, 2, 3, 4, 5), seen, "1..max-level 的每一档都应该抽得到");
        assertEquals(1, LootBookInjector.select(new Random(1L), 1.0D, List.of(single)).orElseThrow().level());
    }

    /**
     * 演示附魔（{@code packet_notice}）也是真实附魔，没有理由被排除在候选池之外；
     * 候选池完全来自注册表，所以「不硬编码名单」这件事由这个测试守住。
     */
    @Test
    void 全部启用定义都可能是候选() {
        final List<EnchantDefinition> pool = List.of(
                definition("fireball", 3),
                definition("packet_notice", 1),
                definition("lifesteal", 2)
        );
        final Random random = new Random(600613L);
        final Set<String> picked = new HashSet<>();

        for (int draw = 0; draw < 600; draw++) {
            picked.add(LootBookInjector.select(random, 1.0D, pool).orElseThrow().definition().id());
        }

        assertEquals(Set.of("fireball", "packet_notice", "lifesteal"), picked);
    }

    /**
     * 总掉率与候选数量无关：先抽总概率，再从池子里等概率选一本。
     * 这里让两条链路用同一颗种子、按同样顺序各抽 400 次，命中次数必须完全一致。
     */
    @Test
    void 总掉率不随候选数量增长() {
        final List<EnchantDefinition> one = List.of(definition("only", 1));
        final List<EnchantDefinition> many = new ArrayList<>();

        for (int index = 0; index < 10; index++) {
            many.add(definition("e" + index, 1));
        }

        final Random single = new Random(20240917L);
        final Random crowded = new Random(20240917L);

        int hitsWithOne = 0;
        int hitsWithMany = 0;

        for (int draw = 0; draw < 400; draw++) {
            if (LootBookInjector.select(single, 0.5D, one).isPresent()) {
                hitsWithOne++;
            }

            if (LootBookInjector.select(crowded, 0.5D, many).isPresent()) {
                hitsWithMany++;
            }
        }

        assertEquals(hitsWithOne, hitsWithMany, "候选数量变化不能让总掉率漂移");
        assertTrue(0 < hitsWithOne && 400 > hitsWithOne, "0.5 的抽样既不该全中也不该全空: " + hitsWithOne);
    }

    @Test
    void 战利品表按精确key与尾部通配匹配() {
        final List<String> patterns = List.of("minecraft:chests/simple_dungeon", "minecraft:chests/village/*");

        assertTrue(LootBookInjector.acceptsTable(patterns, "minecraft:chests/simple_dungeon"));
        assertTrue(LootBookInjector.acceptsTable(patterns, "minecraft:chests/village/house"));
        assertTrue(LootBookInjector.acceptsTable(List.of("minecraft:chests/*"), "minecraft:chests/abandoned_mineshaft"));
        assertFalse(LootBookInjector.acceptsTable(patterns, "minecraft:chests/abandoned_mineshaft"));
        assertFalse(LootBookInjector.acceptsTable(patterns, "minecraft:entities/zombie"));
        assertFalse(
                LootBookInjector.acceptsTable(patterns, "other:chests/simple_dungeon"),
                "别的 namespace 即使同名也不能命中"
        );
    }

    @Test
    void 候选取注册表当前快照_新增删除停用即时生效(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "a.yml", "id: a\n");
        this.write(dir, "b.yml", "id: b\n");
        registry.loadAll(dir);

        final List<EnchantDefinition> pool = List.copyOf(registry.all().values());

        assertEquals(Set.of("a", "b"), this.pickAll(pool), "启用定义都应进入候选池");

        // 新增 c：无需改代码即可进入候选；停用 b：立刻退出候选。
        this.write(dir, "c.yml", "id: c\n");
        this.write(dir, "b.yml", "id: b\nenabled: false\n");
        registry.reload(dir);

        assertEquals(Set.of("a", "c"), this.pickAll(List.copyOf(registry.all().values())));

        Files.delete(dir.resolve("a.yml"));
        registry.reload(dir);

        assertEquals(Set.of("c"), this.pickAll(List.copyOf(registry.all().values())), "被删掉的定义必须立刻退出候选");
    }

    @Test
    void 取消的事件不注入() {
        final FakeBooks books = new FakeBooks();
        final List<ItemStack> loot = new ArrayList<>();
        final LootGenerateEvent event = event(DEFAULT_TABLE, false, loot);

        event.setCancelled(true);
        injector(loot(true, 1.0D), List.of(definition("a", 1)), books, 7L).onLootGenerate(event);

        assertEquals(0, books.calls(), "被取消的生成不能额外加东西");
        assertTrue(loot.isEmpty());
        assertTrue(event.getLoot().isEmpty());
    }

    @Test
    void 插件自己生成的战利品不注入() {
        final FakeBooks books = new FakeBooks();
        final List<ItemStack> loot = new ArrayList<>();
        final LootGenerateEvent event = event(DEFAULT_TABLE, true, loot);

        assertTrue(event.isPlugin());

        injector(loot(true, 1.0D), List.of(definition("a", 1)), books, 7L).onLootGenerate(event);

        assertEquals(0, books.calls());
        assertTrue(loot.isEmpty());
    }

    @Test
    void 非原版容器表不注入() {
        for (final String table : List.of(
                "minecraft:entities/zombie",
                "minecraft:fishing/treasure",
                "other:chests/simple_dungeon"
        )) {
            final FakeBooks books = new FakeBooks();
            final List<ItemStack> loot = new ArrayList<>();

            injector(loot(true, 1.0D), List.of(definition("a", 1)), books, 7L)
                    .onLootGenerate(event(table, false, loot));

            assertEquals(0, books.calls(), "不在允许清单里的表不能掉书: " + table);
            assertTrue(loot.isEmpty());
        }
    }

    @Test
    void 关闭或零概率时不注入() {
        final FakeBooks books = new FakeBooks();
        final List<ItemStack> loot = new ArrayList<>();
        final LootGenerateEvent event = event(DEFAULT_TABLE, false, loot);

        injector(loot(false, 1.0D), List.of(definition("a", 1)), books, 7L).onLootGenerate(event);
        injector(loot(true, 0.0D), List.of(definition("a", 1)), books, 7L).onLootGenerate(event);

        assertEquals(0, books.calls());
        assertTrue(loot.isEmpty());
    }

    @Test
    void 候选为空时不注入() {
        final FakeBooks books = new FakeBooks();
        final List<ItemStack> loot = new ArrayList<>();

        injector(loot(true, 1.0D), List.of(), books, 7L)
                .onLootGenerate(event(DEFAULT_TABLE, false, loot));

        assertEquals(0, books.calls());
        assertTrue(loot.isEmpty());
    }

    /** 书造不出来时宁可少给一本，也不能往箱子里塞一个无效物品。 */
    @Test
    void 书创建失败时不注入() {
        final FakeBooks books = new FakeBooks();
        final List<ItemStack> loot = new ArrayList<>();

        books.fail = true;
        injector(loot(true, 1.0D), List.of(definition("a", 1)), books, 7L)
                .onLootGenerate(event(DEFAULT_TABLE, false, loot));

        assertEquals(1, books.calls(), "候选池非空时应该尝试造书");
        assertTrue(loot.isEmpty());
    }

    /**
     * 原版掉落一项不少、一次最多一本：注入是「追加」，不是「替换」。
     */
    @Test
    void 保留原始战利品且每次最多一本() {
        final FakeBooks books = new FakeBooks();
        final ItemStack first = new FakeBook();
        final ItemStack second = new FakeBook();
        final List<ItemStack> loot = new ArrayList<>(List.of(first, second));
        final LootGenerateEvent event = event(DEFAULT_TABLE, false, loot);

        injector(loot(true, 1.0D), List.of(definition("a", 3), definition("b", 3)), books, 7L)
                .onLootGenerate(event);

        assertEquals(3, event.getLoot().size(), "原有 2 项 + 最多 1 本");
        assertSame(first, event.getLoot().get(0), "原版掉落不能被挪动或替换");
        assertSame(second, event.getLoot().get(1));
        assertNotNull(event.getLoot().get(2));
        assertEquals(1, books.calls(), "一次生成只尝试造一本书");
        assertTrue(1 <= books.levels.get(0), "等级必须是正数");
    }

    @Test
    void 注册表为空时不注入(@TempDir final Path dir) {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);
        final FakeBooks books = new FakeBooks();
        final List<ItemStack> loot = new ArrayList<>();
        final LootBookInjector injector = new LootBookInjector(
                () -> loot(true, 1.0D),
                () -> registry.all().values(),
                books,
                new Random(7L)
        );

        injector.onLootGenerate(event(DEFAULT_TABLE, false, loot));

        assertEquals(0, books.calls());
        assertTrue(loot.isEmpty(), "空注册表不允许掉书");
    }

    private Set<String> pickAll(final List<EnchantDefinition> pool) {
        final Random random = new Random(97L);
        final Set<String> picked = new HashSet<>();

        for (int draw = 0; draw < 600; draw++) {
            LootBookInjector.select(random, 1.0D, pool)
                    .ifPresent(roll -> picked.add(roll.definition().id()));
        }

        return picked;
    }

    private void write(final Path dir, final String name, final String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
