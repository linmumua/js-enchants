package com.linmumua.jsenchant.enchant;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 桥接层的契约测试。
 *
 * <p>这里没有服务端，所以断言的都是「注册表不可用时」的那条路径——它恰恰是最重要的一条：
 * 数据包还没被加载时 {@link VanillaEnchantBridge#enchantment(String)} 必须返回 {@code null}
 * 而不是抛异常，调用方才能安心退化成纯 PDC 存储。
 */
class VanillaEnchantBridgeTest {

    @Test
    void key固定用小写命名空间() {
        final NamespacedKey key = VanillaEnchantBridge.key("FireBall");

        assertEquals("jsenchants", key.getNamespace());
        assertEquals("fireball", key.getKey());
    }

    @Test
    void 空id直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> VanillaEnchantBridge.key(null));
        assertThrows(IllegalArgumentException.class, () -> VanillaEnchantBridge.key("  "));
    }

    @Test
    void 注册表不可用时返回null而不抛异常() {
        assertDoesNotThrow(VanillaEnchantBridge::available);
        assertNull(VanillaEnchantBridge.enchantment("fireball"), "注册表里没有 ⇒ 调用方不要写原版组件");
        assertNull(VanillaEnchantBridge.enchantment(null));
        assertFalse(VanillaEnchantBridge.registered("fireball"));
    }

    @Test
    void 反查只认本插件命名空间() {
        assertEquals("fireball", VanillaEnchantBridge.idIn(new NamespacedKey("jsenchants", "fireball")));
        assertNull(VanillaEnchantBridge.idIn(new NamespacedKey("minecraft", "sharpness")), "原版附魔不是我们的");
        assertNull(VanillaEnchantBridge.idIn(null));
        assertNull(VanillaEnchantBridge.idOf(null));
    }
}
