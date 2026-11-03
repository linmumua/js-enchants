package com.linmumua.jsenchant.script;

import com.linmumua.jsenchant.api.ScriptHost;
import com.linmumua.jsenchant.platform.RegionTasks;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ScriptHost} 的生产实现：把「状态键」与「延迟回调」落到本插件手里。
 *
 * <p>一次派发创建一个实例（绑定当次脚本与附魔 id），不跨触发复用。
 *
 * <h2>延迟回调为什么按函数名重查</h2>
 *
 * <p>延迟任务由实体调度器在<b>实体自己当前的区域线程</b>上执行。玩家可能在这段时间里走过了
 * 区域边界，那时执行线程已经不是当初创建 {@code Function} 对象的那条线程。Rhino 的
 * {@code Scriptable}/{@code Function} 是可变对象，跨线程使用正是脚本池一直在防的事。
 *
 * <p>所以这里只保存函数名与不可变参数，执行时由 {@link ScriptEnginePool#invoke} 在
 * <b>执行线程自己的作用域</b>里重新查一次同名函数——这与事件派发走的是同一条路径，
 * 线程语义不需要第二套解释。
 */
public final class ScriptRuntimeHost implements ScriptHost {

    private final Plugin plugin;
    private final String enchantId;
    private final CompiledScript script;
    private final ScriptEnginePool pool;
    private final RegionTasks tasks;
    private final Logger logger;

    public ScriptRuntimeHost(
            final Plugin plugin,
            final String enchantId,
            final CompiledScript script,
            final ScriptEnginePool pool,
            final RegionTasks tasks,
            final Logger logger
    ) {
        this.plugin = plugin;
        this.enchantId = null == enchantId ? "enchant" : enchantId;
        this.script = script;
        this.pool = pool;
        this.tasks = tasks;
        this.logger = logger;
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

        // 空键名（全是非法字符且被替换后仍然为空）在 NamespacedKey 里非法，直接放弃。
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
        if (null == anchor || null == function || null == this.tasks || null == this.script) {
            return false;
        }

        return this.tasks.later(anchor, delayTicks, () -> this.run(function, args), null);
    }

    /**
     * 周期回调。
     *
     * <p>有限次数<b>不</b>用周期任务实现：{@link RegionTasks#repeat} 没有「跑 N 次就停」的
     * 关闭手段（它只能等实体退休或插件停用），次数用完后任务会一直空转。
     * 所以 {@code times > 0} 时改成「每次执行完再排一次延迟」，用完自然停下。
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
        if (null == anchor || null == function || null == this.tasks || null == this.script) {
            return false;
        }

        if (times > 0) {
            return this.scheduleFinite(anchor, delayTicks, periodTicks, new AtomicInteger(times), function, args);
        }

        return this.tasks.repeat(anchor, delayTicks, periodTicks, () -> this.run(function, args), null);
    }

    /** 有限次数的链式调度：一次延迟执行完，把剩下的次数再排一次。 */
    private boolean scheduleFinite(
            final Entity anchor,
            final long delayTicks,
            final long periodTicks,
            final AtomicInteger remaining,
            final String function,
            final Object[] args
    ) {
        if (remaining.get() <= 0) {
            return true;
        }

        return this.tasks.later(anchor, delayTicks, () -> {
            if (remaining.getAndDecrement() <= 0) {
                return;
            }

            this.run(function, args);

            // 第一次之后统一按周期间隔排队，所以递归时把 delay 换成 period。
            this.scheduleFinite(anchor, periodTicks, periodTicks, remaining, function, args);
        }, null);
    }

    /**
     * 执行回调并吞掉脚本异常。
     *
     * <p>与事件派发不同：那边抛错要回滚冷却并让调用方知道「没有执行」；
     * 这里已经没有调用方能接住异常了（任务体在区域线程上跑），只能记日志，
     * 不能让一个坏回调把区域线程的调度链炸掉。
     */
    private void run(final String function, final Object[] args) {
        try {
            this.pool.invoke(this.script, function, args);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.WARNING, "延迟回调执行出错: " + this.script.id() + "#" + function, exception);
        }
    }
}
