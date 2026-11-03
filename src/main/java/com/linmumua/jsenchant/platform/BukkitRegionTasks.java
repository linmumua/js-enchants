package com.linmumua.jsenchant.platform;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link RegionTasks} 的最小 Bukkit 适配：全部委托给服务端自带的区域化调度器。
 *
 * <p>为什么连 Paper 也用这套：26.x 的 Paper 同样提供
 * {@link GlobalRegionScheduler}/{@link RegionScheduler}/{@link EntityScheduler}，
 * 内部退化成主线程调度。用同一套接口意味着插件里不存在「Folia 分支」和「Paper 分支」
 * 两份代码，也就不会出现只在其中一种服务端上才复现的调度错位。
 *
 * <p><b>不阻塞</b>：这里只提交任务，从不等待结果。需要「做完之后再通知」的场景，
 * 由调用方在任务体里继续提交下一个任务（见 {@code JsEnchantPlugin#reloadAsync}）。
 *
 * <p><b>退休</b>：停用后 {@link #close()} 会取消所有登记过、尚未执行的句柄；任务体第一行
 * 也会再检查一次生命周期，双保险覆盖「取消时已经开始执行」的窗口。
 */
public final class BukkitRegionTasks implements RegionTasks {

    private final Plugin plugin;

    private final GlobalRegionScheduler globalScheduler;
    private final RegionScheduler regionScheduler;
    private final RegionOwnership ownership;

    private final TaskLifecycle lifecycle = new TaskLifecycle();

    public BukkitRegionTasks(final Plugin plugin) {
        this(plugin, Bukkit.getGlobalRegionScheduler(), Bukkit.getRegionScheduler(), RegionOwnership.BUKKIT);
    }

    /**
     * 测试用构造：调度器与归属判定都可替换，因此不需要真的起一个服务端。
     * 生产环境请用 {@link #BukkitRegionTasks(Plugin)}。
     */
    BukkitRegionTasks(
            final Plugin plugin,
            final GlobalRegionScheduler globalScheduler,
            final RegionScheduler regionScheduler,
            final RegionOwnership ownership
    ) {
        this.plugin = plugin;
        this.globalScheduler = globalScheduler;
        this.regionScheduler = regionScheduler;
        this.ownership = ownership;
    }

    @Override
    public void global(final Runnable task) {
        if (!this.active()) {
            return;
        }

        try {
            final ScheduledTask scheduled = this.globalScheduler.run(this.plugin, given -> {
                this.lifecycle.release(given);
                this.run(task);
            });

            this.track(scheduled);
        } catch (final RuntimeException exception) {
            this.warn("无法调度全局任务", exception);
        }
    }

    @Override
    public boolean entity(final Entity entity, final Runnable task, final Runnable retired) {
        // 「没执行」的唯一通知出口：实体退休、调度被拒、任务被取消都从这里出，且只出一次，
        // 因此调用方不需要再为 false 单独发一条提示（否则会收到两条）。
        final AtomicBoolean accepted = new AtomicBoolean(true);
        final AtomicBoolean notified = new AtomicBoolean();

        // 句柄要等 scheduler.run(...) 返回才拿得到，而退休回调可能在返回之前（实体提交前就已退休）
        // 或返回之后才来。用可变引用让 notRun 无论先来后到都能撤掉已登记的句柄，不留残留。
        final AtomicReference<ScheduledTask> handle = new AtomicReference<>();

        final Runnable notRun = () -> {
            accepted.set(false);

            final ScheduledTask created = handle.get();

            if (null != created) {
                this.lifecycle.release(created);
            }

            if (notified.compareAndSet(false, true) && null != retired) {
                this.runQuietly(retired, "实体任务未执行的回调出错");
            }
        };

        if (!this.active()) {
            notRun.run();

            return false;
        }

        // 归属判定只用官方接口，不去读他区的实体状态（位置、是否有效等）。
        if (this.ownership.owns(entity)) {
            this.run(task);

            return true;
        }

        final EntityScheduler scheduler;

        try {
            scheduler = entity.getScheduler();
        } catch (final RuntimeException exception) {
            this.warn("无法取得实体调度器", exception);
            notRun.run();

            return false;
        }

        try {
            final ScheduledTask scheduled = scheduler.run(
                    this.plugin,
                    given -> {
                        this.lifecycle.release(given);
                        this.run(task);
                    },
                    notRun
            );

            handle.set(scheduled);
            this.track(scheduled);

            if (scheduled.isCancelled()) {
                // 已退休的实体可能只得到一个取消态任务、不同步回调 retired，这里补齐通知
                // （notRun 幂等：句柄已被 track 判定为取消态而不登记，release 也就无可撤销）。
                notRun.run();
            }
        } catch (final RuntimeException exception) {
            this.warn("无法调度实体任务", exception);
            notRun.run();
        }

        return accepted.get();
    }

    @Override
    public void region(final Location location, final Runnable task) {
        if (!this.active()) {
            return;
        }

        if (this.ownership.owns(location, 0)) {
            this.run(task);

            return;
        }

        try {
            final ScheduledTask scheduled = this.regionScheduler.run(this.plugin, location, given -> {
                this.lifecycle.release(given);
                this.run(task);
            });

            this.track(scheduled);
        } catch (final RuntimeException exception) {
            this.warn("无法调度区域任务", exception);
        }
    }

    @Override
    public boolean repeat(
            final Entity entity,
            final long delayTicks,
            final long periodTicks,
            final Runnable task,
            final Runnable retired
    ) {
        // 与 entity(...) 同一套「没执行」语义：只要走了 notRun（停用、退休、调度被拒、拿到取消态句柄），
        // 就必须返回 false。否则调用方看到 true 会以为观察已经挂上，实际一拍都不会跑——
        // 接口注释承诺的正是「false = 没有执行，且 retired 已被调用」。
        final AtomicBoolean accepted = new AtomicBoolean(true);
        final AtomicBoolean notified = new AtomicBoolean();

        final Runnable notRun = () -> {
            accepted.set(false);

            if (notified.compareAndSet(false, true) && null != retired) {
                this.runQuietly(retired, "周期任务未执行的回调出错");
            }
        };

        if (!this.active()) {
            notRun.run();

            return false;
        }

        try {
            final EntityScheduler scheduler = entity.getScheduler();

            // 周期任务不在这里 release 句柄：它要一直跑下去，句柄必须留到 close() 时统一取消。
            // 每次执行前都会重新确认生命周期（见 run），停用后余下的周期自然空转然后就绪取消。
            //
            // 首次延迟必须 >= 1：26.x 的 FoliaEntityScheduler 对 initialDelay <= 0 直接抛
            // IllegalArgumentException("Initial delay ticks may not be <= 0")，任务根本不会注册。
            // 调用方写 0 的原意是「尽快开始」，所以按 1 tick 落地——它仍然是「下一个 tick 就跑」，
            // 只是把「立刻」交给服务端能接受的最小值表达。这条一旦退回 0，
            // 依赖它的周期任务（如 HoverWatcher 的悬停预览）会整体静默失效。
            final ScheduledTask scheduled = scheduler.runAtFixedRate(
                    this.plugin,
                    given -> this.run(task),
                    notRun,
                    Math.max(1L, delayTicks),
                    Math.max(1L, periodTicks)
            );

            this.track(scheduled);

            if (scheduled.isCancelled()) {
                // 实体在提交前就已退休时，服务端可能只给一个取消态句柄而不回调 retired：这里补齐。
                notRun.run();
            }

            return accepted.get();
        } catch (final RuntimeException exception) {
            this.warn("无法调度周期任务", exception);
            notRun.run();

            return false;
        }
    }

    @Override
    public boolean later(final Entity entity, final long delayTicks, final Runnable task, final Runnable retired) {
        final AtomicBoolean accepted = new AtomicBoolean(true);
        final AtomicBoolean notified = new AtomicBoolean();

        final Runnable notRun = () -> {
            accepted.set(false);

            if (notified.compareAndSet(false, true) && null != retired) {
                this.runQuietly(retired, "延迟任务未执行的回调出错");
            }
        };

        if (!this.active()) {
            notRun.run();

            return false;
        }

        try {
            final EntityScheduler scheduler = entity.getScheduler();

            // 一次性任务：执行前先把它自己的句柄从登记表里摘掉，否则它会一直留到 close()。
            final ScheduledTask scheduled = scheduler.runDelayed(
                    this.plugin,
                    given -> {
                        this.lifecycle.release(given);
                        this.run(task);
                    },
                    notRun,
                    Math.max(1L, delayTicks)
            );

            this.track(scheduled);

            if (scheduled.isCancelled()) {
                notRun.run();
            }

            return accepted.get();
        } catch (final RuntimeException exception) {
            this.warn("无法调度延迟任务", exception);
            notRun.run();

            return false;
        }
    }

    @Override
    public boolean owns(final Entity entity) {
        return this.ownership.owns(entity);
    }

    @Override
    public boolean owns(final Location location, final int squareRadiusChunks) {
        return this.ownership.owns(location, squareRadiusChunks);
    }

    @Override
    public boolean active() {
        return this.lifecycle.active() && this.plugin.isEnabled();
    }

    @Override
    public void close() {
        this.lifecycle.close(this.logger());
    }

    /**
     * 登记已提交任务的取消句柄。
     *
     * <p>只登记仍是 {@code IDLE} 的句柄：提交即已执行完（Folia 上在全局线程里提交全局任务会
     * 立刻执行）或已取消（实体在提交前就退休）的任务都没什么可取消的，登记进来只会变成
     * 永远撤不掉的残留——退休回调可能早于本方法拿到句柄，正是靠这条判断兜住。
     */
    private void track(final ScheduledTask scheduled) {
        if (ScheduledTask.ExecutionState.IDLE != scheduled.getExecutionState()) {
            return;
        }

        this.lifecycle.track(scheduled, scheduled::cancel);
    }

    /** 待取消句柄数量（诊断/测试用）。 */
    int pendingTasks() {
        return this.lifecycle.pending();
    }

    /** 任务体统一入口：执行前再确认一次生命周期，异常只记录不外抛（外抛会打断区域 tick）。 */
    private void run(final Runnable task) {
        if (!this.lifecycle.active()) {
            return;
        }

        try {
            task.run();
        } catch (final RuntimeException exception) {
            this.warn("任务执行出错", exception);
        }
    }

    private void runQuietly(final Runnable action, final String message) {
        try {
            action.run();
        } catch (final RuntimeException exception) {
            this.warn(message, exception);
        }
    }

    private void warn(final String message, final RuntimeException exception) {
        this.logger().log(Level.WARNING, "[JsEnchants] " + message, exception);
    }

    private Logger logger() {
        final Logger logger = this.plugin.getLogger();

        return null == logger ? Logger.getLogger("JsEnchants") : logger;
    }
}
