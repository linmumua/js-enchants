package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 铁砧的判定规则：等级怎么合、什么情况下拒绝。
 *
 * <p>这些是「玩家会不会白花经验」和「会不会凭空造出附魔」的分界线，因此逐条钉死。
 *
 * <p><b>测试范围</b>：这里是纯规则（合并、可用性、材质），不含 Bukkit 事件对象——
 * {@code PrepareAnvilEvent}/{@code AnvilView} 需要一个注册好的服务端才能构造。
 * 也就是说，本文件<b>不</b>证明「事件确实被接上」「结果槽真的被写入」「经验真的被扣」；
 * 后两条靠读服务端字节码（{@code AnvilMenu#onTake}/{@code mayPickup}）核实，
 * 最终的实服行为仍需人工验收。
 */
class BookAnvilListenerTest {

    private static EnchantDefinition definition(final int maxLevel, final Set<Material> items) {
        return new EnchantDefinition(
                "fireball", "<gold><bold>Fireball", maxLevel, items, Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, "fireball.js", true
        );
    }

    @Test
    void 装备没有该附魔时直接取书的等级() {
        assertEquals(2, BookAnvilListener.mergeLevel(0, 2, 3));
        assertEquals(1, BookAnvilListener.mergeLevel(0, 1, 3));
    }

    @Test
    void 同级升一级() {
        assertEquals(3, BookAnvilListener.mergeLevel(2, 2, 5));
    }

    @Test
    void 异级取较高者() {
        assertEquals(4, BookAnvilListener.mergeLevel(4, 2, 5));
        assertEquals(4, BookAnvilListener.mergeLevel(2, 4, 5));
    }

    @Test
    void 合并结果不超过定义上限() {
        assertEquals(3, BookAnvilListener.mergeLevel(3, 3, 3), "同级但已封顶：算不出提升");
        assertEquals(3, BookAnvilListener.mergeLevel(1, 3, 3));
        assertEquals(1, BookAnvilListener.mergeLevel(0, 1, 1));
    }

    @Test
    void 封顶时没有提升() {
        final int current = 3;
        final int merged = BookAnvilListener.mergeLevel(current, 3, 3);

        assertTrue(
                merged <= current,
                "同一件已封顶的附魔再吃一本书必须判为「没有提升」，否则玩家会白花经验"
        );
    }

    @Test
    void 上限写成零时也不会产出零级() {
        assertEquals(1, BookAnvilListener.mergeLevel(0, 1, 0));
    }

    @Test
    void 书与当前定义必须同时可用() {
        final EnchantDefinition fireball = definition(3, Set.of(Material.DIAMOND_SWORD));

        assertTrue(BookAnvilListener.usable(new EnchantedBookService.BookData("fireball", 1), fireball));
        assertTrue(BookAnvilListener.usable(new EnchantedBookService.BookData("fireball", 3), fireball));

        assertFalse(
                BookAnvilListener.usable(new EnchantedBookService.BookData("fireball", 1), null),
                "定义被停用或删除后，服里的旧书不能再应用"
        );
        assertFalse(
                BookAnvilListener.usable(null, fireball),
                "书的数据读不出来（缺键/坏类型）时拒绝"
        );
        assertFalse(
                BookAnvilListener.usable(new EnchantedBookService.BookData("fireball", 4), fireball),
                "书上的等级超过当前上限（定义被下调或书被改过）时必须拒绝，而不是夹到上限"
        );
        assertFalse(BookAnvilListener.usable(new EnchantedBookService.BookData("fireball", 0), fireball));
    }

    @Test
    void 材质不支持时不产出结果() {
        final EnchantDefinition fireball = definition(3, Set.of(Material.DIAMOND_SWORD));

        assertTrue(BookAnvilListener.applicable(Material.DIAMOND_SWORD, fireball));
        assertFalse(BookAnvilListener.applicable(Material.IRON_SHOVEL, fireball));
        assertFalse(BookAnvilListener.applicable(null, fireball));
    }

    @Test
    void 不限物品的附魔也不接受把书本身当主材料() {
        final EnchantDefinition anyItem = definition(3, Set.of());

        assertTrue(BookAnvilListener.applicable(Material.DIRT, anyItem), "items 留空表示不限物品");
        assertFalse(
                BookAnvilListener.applicable(Material.ENCHANTED_BOOK, anyItem),
                "书当主材料会产出一件带 e_ 键的书，而手拿那本书就会触发装备效果"
        );
    }

    @Test
    void 展示与计价都还是当前值时直接成交() {
        assertEquals(
                BookAnvilListener.TakeAction.TAKE,
                BookAnvilListener.decideTake(true, false, 7, 7, 1)
        );
    }

    @Test
    void 重载改掉费用时先取消刷新下一次点击才成交() {
        final int shown = 3;
        final int reloaded = 7;

        assertEquals(
                BookAnvilListener.TakeAction.REFRESH,
                BookAnvilListener.decideTake(true, false, shown, reloaded, 1),
                "屏幕上还写着 3 级，绝不能按重载后的 7 级静默扣费"
        );

        // 刷新已经把界面与价格改成当前值，下一次点击才真正成交。
        assertEquals(
                BookAnvilListener.TakeAction.TAKE,
                BookAnvilListener.decideTake(true, false, reloaded, reloaded, 1)
        );
    }

    @Test
    void 界面消耗数量不是一本文时也先取消刷新() {
        assertEquals(
                BookAnvilListener.TakeAction.REFRESH,
                BookAnvilListener.decideTake(true, false, 1, 1, 0),
                "数量为 0 时原版会把第二格整摞清掉，那等于白吃玩家一摞书"
        );
    }

    @Test
    void 重算结果与展示不同时先取消刷新() {
        assertEquals(
                BookAnvilListener.TakeAction.REFRESH,
                BookAnvilListener.decideTake(true, true, 1, 1, 1),
                "换掉玩家即将取走的物品不能发生在同一次点击里"
        );
    }

    @Test
    void 算不出结果时直接作废并且不看计价() {
        assertEquals(BookAnvilListener.TakeAction.REJECT, BookAnvilListener.decideTake(false, false, 1, 1, 1));
        assertEquals(
                BookAnvilListener.TakeAction.REJECT,
                BookAnvilListener.decideTake(false, true, 5, 9, 0),
                "结果已经算不出来时，价格是否过期都不影响结论"
        );
    }

    @Test
    void 旧式纯_PDC_书由本监听器接管而带组件的书放行原版() {
        // 旧书：只有 book_enchant / book_level，原版铁砧算不出结果，必须由我们介入。
        assertFalse(
                BookAnvilListener.hasOwnVanillaComponent(List.of()),
                "没有本插件原版组件的书 = 旧式纯 PDC 书，走自研铁砧"
        );

        // 新书：数据包生效后同书写了 stored_enchantments，原版铁砧自己会做同级 +1、max_level 封顶、
        // 按 anvil_cost 扣等级并消耗 1 本，再介入一次就是双重介入。
        assertTrue(
                BookAnvilListener.hasOwnVanillaComponent(
                        List.of(new NamespacedKey(EnchantApplier.DATA_NAMESPACE, "fireball"))
                ),
                "带本插件原版组件的书必须放行给原版铁砧"
        );

        assertFalse(
                BookAnvilListener.hasOwnVanillaComponent(List.of(new NamespacedKey("minecraft", "sharpness"))),
                "原版附魔书不是我们的书，两个条件都不成立"
        );
    }

    @Test
    void 书级越界由双读拦下() {
        // PDC 写 2、组件写 3、定义已经降到 2：只读 PDC 那一侧会漏判成「合法」。
        final EnchantedBookService.BookData merged = EnchantedBookService.withStored(
                new EnchantedBookService.BookData("fireball", 2),
                3
        );

        assertFalse(
                BookAnvilListener.usable(merged, definition(2, Set.of(Material.DIAMOND_SWORD))),
                "书上的实际等级是 3，定义上限已降到 2，必须拒绝而不是夹取"
        );
    }
}
