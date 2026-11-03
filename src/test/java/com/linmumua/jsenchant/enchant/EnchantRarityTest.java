package com.linmumua.jsenchant.enchant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 稀有度是 Lore 配色的唯一依据，两条性质必须钉住：
 * 缺省推导能把不同 {@code max-level} 分到不同色阶（否则配色退化单色），
 * 以及 {@code byId} 对不认识的输入返回 {@code null} 而不是抛异常或猜一个值。
 */
class EnchantRarityTest {

    @Test
    void 等级推导稀有度() {
        assertEquals(EnchantRarity.COMMON, EnchantRarity.fromMaxLevel(1), "1 级应为最低档");
        assertEquals(EnchantRarity.UNCOMMON, EnchantRarity.fromMaxLevel(2), "2 级应升一档");
        assertEquals(EnchantRarity.RARE, EnchantRarity.fromMaxLevel(3), "3 级应再升一档");
        assertEquals(EnchantRarity.EPIC, EnchantRarity.fromMaxLevel(4), "4 级应为最高档");
        assertEquals(EnchantRarity.EPIC, EnchantRarity.fromMaxLevel(10), "超过 4 级应封顶在最高档");
    }

    @Test
    void 非法等级按最低档处理() {
        // 解析器已用 Math.max(1, …) 收口，这里再兜一次，避免出现「没有稀有度」的状态。
        assertEquals(EnchantRarity.COMMON, EnchantRarity.fromMaxLevel(0));
        assertEquals(EnchantRarity.COMMON, EnchantRarity.fromMaxLevel(-3));
    }

    @Test
    void id用于配置键与数据包() {
        assertEquals("common", EnchantRarity.COMMON.id());
        assertEquals("uncommon", EnchantRarity.UNCOMMON.id());
        assertEquals("rare", EnchantRarity.RARE.id());
        assertEquals("epic", EnchantRarity.EPIC.id());
    }

    @Test
    void byId大小写与空白被容忍() {
        assertEquals(EnchantRarity.RARE, EnchantRarity.byId("rare"));
        assertEquals(EnchantRarity.RARE, EnchantRarity.byId("RARE"));
        assertEquals(EnchantRarity.RARE, EnchantRarity.byId("  Rare  "));
        assertEquals(EnchantRarity.UNCOMMON, EnchantRarity.byId("uncommon"));
    }

    @Test
    void byId未知或不给值返回null() {
        // 解析器要靠 null 判断「写了但不合法」，抛异常会丢掉出错字段的上下文。
        assertNull(EnchantRarity.byId(null));
        assertNull(EnchantRarity.byId(""));
        assertNull(EnchantRarity.byId("legendary"));
    }

    @Test
    void 每个稀有度都有非空id() {
        for (final EnchantRarity rarity : EnchantRarity.values()) {
            assertNotNull(rarity.id());
            assertEquals(rarity, EnchantRarity.byId(rarity.id()), "id 必须能反查回自身");
        }
    }
}
