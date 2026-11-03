package com.linmumua.jsenchant.trigger;

import com.linmumua.jsenchant.enchant.EnchantDefinition;
import org.junit.jupiter.api.Test;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发策略的规则必须能脱离服务器验证：概率语义、冷却预占与回滚、告警限流都是纯状态机，
 * 混进 Bukkit 事件里就只能靠实测，改一行也没法确认是否退化。
 *
 * <p>预占与回滚这两组用例覆盖的正是 Folia 带来的新前提：同一条冷却可能被多个区域线程
 * 同时问到，因此「判定」与「记账」必须是同一步，失败也只能回滚自己那一步。
 */
class TriggerPolicyTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 可控时钟 + 可控随机源，draws 记录随机被抽了几次。 */
    private static final class Fixture {

        private final AtomicLong now = new AtomicLong(1_000L);
        private final AtomicInteger draws = new AtomicInteger();
        private double nextRandom;

        private final TriggerPolicy policy = new TriggerPolicy(this.now::get, () -> {
            this.draws.incrementAndGet();

            return this.nextRandom;
        });
    }

    private static EnchantDefinition enchant(final double chanceBase, final double chancePerLevel, final long cooldown) {
        return new EnchantDefinition("test", "测试附魔", 5, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), cooldown, chanceBase, chancePerLevel, "test.js", true);
    }

    @Test
    void 概率为零时从不执行且不消耗随机数() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(0.0D, 0.0D, 0L);

        for (int attempt = 0; attempt < 50; attempt++) {
            assertFalse(fixture.policy.claim(PLAYER, definition, 5).granted(),
                    "概率 0 的附魔一次都不该执行");
        }

        assertEquals(0, fixture.draws.get(), "概率 0 不该消耗随机数");
    }

    @Test
    void 概率为一必然执行且不消耗随机数() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 0L);

        for (int attempt = 0; attempt < 50; attempt++) {
            assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted(), "概率 1 必须执行");
        }

        assertEquals(0, fixture.draws.get(), "概率 1 是确定事件，不该消耗随机数");
    }

    @Test
    void 中间概率每次触发只抽一次() {
        final Fixture fixture = new Fixture();
        fixture.nextRandom = 0.5D;

        final EnchantDefinition definition = enchant(0.5D, 0.0D, 0L);

        for (int attempt = 0; attempt < 10; attempt++) {
            fixture.policy.claim(PLAYER, definition, 1);
        }

        assertEquals(10, fixture.draws.get(), "10 次触发只允许 10 次抽取，不能重复抽");
    }

    @Test
    void 中间概率未命中时不执行脚本() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(0.3D, 0.0D, 0L);

        fixture.nextRandom = 0.29D;
        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted(), "0.29 < 0.3 应命中");

        fixture.nextRandom = 0.3D;
        assertFalse(fixture.policy.claim(PLAYER, definition, 1).granted(), "0.3 不小于 0.3，未命中");

        fixture.nextRandom = 0.99D;
        assertFalse(fixture.policy.claim(PLAYER, definition, 1).granted(), "0.99 未命中");
    }

    @Test
    void 中间概率未命中不刷新冷却() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(0.5D, 0.0D, 1_000L);

        // 未命中：这一下不该留下任何记录。
        fixture.nextRandom = 0.9D;
        assertFalse(fixture.policy.claim(PLAYER, definition, 1).granted());
        assertTrue(fixture.policy.cooldownReady(PLAYER, definition), "未命中不该写冷却");

        // 下一次直接命中，说明冷却没被上一次未命中拖住。
        fixture.nextRandom = 0.1D;
        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted(), "未命中后下一次仍可正常命中");
    }

    @Test
    void 命中后计时期间不再执行也不再抽随机数() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(0.5D, 0.0D, 1_000L);
        fixture.nextRandom = 0.1D;

        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted());
        assertEquals(1, fixture.draws.get());

        for (int attempt = 0; attempt < 5; attempt++) {
            assertFalse(fixture.policy.claim(PLAYER, definition, 1).granted(), "冷却期内不执行");
        }

        assertEquals(1, fixture.draws.get(), "冷却期内不该消耗随机数");

        fixture.now.addAndGet(1_000L);

        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted(), "冷却到期后恢复");
        assertEquals(2, fixture.draws.get());
    }

    @Test
    void 等级加成会带高概率() {
        final Fixture fixture = new Fixture();
        fixture.nextRandom = 0.59D;

        final EnchantDefinition definition = enchant(0.2D, 0.2D, 0L);

        assertFalse(fixture.policy.claim(PLAYER, definition, 1).granted(), "1 级是 0.2，0.59 未命中");
        assertTrue(fixture.policy.claim(PLAYER, definition, 3).granted(), "3 级是 0.6，0.59 命中");
    }

    @Test
    void 冷却未就绪时不执行也不抽随机数() {
        final Fixture fixture = new Fixture();
        fixture.nextRandom = 0.0D;

        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);

        fixture.policy.claim(PLAYER, definition, 1).commit();

        assertFalse(fixture.policy.claim(PLAYER, definition, 1).granted(), "冷却期内不执行脚本");
        assertEquals(0, fixture.draws.get(), "冷却没过就不该动随机数");

        fixture.now.addAndGet(1_000L);

        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted(), "冷却到期后恢复");
    }

    @Test
    void 冷却按玩家与附魔分别记账() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);
        final UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");

        fixture.policy.claim(PLAYER, definition, 1).commit();

        assertFalse(fixture.policy.cooldownReady(PLAYER, definition));
        assertTrue(fixture.policy.cooldownReady(other, definition), "别人的冷却与本玩家无关");
    }

    @Test
    void 无冷却的附魔不记账() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 0L);

        final TriggerPolicy.Claim claim = fixture.policy.claim(PLAYER, definition, 1);
        assertTrue(claim.granted());

        claim.commit();
        claim.release();

        assertTrue(fixture.policy.cooldownReady(PLAYER, definition));
        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted());
    }

    @Test
    void 命中即预占所以冷却立刻生效() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);

        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted());
        assertFalse(fixture.policy.cooldownReady(PLAYER, definition),
                "预占就必须立刻挡住并发的第二次触发，不能等脚本跑完才记账");
    }

    @Test
    void 执行失败回滚后立刻可以再次触发() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);

        final TriggerPolicy.Claim failed = fixture.policy.claim(PLAYER, definition, 1);
        assertTrue(failed.granted());
        failed.release();

        assertTrue(fixture.policy.cooldownReady(PLAYER, definition), "回滚后不该留在冷却里");
        assertTrue(fixture.policy.claim(PLAYER, definition, 1).granted(), "回滚后下一次必须能正常触发");
    }

    @Test
    void 旧claim的回滚不会清掉新claim的预占() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);

        final TriggerPolicy.Claim first = fixture.policy.claim(PLAYER, definition, 1);
        first.release();

        final TriggerPolicy.Claim second = fixture.policy.claim(PLAYER, definition, 1);
        assertTrue(second.granted());

        // 迟到的回滚（例如异常处理晚了一拍）只能撤掉自己那一份。
        first.release();

        assertFalse(fixture.policy.cooldownReady(PLAYER, definition), "别人的预占不能被误删");
    }

    @Test
    void commit之后回滚不再生效() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);

        final TriggerPolicy.Claim claim = fixture.policy.claim(PLAYER, definition, 1);
        claim.commit();
        claim.release();

        assertFalse(fixture.policy.cooldownReady(PLAYER, definition), "已经执行成功就不该被回滚掉");
    }

    @Test
    void 并发判定只有一个赢家() throws Exception {
        final Fixture fixture = new Fixture();
        final EnchantDefinition definition = enchant(1.0D, 0.0D, 1_000L);
        final int threads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<TriggerPolicy.Claim>> results = new ArrayList<>();
        final List<TriggerPolicy.Claim> claims = new ArrayList<>();

        try {
            for (int index = 0; index < threads; index++) {
                results.add(pool.submit(() -> {
                    start.await();

                    return fixture.policy.claim(PLAYER, definition, 1);
                }));
            }

            // 所有线程尽量同时开跑：并发下「查冷却 → 执行 → 记账」会被穿过两次，这里不允许。
            start.countDown();

            for (final Future<TriggerPolicy.Claim> result : results) {
                claims.add(result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, claims.stream().filter(TriggerPolicy.Claim::granted).count(),
                "同一玩家同一条冷却，并发只允许一个赢家");
        assertFalse(fixture.policy.cooldownReady(PLAYER, definition), "赢家的预占必须留下");
    }

    @Test
    void 到期且已执行的冷却会被低频清理() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition cooled = enchant(1.0D, 0.0D, 1_000L);
        final EnchantDefinition noCooldown = enchant(1.0D, 0.0D, 0L);

        fixture.policy.claim(PLAYER, cooled, 1).commit();
        assertEquals(1, fixture.policy.trackedCooldowns());

        // 时钟推过「冷却到期」与「清理间隔」两个条件。
        fixture.now.addAndGet(TriggerPolicy.CLEANUP_INTERVAL_MILLIS + 1_000L);

        assertTrue(fixture.policy.claim(PLAYER, noCooldown, 1).granted(), "清理不能影响正常判定");
        assertEquals(0, fixture.policy.trackedCooldowns(),
                "已执行且过期的条目必须被清掉，否则冷却表只增不减");
    }

    @Test
    void 执行中的预占不会被清理() {
        final Fixture fixture = new Fixture();
        final EnchantDefinition cooled = enchant(1.0D, 0.0D, 1_000L);
        final EnchantDefinition noCooldown = enchant(1.0D, 0.0D, 0L);

        final TriggerPolicy.Claim inFlight = fixture.policy.claim(PLAYER, cooled, 1);
        assertTrue(inFlight.granted());

        fixture.now.addAndGet(TriggerPolicy.CLEANUP_INTERVAL_MILLIS + 1_000L);

        // 这一次触发会顺带触发清理：没 commit 的预占必须活着。
        fixture.policy.claim(PLAYER, noCooldown, 1);

        assertEquals(1, fixture.policy.trackedCooldowns(),
                "执行中的预占被清掉就等于放跑一次并发触发");

        inFlight.release();

        assertEquals(0, fixture.policy.trackedCooldowns(),
                "清理过后旧 claim 的回滚令牌仍然有效（记录身份没被改过）");
    }

    @Test
    void 缺失脚本告警按间隔限流() {
        final Fixture fixture = new Fixture();

        assertTrue(fixture.policy.shouldWarnMissingScript("test"), "第一次必须告警");
        assertFalse(fixture.policy.shouldWarnMissingScript("test"), "同一附魔立即重复触发不再刷屏");

        fixture.now.addAndGet(TriggerPolicy.WARN_INTERVAL_MILLIS);

        assertTrue(fixture.policy.shouldWarnMissingScript("test"), "过了间隔放行一次");
        assertTrue(fixture.policy.shouldWarnMissingScript("another"), "不同附魔分别限流");
    }

    @Test
    void 不同类别的告警分别限流() {
        final Fixture fixture = new Fixture();

        assertTrue(fixture.policy.shouldWarn("cross-region:MINE"), "第一次放行");
        assertFalse(fixture.policy.shouldWarn("cross-region:MINE"), "同一键立刻重复不再放行");
        assertTrue(fixture.policy.shouldWarn("missing-script:test"), "不同键互不影响");
    }
}
