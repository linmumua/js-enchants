package com.linmumua.jsenchant.trigger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 悬停指纹的业务含义：它决定「这一次要不要重新问脚本要预览」。
 *
 * <p>为什么要单独钉住它：指纹算漏一项，玩家的表现就是「明明换了东西，画面却没变」——
 * 这正是线上「看不到预览」里最难自查的一类。预览内容由
 * {@code TriggerDispatcher#fireHover}（读主手附魔）与准星目标共同决定，
 * 因此指纹必须同时覆盖这两边：漏掉主手，换装备不刷新；漏掉等级，
 * 附魔升级后仍画旧范围；漏掉方块数据，同一格换方块不刷新。
 *
 * <p>反方向同样重要：同一件物品、同一格方块必须算出<b>完全相同</b>的指纹。
 * 否则每 4 tick 都会重建一套展示实体，玩家站着不动也在持续生成/销毁实体。
 */
class HoverFingerprintTest {

    @Test
    void 同一物品同一方块的指纹保持稳定() {
        final Block block = block("world", 10, 64, 20, "minecraft:stone");
        final Map<String, Integer> levels = levels("chain", 3);

        assertEquals(HoverWatcher.blockFingerprint(block), HoverWatcher.blockFingerprint(block),
                "同一格方块重复计算必须一致，否则每拍都会重建高亮");
        assertEquals(HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, levels),
                HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, levels),
                "同一件物品重复计算必须一致");
    }

    @Test
    void 换主手物品会改变指纹() {
        final String bare = HoverWatcher.itemFingerprint(Material.AIR, Map.of());
        final String holding = HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, levels("chain", 3));

        assertNotEquals(bare, holding,
                "空手与拿着带附魔的镐子必须算出不同指纹，否则换主手后预览不会刷新");
        assertNotEquals(HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, Map.of()), holding,
                "同材质但去掉附魔也必须算作变化，否则高亮会留在上一件物品的结果上");
    }

    @Test
    void 附魔等级变化会改变指纹() {
        assertNotEquals(
                HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, levels("chain", 1)),
                HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, levels("chain", 3)),
                "等级变了预览范围就会变，指纹不能视而不见"
        );
    }

    @Test
    void 附魔条目顺序不影响指纹() {
        final Map<String, Integer> first = new LinkedHashMap<>();
        first.put("chain", 3);
        first.put("haste", 2);

        final Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("haste", 2);
        reversed.put("chain", 3);

        assertEquals(
                HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, first),
                HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, reversed),
                "底层数据容器的迭代顺序不该让同一把镐子算出两个指纹"
        );
    }

    @Test
    void 相邻同材质方块不会撞指纹() {
        assertNotEquals(
                HoverWatcher.blockFingerprint(block("world", 10, 64, 20, "minecraft:stone")),
                HoverWatcher.blockFingerprint(block("world", 10, 64, 21, "minecraft:stone")),
                "准星移到相邻同材质方块时必须算作变化，否则高亮会停在原格"
        );
    }

    @Test
    void 同一格换方块会改变指纹() {
        assertNotEquals(
                HoverWatcher.blockFingerprint(block("world", 10, 64, 20, "minecraft:stone")),
                HoverWatcher.blockFingerprint(block("world", 10, 64, 20, "minecraft:diamond_ore")),
                "同一格被换成别的方块（或已被挖掉变成空气）时预览范围会变，必须重新派发"
        );
    }

    @Test
    void 换世界会改变指纹() {
        assertNotEquals(
                HoverWatcher.blockFingerprint(block("world", 10, 64, 20, "minecraft:stone")),
                HoverWatcher.blockFingerprint(block("nether", 10, 64, 20, "minecraft:stone")),
                "跨世界传送后同一坐标不是同一格方块"
        );
    }

    @Test
    void 潜行状态会改变指纹() {
        final String block = HoverWatcher.blockFingerprint(block("world", 10, 64, 20, "minecraft:diamond_ore"));
        final String held = HoverWatcher.itemFingerprint(Material.DIAMOND_PICKAXE, levels("vein_miner", 3));

        assertNotEquals(
                HoverWatcher.hoverFingerprint(block, held, false),
                HoverWatcher.hoverFingerprint(block, held, true),
                "脚本可以只在潜行时给预览；原地按下/松开潜行时方块与主手都没变，"
                        + "指纹漏掉潜行就等于描边永远清不掉、也永远亮不起来"
        );
        assertEquals(
                HoverWatcher.hoverFingerprint(block, held, true),
                HoverWatcher.hoverFingerprint(block, held, true),
                "潜行状态不变时指纹必须完全一致，否则每拍都会重建展示实体"
        );
    }

    private static Map<String, Integer> levels(final String id, final int level) {
        final Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put(id, level);

        return levels;
    }

    /** 方块替身：只提供指纹需要的世界名、坐标与方块数据字符串。 */
    private static Block block(
            final String worldName,
            final int x,
            final int y,
            final int z,
            final String data
    ) {
        final World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{ World.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> worldName;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeWorld(" + worldName + ')';
                    default -> throw new UnsupportedOperationException(
                            "指纹只该读世界名，被调用的方法: " + method.getName()
                    );
                }
        );

        final BlockData blockData = (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[]{ BlockData.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAsString" -> data;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> data;
                    default -> throw new UnsupportedOperationException(
                            "指纹只该读方块数据字符串，被调用的方法: " + method.getName()
                    );
                }
        );

        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{ Block.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getBlockData" -> blockData;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeBlock(" + worldName + ':' + x + ':' + y + ':' + z + ')';
                    default -> throw new UnsupportedOperationException(
                            "指纹只该读坐标与方块数据，被调用的方法: " + method.getName()
                    );
                }
        );
    }
}
