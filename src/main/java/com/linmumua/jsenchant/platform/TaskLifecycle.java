package com.linmumua.jsenchant.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 短任务的生命周期守卫：登记 + 退休。
 *
 * <p>为什么需要它：Folia 的调度器是不阻塞的——任务提交出去就返回，真正执行要等所属区域
 * 下一拍。插件停用（{@code /reload}、关服）时那些已排队但还没跑的任务如果照样执行，
 * 就会在「插件已经关掉了」之后去碰已经析构的监听器、脚本引擎和文件句柄。
 * 因此每个任务在执行前都要问一句「现在还允许跑吗」，并且已登记的句柄在停用时一律取消。
 *
 * <p>本类刻意不引用任何 Paper 类型：{@link BukkitRegionTasks} 把调度器返回的句柄当作
 * 不透明 token 交给它，纯逻辑因此可以在没有服务端的测试里验证。
 */
public final class TaskLifecycle {

    private final Map<Object, Runnable> pending = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /** 是否还允许提交/执行任务。 */
    public boolean active() {
        return !this.closed.get();
    }

    /**
     * 登记一个已提交任务的取消句柄。
     *
     * <p>如果登记时已经关闭，句柄会被立刻取消并且不留记录——否则「关闭之后才提交成功」的
     * 任务会永远留在待办里，既不会执行也不会被取消。
     *
     * @param token  任务句柄本体（同一对象必须能原样传给 {@link #release(Object)}）
     * @param cancel 取消动作，通常就是句柄的 {@code cancel()}
     */
    public void track(final Object token, final Runnable cancel) {
        if (null == token || null == cancel) {
            return;
        }

        this.pending.put(token, cancel);

        if (!this.closed.get()) {
            return;
        }

        if (null != this.pending.remove(token)) {
            cancelQuietly(cancel, null);
        }
    }

    /** 任务已经执行完（或被取消）后撤销登记，避免句柄长期堆积。 */
    public void release(final Object token) {
        if (null != token) {
            this.pending.remove(token);
        }
    }

    /** 已登记、尚未撤销的取消句柄数量（诊断/测试用）。 */
    public int pending() {
        return this.pending.size();
    }

    /**
     * 关闭：此后提交的任务不执行，已登记的任务全部取消。可重复调用。
     *
     * @param logger 记录取消失败的日志器，可为 {@code null}
     */
    public void close(final Logger logger) {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        for (final Object token : this.pending.keySet()) {
            final Runnable cancel = this.pending.remove(token);

            if (null != cancel) {
                cancelQuietly(cancel, logger);
            }
        }
    }

    /** 取消失败不该阻断关闭流程，但必须留痕。 */
    private static void cancelQuietly(final Runnable cancel, final Logger logger) {
        try {
            cancel.run();
        } catch (final RuntimeException exception) {
            if (null != logger) {
                logger.log(Level.FINE, "取消排队任务失败（可忽略）", exception);
            }
        }
    }
}
