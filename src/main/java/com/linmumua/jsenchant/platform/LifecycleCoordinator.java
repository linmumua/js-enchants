package com.linmumua.jsenchant.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 启停协调器：让「装配」与「停用」共用同一条资源清单。
 *
 * <p>为什么需要它：装配不是一次原子操作，而是分步进行的（建脚本引擎池 → 起监听线程 →
 * 注册命令与事件）。停用可能正好落在任意两步之间，最典型的漏法是：停用先把眼下已有的
 * 监听器关掉，装配随后又建了一个——这个新建的没人关，线程会一直活到进程结束。
 *
 * <p>规则只有两条：
 * <ul>
 *   <li>每建一个资源就 {@link #adopt(Runnable)} 登记；登记时若已停用，立刻就地关闭它，
 *       并让调用方停止后续装配步骤。</li>
 *   <li>{@link #stop(Logger)} 关闭全部已登记资源（按登记逆序），可重复调用；之后 adopt
 *       一律当场关闭。</li>
 * </ul>
 *
 * <p>实现刻意不持锁等待：{@code adopt} 只在登记表上做一次极短的加锁，关闭动作永远在锁外，
 * 因此装配侧不会因为停用而阻塞区域线程；{@code stop} 也不等待装配线程结束。
 */
public final class LifecycleCoordinator {

    private final List<Runnable> closers = Collections.synchronizedList(new ArrayList<>());

    private final AtomicBoolean started = new AtomicBoolean();

    private final AtomicBoolean stopped = new AtomicBoolean();

    /** 装配开始：已停用或已开始过时返回 {@code false}（避免装配出第二套监听器）。 */
    public boolean begin() {
        if (this.stopped.get()) {
            return false;
        }

        return this.started.compareAndSet(false, true);
    }

    /**
     * 登记一个刚创建好的资源的关闭动作。
     *
     * @return {@code false} 表示此刻已停用——该资源已被就地关闭，调用方必须停止后续装配步骤
     */
    public boolean adopt(final Runnable closer) {
        if (null == closer) {
            return !this.stopped.get();
        }

        synchronized (this.closers) {
            if (!this.stopped.get()) {
                this.closers.add(closer);

                return true;
            }
        }

        // 已停用（或刚在锁内被停用）：不登记、就地关闭，保证「谁建的谁关」不留孤儿。
        // 关闭动作放在锁外，避免拖住正在收尾的停用线程。
        closeQuietly(closer, null);

        return false;
    }

    /** 是否已停用。 */
    public boolean stopped() {
        return this.stopped.get();
    }

    /**
     * 停用：逆序关闭全部已登记资源。
     *
     * @return 本次调用是否真的执行了关闭（重复调用返回 {@code false}）
     */
    public boolean stop(final Logger logger) {
        if (!this.stopped.compareAndSet(false, true)) {
            return false;
        }

        final List<Runnable> pending;

        synchronized (this.closers) {
            pending = new ArrayList<>(this.closers);
            this.closers.clear();
        }

        for (int index = pending.size() - 1; index >= 0; index--) {
            closeQuietly(pending.get(index), logger);
        }

        return true;
    }

    /** 已登记、尚未关闭的资源数量（诊断/测试用）。 */
    public int size() {
        return this.closers.size();
    }

    private static void closeQuietly(final Runnable closer, final Logger logger) {
        try {
            closer.run();
        } catch (final RuntimeException exception) {
            if (null != logger) {
                logger.log(Level.WARNING, "关闭资源时出错（已继续收尾）。", exception);
            }
        }
    }
}
