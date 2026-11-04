package com.linmumua.jsenchant.script;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本延迟/周期回调的存活名额：按「锚点实体 + 附魔 id」计数。
 *
 * <p>为什么需要它：脚本在 {@code onTrigger} 里调 {@code ctx.repeat(20, 0, "tick")}，
 * 玩家每攻击一次就多挂一条永不停止的任务。没有上限时几百次攻击后同一名玩家身上就有几百条任务
 * 在空转。这里给每个「玩家 × 附魔」一个固定名额，满了就拒绝新的回调（{@code ctx.delay/repeat}
 * 返回 {@code false}），已经在跑的不受影响。
 *
 * <p>一条回调链（一次 {@code delay}，或一次 {@code repeat} 的全部次数）只占一个名额，
 * 链结束（次数用完、脚本被删、实体退休、插件停用）时归还。
 *
 * <p>线程语义：计数器是原子的，区域线程之间并发占用/归还不需要加锁。
 */
public final class CallbackBudget {

    /** 每名玩家每个附魔同时存活的回调链上限。 */
    public static final int DEFAULT_LIMIT = 16;

    private final int limit;
    private final Map<String, AtomicInteger> live = new ConcurrentHashMap<>();

    public CallbackBudget() {
        this(DEFAULT_LIMIT);
    }

    public CallbackBudget(final int limit) {
        this.limit = Math.max(1, limit);
    }

    /** 占一个名额；已满时返回 {@code false}，计数不变。 */
    public boolean acquire(final UUID anchor, final String enchantId) {
        final AtomicInteger counter = this.live.computeIfAbsent(key(anchor, enchantId), ignored -> new AtomicInteger());

        while (true) {
            final int current = counter.get();

            if (current >= this.limit) {
                return false;
            }

            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 归还一个名额；归零时移除条目，避免下线玩家的键一直留在表里。 */
    public void release(final UUID anchor, final String enchantId) {
        final String key = key(anchor, enchantId);

        this.live.computeIfPresent(key, (ignored, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    }

    /** 当前存活的回调链数量（测试与诊断用）。 */
    public int live(final UUID anchor, final String enchantId) {
        final AtomicInteger counter = this.live.get(key(anchor, enchantId));

        return null == counter ? 0 : counter.get();
    }

    public int limit() {
        return this.limit;
    }

    private static String key(final UUID anchor, final String enchantId) {
        return anchor + "|" + enchantId;
    }
}
