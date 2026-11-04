package com.linmumua.jsenchant.script;

import com.linmumua.jsenchant.api.ScriptHost;
import com.linmumua.jsenchant.platform.RegionTasks;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ScriptHost} 的生产实现：把「状态键」与「延迟回调」落到本插件手里。
 *
 * <p>一次派发创建一个实例（绑定当次附魔 id 与脚本 id），不跨触发复用；
 * 回调名额 {@link CallbackBudget} 则由派发层持有、跨触发共享。
 *
 * <h2>延迟回调为什么按函数名重查</h2>
 *
 * <p>延迟任务由实体调度器在<b>实体自己当前的区域线程</b>上执行。玩家可能在这段时间里走过了
 * 区域边界，那时执行线程已经不是当初创建 {@code Function} 对象的那条线程。Rhino 的
 * {@code Scriptable}/{@code Function} 是可变对象，跨线程使用正是脚本池一直在防的事。
 *
 * <p>所以这里只保存函数名与不可变参数，执行时由 {@link ScriptEnginePool#invoke} 在
 * <b>执行线程自己的作用域</b>里重新查一次同名函数——这与事件派发走的是同一条路径。
 *
 * <h2>为什么到期时按脚本 id 重新取版本</h2>
 *
 * <p>如果握住派发那一刻的 {@link CompiledScript}，{@code /jse reload} 之后才到期的回调会
 * 用旧源码重建一份作用域、执行旧逻辑，旧作用域还会留在缓存里。到期时向注册表要「当前版本」，
 * 热重载就对已排队的回调同样生效；脚本被删掉时链直接结束并归还名额。
 */
public final class ScriptRuntimeHost implements ScriptHost {

    private final Plugin plugin;
    private final String enchantId;
    private final String scriptId;
    private final Function<String, CompiledScript> scripts;
    private final ScriptEnginePool pool;
    private final RegionTasks tasks;
    private final CallbackBudget budget;
    private final Logger logger;

    public ScriptRuntimeHost(
            final Plugin plugin,
            final String enchantId,
            final String scriptId,
            final Function<String, CompiledScript> scripts,
            final ScriptEnginePool pool,
            final RegionTasks tasks,
            final CallbackBudget budget,
            final Logger logger
    ) {
        this.plugin = plugin;
        this.enchantId = null == enchantId ? "enchant" : enchantId;
        this.scriptId = scriptId;
        this.scripts = scripts;
        this.pool = pool;
        this.tasks = tasks;
        this.budget = null == budget ? new CallbackBudget() : budget;
        this.logger = logger;
    }

    /** 固定脚本版本的便捷构造：只给不涉及热重载的调用方（测试）使用。 */
    public ScriptRuntimeHost(
            final Plugin plugin,
            final String enchantId,
            final CompiledScript script,
            final ScriptEnginePool pool,
            final RegionTasks tasks,
            final Logger logger
    ) {
        this(plugin, enchantId, null == script ? null : script.id(),
                id -> script, pool, tasks, new CallbackBudget(), logger);
    }

    /**
     * 状态键：{@code <附魔 id>.<脚本给的 suffix>}。
     *
     * <p>键必须带附魔 id：两个附魔都用 {@code "counter"} 时不能互相覆盖。
     * {@link NamespacedKey} 只接受小写字母/数字/{@code ._-}，所以这里做一次规整，
     * 而不是把非法字符留给构造器抛错——那会让脚本因为一个中文键名整次触发失败。
     */
    @Override
    public NamespacedKey stateKey(final String suffix) {
        if (null == suffix || suffix.isBlank() || null == this.plugin) {
            return null;
        }

        final String raw = this.enchantId + '.' + suffix;
        final StringBuilder safe = new StringBuilder(raw.length());

        for (int i = 0; i < raw.length(); i++) {
            final char ch = Character.toLowerCase(raw.charAt(i));
            final boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '_' || ch == '-';

            safe.append(allowed ? ch : '_');
        }

        final String key = safe.toString();

        if (key.isBlank()) {
            return null;
        }

        try {
            return new NamespacedKey(this.plugin, key);
        } catch (final IllegalArgumentException exception) {
            this.logger.log(Level.WARNING, "无法为附魔 " + this.enchantId + " 生成状态键: " + key, exception);

            return null;
        }
    }

    @Override
    public boolean later(final Entity anchor, final long delayTicks, final String function, final Object... args) {
        if (!this.ready(anchor, function)) {
            return false;
        }

        final Lease lease = this.lease(anchor);

        if (null == lease) {
            return false;
        }

        final boolean accepted = this.tasks.later(anchor, delayTicks, () -> {
            try {
                this.run(function, args);
            } finally {
                lease.close();
            }
        }, lease::close);

        if (!accepted) {
            lease.close();
        }

        return accepted;
    }

    /**
     * 周期回调。
     *
     * <p>有限次数<b>不</b>用周期任务实现：{@link RegionTasks#repeat} 没有「跑 N 次就停」的
     * 关闭手段（它只能等实体退休或插件停用），次数用完后任务会一直空转。
     * 所以 {@code times > 0} 时改成「每次执行完再排一次延迟」，用完自然停下。
     *
     * <p>整条链只占一个名额：无限周期在实体退休时归还，有限链在最后一次执行后归还。
     */
    @Override
    public boolean repeat(
            final Entity anchor,
            final long delayTicks,
            final long periodTicks,
            final int times,
            final String function,
            final Object... args
    ) {
        if (!this.ready(anchor, function)) {
            return false;
        }

        final Lease lease = this.lease(anchor);

        if (null == lease) {
            return false;
        }

        final boolean accepted = times > 0
                ? this.scheduleFinite(anchor, delayTicks, periodTicks, new AtomicInteger(times), function, args, lease)
                : this.tasks.repeat(anchor, delayTicks, periodTicks, () -> this.run(function, args), lease::close);

        if (!accepted) {
            lease.close();
        }

        return accepted;
    }

    /** 有限次数的链式调度：一次延迟执行完，把剩下的次数再排一次。 */
    private boolean scheduleFinite(
            final Entity anchor,
            final long delayTicks,
            final long periodTicks,
            final AtomicInteger remaining,
            final String function,
            final Object[] args,
            final Lease lease
    ) {
        return this.tasks.later(anchor, delayTicks, () -> {
            if (remaining.getAndDecrement() <= 0 || !this.run(function, args) || remaining.get() <= 0) {
                lease.close();

                return;
            }

            // 第一次之后统一按周期间隔排队，所以递归时把 delay 换成 period。
            if (!this.scheduleFinite(anchor, periodTicks, periodTicks, remaining, function, args, lease)) {
                lease.close();
            }
        }, lease::close);
    }

    private boolean ready(final Entity anchor, final String function) {
        return null != anchor && null != function && null != this.tasks
                && null != this.scriptId && null != this.scripts;
    }

    /** 占名额；满了记一条（限流由调用方的返回值体现，这里只在 debug 级别记录）。 */
    private Lease lease(final Entity anchor) {
        final UUID id = anchor.getUniqueId();

        if (!this.budget.acquire(id, this.enchantId)) {
            this.logger.fine("附魔 " + this.enchantId + " 的回调已达上限 " + this.budget.limit() + "，本次拒绝");

            return null;
        }

        return new Lease(this.budget, id, this.enchantId);
    }

    /**
     * 执行回调并吞掉脚本异常。
     *
     * <p>这里已经没有调用方能接住异常了（任务体在区域线程上跑），只能记日志，
     * 不能让一个坏回调把区域线程的调度链炸掉。
     *
     * @return 脚本仍然存在（链可以继续）；脚本已被删除时返回 {@code false}
     */
    private boolean run(final String function, final Object[] args) {
        final CompiledScript current = this.scripts.apply(this.scriptId);

        if (null == current) {
            return false;
        }

        try {
            this.pool.invoke(current, function, args);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.WARNING, "延迟回调执行出错: " + this.scriptId + "#" + function, exception);
        }

        return true;
    }

    /** 一条回调链持有的名额；可重复 close，只归还一次。 */
    private static final class Lease {

        private final CallbackBudget budget;
        private final UUID anchor;
        private final String enchantId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(final CallbackBudget budget, final UUID anchor, final String enchantId) {
            this.budget = budget;
            this.anchor = anchor;
            this.enchantId = enchantId;
        }

        void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.budget.release(this.anchor, this.enchantId);
            }
        }
    }
}
