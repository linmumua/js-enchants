package com.linmumua.jsenchant.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 概率语义与上下文透传的行为锁定。
 *
 * <p>这里不解沙箱、不碰服务器：ScriptApi 的构造与 {@code rollChance()} 都是纯数据操作，
 * 用接口代理就能完整验证「只抽一次」与「投射物不挤占射手/目标」。
 */
class ScriptApiChanceTest {

    /** 只需要一个身份，方法体不会被调用；primitive 返回值给默认值以免代理炸掉。 */
    private static <T> T stub(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }

                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }

                    return defaultValue(method.getReturnType());
                }
        ));
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }

        if (boolean.class == type) {
            return false;
        }

        if (char.class == type) {
            return (char) 0;
        }

        if (void.class == type) {
            return null;
        }

        if (long.class == type) {
            return 0L;
        }

        if (double.class == type) {
            return 0.0D;
        }

        if (float.class == type) {
            return 0.0F;
        }

        if (short.class == type) {
            return (short) 0;
        }

        if (byte.class == type) {
            return (byte) 0;
        }

        return 0;
    }

    @Test
    void 已抽中时脚本重复调用都返回同一个结果() {
        final ScriptApi api = ScriptApi.attack(null, null, 3, "fireball", true, true);

        for (int call = 0; call < 1_000; call++) {
            assertTrue(api.rollChance(), "框架已判定命中，脚本再调也不该变");
        }
    }

    @Test
    void 已判空时脚本重复调用都返回同一个结果() {
        final ScriptApi api = ScriptApi.attack(null, null, 3, "fireball", false, true);

        for (int call = 0; call < 1_000; call++) {
            assertFalse(api.rollChance(), "框架已判定未命中，脚本再调也不该变");
        }
    }

    @Test
    void 旧签名概率零从不命中() {
        final ScriptApi api = ScriptApi.attack(null, null, 1, "fireball", 0.0D, true);

        assertFalse(api.rollChance());
    }

    @Test
    void 旧签名概率一必然命中() {
        final ScriptApi api = ScriptApi.attack(null, null, 1, "fireball", 1.0D, true);

        assertTrue(api.rollChance());
    }

    @Test
    void 旧签名中间概率只抽一次并固化() {
        final ScriptApi api = ScriptApi.attack(null, null, 1, "fireball", 0.5D, true);
        final boolean settled = api.rollChance();

        for (int call = 0; call < 200; call++) {
            assertEquals(settled, api.rollChance(), "同一次触发内的多次调用必须是同一个结果");
        }
    }

    @Test
    void 旧签名中间概率确实按概率分布() {
        int hits = 0;

        for (int attempt = 0; attempt < 2_000; attempt++) {
            if (ScriptApi.mine(null, null, 1, "fireball", 0.5D, true).rollChance()) {
                hits++;
            }
        }

        // 区间给得很宽（±20%），只用来证明「既不是恒真也不是恒假」，不追求统计精度。
        assertTrue(700 < hits && hits < 1_300, "0.5 概率的 2000 次抽样命中数异常: " + hits);
    }

    @Test
    void 射箭上下文透传投射物且保留射手目标语义() {
        final Player shooter = stub(Player.class);
        final Projectile projectile = stub(Projectile.class);

        final ScriptApi api = ScriptApi.shoot(shooter, projectile, 2, "archer", true, true);

        assertSame(shooter, api.player(), "player() 仍是射手");
        assertSame(projectile, api.projectile(), "投射物必须原样透传");
        assertNull(api.victim(), "射箭没有目标，不能拿投射物顶位");
        assertNull(api.entity());
        assertNull(api.block());
    }

    @Test
    void 射箭缺投射物时按空处理() {
        final ScriptApi api = ScriptApi.shoot(null, null, 1, "archer", true, true);

        assertNull(api.projectile());
        assertEquals("archer", api.enchantId());
        assertEquals(1, api.level());
    }

    @Test
    void 攻击上下文同时保留射手与目标() {
        final Player attacker = stub(Player.class);
        final LivingEntity victim = stub(LivingEntity.class);

        final ScriptApi api = ScriptApi.attack(attacker, victim, 4, "fireball", true, false);

        assertSame(attacker, api.player());
        assertSame(victim, api.victim());
        assertNull(api.projectile(), "近战没有投射物");
        assertFalse(api.cooldownReady());
    }
}
