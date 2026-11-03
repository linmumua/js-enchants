package com.linmumua.jsenchant.trigger;

import com.linmumua.jsenchant.enchant.EnchantDefinition;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * 一次触发到底该不该执行脚本、执行完之后该不该记账——这些判断集中在这里。
 *
 * <p>之所以从 {@link TriggerDispatcher} 里抽出来：这些规则是纯状态机（概率、时钟、计数），
 * 不需要 Bukkit 就能完整验证，而派发器里混着事件对象，任何规则都只能用服务器实测。
 *
 * <p><b>为什么是「预占」而不是「先查后记」</b>：Folia 上同一名玩家的触发可能从不同区域线程
 * 先后问到同一条规则——攻击在自己的区域判断，击杀在死者的区域判断——「查冷却 → 跑脚本 →
 * 记账」这种三段式在并发下会让同一条冷却被穿过两次。因此判定与记账合并为一次
 * {@link #claim(UUID, EnchantDefinition, int)}：命中时立刻把冷却预占下来，脚本执行成功
 * {@link Claim#commit()}，执行失败 {@link Claim#release()} <b>只回滚自己那一次预占</b>。
 *
 * <p>预占本身在 {@link ConcurrentHashMap#compute} 里完成，但那里只做状态数学：
 * 不碰 Bukkit、不跑脚本、不回调任何外部代码。
 *
 * <p>三条硬规则不变：
 * <ul>
 *   <li>概率 {@code <= 0} 从不执行，{@code >= 1} 必然命中且不消耗随机数；</li>
 *   <li>介于两者之间<b>每次触发只抽一次</b>，未命中就直接不执行脚本——概率由框架强制，
 *       不依赖脚本自觉遵守；</li>
 *   <li>冷却未就绪时不执行脚本、也不刷新冷却；未命中同样不刷新，只有执行成功才记账。</li>
 * </ul>
 */
public final class TriggerPolicy {

    /** 同一类告警的最小间隔，避免每次触发都刷屏。 */
    static final long WARN_INTERVAL_MILLIS = 60_000L;

    /**
     * 到期条目清理的最小间隔。
     *
     * <p>冷却表按「玩家 × 附魔」记账，天然会随玩家数增长；已执行完且早过期的条目留着没有意义，
     * 但每次触发都全表扫一遍是把 O(n) 挂到热路径上。所以低频清理：两条之间至少隔这么久。
     */
    static final long CLEANUP_INTERVAL_MILLIS = 60_000L;

    /** 冷却预占：玩家 + 附魔 id → 预占记录。 */
    private final Map<CooldownKey, Reservation> cooldowns = new ConcurrentHashMap<>();

    /** 下一次允许清理的时间戳；用 CAS 保证同一时刻只有一个线程真的去扫表。 */
    private final AtomicLong nextCleanupAt = new AtomicLong(Long.MIN_VALUE);

    /** 告警限流：告警键 → 上次告警时间戳。 */
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    private final LongSupplier clock;
    private final DoubleSupplier random;

    public TriggerPolicy() {
        this(System::currentTimeMillis, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * 测试用入口：注入可控时钟与随机源。
     *
     * <p>「只抽一次」「冷却到期后恢复」「并发只有一个赢家」这类规则只有能控制这两个来源
     * 才断言得出来。
     */
    public TriggerPolicy(final LongSupplier clock, final DoubleSupplier random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * 对本次触发做一次原子判定：该不该执行、以及执行失败时要回滚的那份预占。
     *
     * <p>顺序是刻意的：先看冷却（便宜、不消耗随机数），再抽概率，最后才预占。
     * 冷却未就绪就抽随机数等于白白扰动概率序列，也让「只抽一次」难以断言。
     *
     * @return 永远非 {@code null}；{@link Claim#granted()} 为假时调用方直接放弃，
     *         不需要也不允许对它调用 commit/release
     */
    public Claim claim(final UUID playerId, final EnchantDefinition definition, final int level) {
        this.cleanupExpiredCooldowns();

        final double chance = definition.chanceAt(level);

        if (0.0D >= chance) {
            return Claim.denied();
        }

        if (!this.cooldownReady(playerId, definition)) {
            return Claim.denied();
        }

        // 概率 1 是确定事件，不该消耗随机数；其余情况一次触发只抽一次。
        if (1.0D > chance && this.random.getAsDouble() >= chance) {
            return Claim.denied();
        }

        final long cooldown = definition.cooldownMillis();

        if (0L >= cooldown) {
            return Claim.grantedWithoutCooldown();
        }

        return this.reserve(playerId, definition, cooldown);
    }

    /** 该玩家此刻是否已脱离这个附魔的冷却。 */
    public boolean cooldownReady(final UUID playerId, final EnchantDefinition definition) {
        if (0L >= definition.cooldownMillis()) {
            return true;
        }

        final Reservation reservation = this.cooldowns.get(new CooldownKey(playerId, definition.id()));

        return null == reservation || this.clock.getAsLong() >= reservation.expiresAt();
    }

    /**
     * 某条告警是否应该真的打出来。
     *
     * <p>缺失脚本、跨区无法重放的触发、被拒绝的爆炸都属于「配置/脚本问题随每次触发重复出现」，
     * 不限流的话一个错配置就能把日志刷满。
     *
     * @param key 告警类别键；不同键分别限流
     * @return 距上次放行已超过间隔（或从未告警）时为 {@code true}
     */
    public boolean shouldWarn(final String key) {
        final long now = this.clock.getAsLong();
        final boolean[] allowed = {false};

        this.lastWarn.compute(key, (ignored, last) -> {
            if (null != last && now - last < WARN_INTERVAL_MILLIS) {
                return last;
            }

            allowed[0] = true;

            return now;
        });

        return allowed[0];
    }

    /** 「附魔引用的脚本不存在」这条告警是否应该真的打出来。 */
    public boolean shouldWarnMissingScript(final String enchantId) {
        return this.shouldWarn("missing-script:" + enchantId);
    }

    /**
     * 当前记账中的冷却条目数（诊断用）。
     *
     * <p>清理是否真的发生只有它看得见——外部没有别的可观测面。
     */
    int trackedCooldowns() {
        return this.cooldowns.size();
    }

    /**
     * 低频清理「已执行完且已过期」的条目。
     *
     * <p>三条硬约束，缺一条都会破坏正确性：
     * <ul>
     *   <li>只删<b>已 commit</b> 的：执行中的预占一旦被删，并发的第二次触发就能赢，
     *       同一条冷却会被穿过两次；</li>
     *   <li>只删<b>已过期</b> 的：没过期就等于还在冷却，删了就是白送一次触发；</li>
     *   <li>绝不碰别人记录的<b>身份</b>：回滚靠「键 + 记录同一性」比对，
     *       清理只做整条删除，不会原地改字段，所以旧 claim 的回滚仍然精确。</li>
     * </ul>
     *
     * <p>未 commit 且已过期的条目这里<b>故意不删</b>：它可能正处在「预占成功、脚本还没跑完」的
     * 极短窗口里。这类条目在下一次同一键的预占里会被覆盖，不构成长期泄漏。
     */
    private void cleanupExpiredCooldowns() {
        final long now = this.clock.getAsLong();
        final long scheduled = this.nextCleanupAt.get();

        if (now < scheduled) {
            return;
        }

        if (!this.nextCleanupAt.compareAndSet(scheduled, now + CLEANUP_INTERVAL_MILLIS)) {
            // 别的线程刚抢到这次清理，不重复扫表。
            return;
        }

        this.cooldowns.entrySet().removeIf(entry -> entry.getValue().isCommitted()
                && now >= entry.getValue().expiresAt());
    }

    /** 预占一份冷却；并发对手已经占住时保持原值并判否。 */
    private Claim reserve(final UUID playerId, final EnchantDefinition definition, final long cooldown) {
        final CooldownKey key = new CooldownKey(playerId, definition.id());
        final Reservation reservation = new Reservation(this.clock.getAsLong() + cooldown);
        final Claim[] winner = {null};

        this.cooldowns.compute(key, (ignored, current) -> {
            if (null != current && this.clock.getAsLong() < current.expiresAt()) {
                // 对手刚刚预占成功：保留它的记录，不动、不覆盖。
                return current;
            }

            winner[0] = new Claim(this, key, reservation, true);

            return reservation;
        });

        return null == winner[0] ? Claim.denied() : winner[0];
    }

    private record CooldownKey(UUID playerId, String enchantId) {
    }

    /**
     * 一份预占记录。
     *
     * <p>为什么不是 record：{@link #isCommitted()} 要能被 {@link Claim#commit()} 翻转，
     * 而 record 是不可变的。字段本身不参与相等性——回滚靠的是<b>对象同一性</b>，
     * 这比比较数值更精确，也让低频清理不可能改变旧 claim 的回滚令牌。
     */
    private static final class Reservation {

        private final long expiresAt;
        private volatile boolean committed;

        private Reservation(final long expiresAt) {
            this.expiresAt = expiresAt;
        }

        private long expiresAt() {
            return this.expiresAt;
        }

        private boolean isCommitted() {
            return this.committed;
        }

        /** 预占转正：脚本真的跑成了。 */
        private void markCommitted() {
            this.committed = true;
        }
    }

    /**
     * 一次判定的结果。
     *
     * <p>生命周期固定为「claim → 执行 → commit/release」：{@link #commit()} 表示脚本真的跑成了，
     * 预占转正；{@link #release()} 表示这一下没跑成（脚本缺失、缺 handler、抛异常），
     * 把<b>自己那次</b>预占撤掉——不是清空整条冷却，因此不会误伤之后别人的成功触发。
     */
    public static final class Claim {

        private final TriggerPolicy policy;
        private final CooldownKey key;
        private final Reservation reservation;
        private final boolean granted;

        private Claim(
                final TriggerPolicy policy,
                final CooldownKey key,
                final Reservation reservation,
                final boolean granted
        ) {
            this.policy = policy;
            this.key = key;
            this.reservation = reservation;
            this.granted = granted;
        }

        private static Claim denied() {
            return new Claim(null, null, null, false);
        }

        private static Claim grantedWithoutCooldown() {
            return new Claim(null, null, null, true);
        }

        public boolean granted() {
            return this.granted;
        }

        /** 声明这次触发真的执行成功了：预占转正，此后 {@link #release()} 不再回滚。 */
        public void commit() {
            if (null != this.reservation) {
                this.reservation.markCommitted();
            }
        }

        /**
         * 回滚自己那次预占。
         *
         * <p>已 commit 的、没有冷却的、以及压根没被授予的 claim 都是空操作。
         * 删除条件是「键 + 记录同一性」，所以只会摘掉本对象写入的那一份；
         * 低频清理也不会改记录本身的身份，迟到或并发的回滚都不会误删别人的预占。
         */
        public void release() {
            if (!this.granted || null == this.reservation || this.reservation.isCommitted()) {
                return;
            }

            this.policy.cooldowns.remove(this.key, this.reservation);
        }
    }
}
