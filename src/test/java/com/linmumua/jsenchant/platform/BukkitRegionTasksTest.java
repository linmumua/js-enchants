package com.linmumua.jsenchant.platform;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用假调度器验证适配层的正常行为：谁内联、谁排队、停用后到底还跑不跑。
 *
 * <p>为什么这么做而不是起服务端：这些分支的差别只有「当前线程是否持有该区域」和
 * 「任务是否已被取消」，用真服务端反而测不出来（也很难复现关服那一瞬的窗口）。
 * 假实体在任何状态读取上都会抛错，因此测试同时也钉住了「归属判断不读实体状态」
 * 这条 Folia 硬要求——一旦有人为了判归属去读位置，这里会立刻失败。
 */
class BukkitRegionTasksTest {

    private final FakePlugin plugin = new FakePlugin();

    private final FakeGlobalScheduler global = new FakeGlobalScheduler();

    private final FakeRegionScheduler regions = new FakeRegionScheduler();

    private final FakeOwnership ownership = new FakeOwnership();

    private BukkitRegionTasks tasks() {
        return new BukkitRegionTasks(this.plugin.proxy(), this.global, this.regions, this.ownership);
    }

    @Test
    void 全局任务只提交不内联并在区域线程上执行() {
        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();

        tasks.global(runs::incrementAndGet);

        assertEquals(0, runs.get(), "global 只负责提交，不能就地执行");
        assertEquals(1, this.global.pending(), "任务应已进入全局队列");

        this.global.tick();

        assertEquals(1, runs.get(), "全局任务应在全局区域线程上执行");
    }

    @Test
    void 关闭后不再提交全局任务() {
        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();

        tasks.close();
        tasks.global(runs::incrementAndGet);

        assertEquals(0, this.global.pending(), "停用后不得再向调度器提交任务");

        this.global.tick();

        assertEquals(0, runs.get());
    }

    @Test
    void 关闭时取消已排队的全局任务() {
        final BukkitRegionTasks tasks = this.tasks();

        tasks.global(() -> {
        });

        final FakeTask queued = this.global.lastHandle();

        tasks.close();

        assertTrue(queued.isCancelled(), "停用时必须取消已登记的排队任务");
    }

    @Test
    void 已被取消但开始执行的任务不会跑任务体() {
        // 这一条是「取消」之外的第二道防线：取消发生时任务可能已经进入执行阶段。
        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();

        tasks.global(runs::incrementAndGet);
        tasks.close();

        this.global.forceTick();

        assertEquals(0, runs.get(), "停用后排队任务不得执行");
    }

    @Test
    void 插件停用后视为不活跃() {
        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();

        assertTrue(tasks.active());

        this.plugin.disable();

        assertFalse(tasks.active(), "插件停用后不得再接受任务");

        tasks.global(runs::incrementAndGet);
        this.global.tick();

        assertEquals(0, runs.get());
        assertEquals(0, this.global.pending());
    }

    @Test
    void 已持有实体时同步执行并返回true() {
        this.ownership.ownsEntity(true);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        assertTrue(tasks.entity(fakeEntity(scheduler), runs::incrementAndGet, null));

        assertEquals(1, runs.get(), "已持有该实体时必须就近同步执行");
        assertEquals(0, scheduler.submitted(), "已持有实体时不该再提交调度任务");
    }

    @Test
    void 未持有实体时交给实体自己的调度器() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        assertTrue(tasks.entity(fakeEntity(scheduler), runs::incrementAndGet, null));

        assertEquals(0, runs.get(), "跨区时不得同步执行");
        assertEquals(1, scheduler.submitted(), "跨区时必须交给实体调度器");

        scheduler.tick();

        assertEquals(1, runs.get());
    }

    @Test
    void 实体已退休时返回false且回调只来一次() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger retired = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        scheduler.retiredBeforeSubmit(true);

        assertFalse(tasks.entity(fakeEntity(scheduler), runs::incrementAndGet, retired::incrementAndGet));

        assertEquals(1, retired.get(), "退休回调必须被调用，且不能重复");
        assertEquals(0, runs.get(), "任务不得执行");
        assertEquals(0, tasks.pendingTasks(), "已取消的句柄不该登记进待办");
    }

    @Test
    void 只拿到取消态任务时也会通知且不留待办() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger retired = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        scheduler.cancelledAtSubmit(true);

        assertFalse(tasks.entity(fakeEntity(scheduler), () -> {
        }, retired::incrementAndGet));

        assertEquals(1, retired.get(), "取消态任务必须补齐「没执行」的通知，且只通知一次");
        assertEquals(0, tasks.pendingTasks());
    }

    @Test
    void 提交之后退休会撤销已登记的句柄() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger retired = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        assertTrue(tasks.entity(fakeEntity(scheduler), () -> {
        }, retired::incrementAndGet));

        assertEquals(1, tasks.pendingTasks(), "排队中的实体任务应当被登记");

        scheduler.retire();

        assertEquals(1, retired.get());
        assertEquals(0, tasks.pendingTasks(), "退休之后必须撤销句柄，否则待办只增不减");
    }

    @Test
    void 任务执行完不再占用待办() {
        final BukkitRegionTasks tasks = this.tasks();

        tasks.global(() -> {
        });

        assertEquals(1, tasks.pendingTasks());

        this.global.tick();

        assertEquals(0, tasks.pendingTasks(), "执行完的句柄必须撤销登记");
    }

    @Test
    void 关闭后实体任务不执行但给出退休回调() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger retired = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        tasks.close();

        assertFalse(tasks.entity(fakeEntity(scheduler), runs::incrementAndGet, retired::incrementAndGet));

        assertEquals(0, scheduler.submitted(), "停用后不得再提交实体任务");
        assertEquals(1, retired.get(), "调用方必须得到「没执行」的通知");
        assertEquals(0, runs.get());
    }

    @Test
    void 已持有区域时同步执行() {
        this.ownership.ownsArea(true);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();

        tasks.region(new Location(null, 8.0D, 64.0D, 8.0D), runs::incrementAndGet);

        assertEquals(1, runs.get());
        assertEquals(0, this.regions.submitted(), "已持有该区域时不该再提交调度任务");
    }

    @Test
    void 未持有区域时交给区域调度器() {
        this.ownership.ownsArea(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final Location location = new Location(null, 8.0D, 64.0D, 8.0D);

        tasks.region(location, runs::incrementAndGet);

        assertEquals(0, runs.get(), "跨区时不得同步执行");
        assertEquals(1, this.regions.submitted(), "跨区时必须交给区域调度器");
        assertSame(location, this.regions.lastLocation(), "必须按目标坐标定位区域");

        this.regions.tick();

        assertEquals(1, runs.get());
    }

    @Test
    void 周期任务的零延迟被规范化为一tick() {
        // 线上证据：26.x 的 runAtFixedRate 对 initialDelay<=0 直接抛
        // IllegalArgumentException("Initial delay ticks may not be <= 0")，
        // 周期任务压根没注册，HoverWatcher 因此整条不跑——玩家看不到任何预览。
        // 假调度器照抄了这条校验，所以只要有人把下限退回 0，这里立刻失败。
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        assertTrue(
                tasks.repeat(fakeEntity(scheduler), 0L, 4L, runs::incrementAndGet, null),
                "0 延迟的周期任务必须仍被受理：调用方的原意是「下一 tick 就跑」，适配层负责落地"
        );

        assertEquals(1L, scheduler.repeatDelay(), "首次延迟必须规范化到 1 tick，服务端不接受 0");
        assertEquals(4L, scheduler.repeatPeriod());
        assertEquals(0, runs.get(), "提交不等于执行：周期任务由实体调度器驱动");
    }

    @Test
    void 周期任务的下限同时兜住负延迟与零周期() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        assertTrue(tasks.repeat(fakeEntity(scheduler), -5L, 0L, () -> {
        }, null));

        assertEquals(1L, scheduler.repeatDelay(), "负延迟同样必须是 1 tick");
        assertEquals(1L, scheduler.repeatPeriod(), "0 周期按 1 tick 处理，否则服务端同样拒绝");
    }

    @Test
    void 周期任务每拍都跑且句柄一直留到停用() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        tasks.repeat(fakeEntity(scheduler), 0L, 4L, runs::incrementAndGet, null);

        assertEquals(1, tasks.pendingTasks(), "周期任务的句柄必须登记，否则停用时无法取消");

        scheduler.repeatTick();
        scheduler.repeatTick();

        assertEquals(2, runs.get(), "每个周期都要执行，不是只跑一次");
        assertEquals(1, tasks.pendingTasks(), "跑完一拍不能撤销登记：它还要继续跑");

        tasks.close();

        assertTrue(scheduler.repeatHandle().isCancelled(), "停用时必须取消周期任务");

        scheduler.repeatTick();

        assertEquals(2, runs.get(), "取消之后不得再跑");
    }

    @Test
    void 已取消但这一拍已开始的周期任务不执行任务体() {
        // 周期任务的第二道防线：cancel() 与「本拍已经开始」之间存在窗口。
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        tasks.repeat(fakeEntity(scheduler), 0L, 4L, runs::incrementAndGet, null);
        tasks.close();

        scheduler.forceRepeatTick();

        assertEquals(0, runs.get(), "停用后周期任务体不得执行，哪怕这一拍已经排队");
    }

    @Test
    void 实体已退休时周期任务返回false且回调只来一次() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger retired = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        scheduler.retiredBeforeSubmit(true);

        assertFalse(tasks.repeat(fakeEntity(scheduler), 0L, 4L, runs::incrementAndGet, retired::incrementAndGet));

        assertEquals(1, retired.get(), "周期任务没能挂上必须通知一次，且只通知一次");
        assertEquals(0, runs.get());
        assertEquals(0, tasks.pendingTasks(), "已取消的周期句柄不该登记进待办");
    }

    @Test
    void 关闭后不再提交周期任务并给出退休回调() {
        this.ownership.ownsEntity(false);

        final BukkitRegionTasks tasks = this.tasks();
        final AtomicInteger retired = new AtomicInteger();
        final FakeEntityScheduler scheduler = new FakeEntityScheduler();

        tasks.close();

        assertFalse(tasks.repeat(fakeEntity(scheduler), 0L, 4L, () -> {
        }, retired::incrementAndGet));

        assertEquals(0, scheduler.repeatSubmitted(), "停用后不得再向实体调度器提交周期任务");
        assertEquals(1, retired.get(), "调用方必须得到「没执行」的通知");
    }

    @Test
    void 归属判断只问官方判定且不读实体状态() {
        final BukkitRegionTasks tasks = this.tasks();
        final Entity entity = fakeEntity(new FakeEntityScheduler());
        final Location location = new Location(null, 8.0D, 64.0D, 8.0D);

        assertFalse(tasks.owns(entity));
        assertFalse(tasks.owns(location, 2));

        this.ownership.ownsEntity(true);
        this.ownership.ownsArea(true);

        assertTrue(tasks.owns(entity), "假实体会在任何状态读取上抛错，能走到这里说明只问了归属");
        assertTrue(tasks.owns(location, 2));
    }

    /** 只回答 isEnabled/getLogger：适配层若去碰别的插件状态，这里会直接抛错。 */
    private static final class FakePlugin {

        private final AtomicBoolean enabled = new AtomicBoolean(true);

        private final Logger logger = Logger.getLogger("JsEnchantsTest");

        private final Plugin proxy;

        FakePlugin() {
            this.proxy = (Plugin) Proxy.newProxyInstance(
                    Plugin.class.getClassLoader(),
                    new Class<?>[]{ Plugin.class },
                    (self, method, args) -> switch (method.getName()) {
                        case "isEnabled" -> this.enabled.get();
                        case "getLogger" -> this.logger;
                        case "getName" -> "JsEnchants";
                        case "equals" -> self == args[0];
                        case "hashCode" -> System.identityHashCode(self);
                        case "toString" -> "FakePlugin";
                        default -> throw new UnsupportedOperationException(
                                "适配层不该调用 Plugin#" + method.getName()
                        );
                    }
            );
        }

        Plugin proxy() {
            return this.proxy;
        }

        void disable() {
            this.enabled.set(false);
        }
    }

    /** 假实体：任何状态读取都会抛错，只开放 getScheduler。 */
    private static Entity fakeEntity(final EntityScheduler scheduler) {
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{ Entity.class },
                (self, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "FakeEntity";
                    default -> throw new UnsupportedOperationException(
                            "归属判断不得读取实体状态（被调用的方法: " + method.getName() + "）"
                    );
                }
        );
    }

    private static final class FakeOwnership implements RegionOwnership {

        private final AtomicBoolean entityOwned = new AtomicBoolean();

        private final AtomicBoolean areaOwned = new AtomicBoolean();

        void ownsEntity(final boolean value) {
            this.entityOwned.set(value);
        }

        void ownsArea(final boolean value) {
            this.areaOwned.set(value);
        }

        @Override
        public boolean owns(final Entity entity) {
            return this.entityOwned.get();
        }

        @Override
        public boolean owns(final Location location, final int squareRadiusChunks) {
            return this.areaOwned.get();
        }
    }

    /** 可手动执行一次的假任务句柄。 */
    private static final class FakeTask implements ScheduledTask {

        private final Plugin owner;

        private volatile ExecutionState state = ExecutionState.IDLE;

        FakeTask(final Plugin owner) {
            this.owner = owner;
        }

        @Override
        public Plugin getOwningPlugin() {
            return this.owner;
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        public CancelledState cancel() {
            if (ExecutionState.IDLE != this.state) {
                return CancelledState.ALREADY_EXECUTED;
            }

            this.state = ExecutionState.CANCELLED;

            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public ExecutionState getExecutionState() {
            return this.state;
        }

        void markRunning() {
            this.state = ExecutionState.RUNNING;
        }

        void markFinished() {
            if (ExecutionState.RUNNING == this.state) {
                this.state = ExecutionState.FINISHED;
            }
        }
    }

    private record Queued(FakeTask handle, Consumer<ScheduledTask> body) {
    }

    /**
     * 周期任务句柄：与一次性任务不同，它在每一次执行之后<b>仍然可取消</b>。
     *
     * <p>这条差异必须由假件如实表达：真实实现里周期任务靠主动 {@code cancel()} 停下，
     * 如果假件像一次性任务那样「跑过就不能取消」，停用清理这条路径会被测成通过。
     */
    private static final class FakeRepeatingTask implements ScheduledTask {

        private final Plugin owner;

        private volatile ExecutionState state = ExecutionState.IDLE;

        FakeRepeatingTask(final Plugin owner) {
            this.owner = owner;
        }

        @Override
        public Plugin getOwningPlugin() {
            return this.owner;
        }

        @Override
        public boolean isRepeatingTask() {
            return true;
        }

        @Override
        public CancelledState cancel() {
            if (ExecutionState.CANCELLED == this.state) {
                return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
            }

            this.state = ExecutionState.CANCELLED;

            // 周期任务的取消语义是「后续不再跑」，真实实现返回的就是这一项。
            return CancelledState.NEXT_RUNS_CANCELLED;
        }

        @Override
        public ExecutionState getExecutionState() {
            return this.state;
        }

        /** 覆盖接口的默认实现，语义与真实句柄一致：只有被取消过才算取消。 */
        @Override
        public boolean isCancelled() {
            return ExecutionState.CANCELLED == this.state;
        }
    }

    private static final class FakeGlobalScheduler implements GlobalRegionScheduler {

        private final List<Queued> queued = new ArrayList<>();

        @Override
        public void execute(final Plugin plugin, final Runnable task) {
            throw new UnsupportedOperationException("全局任务必须走可取消的 run(...)，否则停用时无法取消");
        }

        @Override
        public ScheduledTask run(final Plugin plugin, final Consumer<ScheduledTask> task) {
            final FakeTask handle = new FakeTask(plugin);

            this.queued.add(new Queued(handle, task));

            return handle;
        }

        @Override
        public ScheduledTask runDelayed(final Plugin plugin, final Consumer<ScheduledTask> task, final long delay) {
            throw new UnsupportedOperationException("本插件不使用延迟任务");
        }

        @Override
        public ScheduledTask runAtFixedRate(
                final Plugin plugin,
                final Consumer<ScheduledTask> task,
                final long initialDelay,
                final long period
        ) {
            throw new UnsupportedOperationException("本插件不使用重复任务");
        }

        @Override
        public void cancelTasks(final Plugin plugin) {
            // 适配层自己按句柄取消，不依赖整体撤销。
        }

        int pending() {
            return this.queued.size();
        }

        FakeTask lastHandle() {
            return this.queued.get(this.queued.size() - 1).handle();
        }

        /** 模拟区域线程跑一拍：已取消的任务不执行。 */
        void tick() {
            this.drain(true);
        }

        /** 模拟「取消发生时任务已经开始执行」的窗口：无视取消状态照跑。 */
        void forceTick() {
            this.drain(false);
        }

        private void drain(final boolean honorCancellation) {
            final List<Queued> snapshot = new ArrayList<>(this.queued);

            this.queued.clear();

            for (final Queued entry : snapshot) {
                if (honorCancellation && entry.handle().isCancelled()) {
                    continue;
                }

                entry.handle().markRunning();
                entry.body().accept(entry.handle());
                entry.handle().markFinished();
            }
        }
    }

    private static final class FakeEntityScheduler implements EntityScheduler {

        private Runnable pending;
        private Runnable retiredCallback;
        private FakeTask handle;
        private int submitted;
        private boolean retiredBeforeSubmit;
        private boolean cancelledAtSubmit;

        /** 周期任务的登记内容（本插件一条实体只挂一条周期任务，够用且更严格）。 */
        private Consumer<ScheduledTask> repeatBody;
        private FakeRepeatingTask repeatHandle;
        private long repeatDelay;
        private long repeatPeriod;
        private int repeatSubmitted;

        void retiredBeforeSubmit(final boolean value) {
            this.retiredBeforeSubmit = value;
        }

        /** 只给出取消态任务、且不回调 retired：用来覆盖「实现不同步回调」的差异。 */
        void cancelledAtSubmit(final boolean value) {
            this.cancelledAtSubmit = value;
        }

        /** 模拟任务执行前实体被移除。 */
        void retire() {
            final Runnable callback = this.retiredCallback;

            this.retiredCallback = null;
            this.pending = null;

            if (null != callback) {
                callback.run();
            }
        }

        int submitted() {
            return this.submitted;
        }

        @Override
        public boolean execute(
                final Plugin plugin,
                final Runnable task,
                final Runnable retired,
                final long delay
        ) {
            throw new UnsupportedOperationException("实体任务必须走可取消的 run(...)，否则停用时无法取消");
        }

        @Override
        public ScheduledTask run(final Plugin plugin, final Consumer<ScheduledTask> task, final Runnable retired) {
            this.submitted++;
            this.retiredCallback = retired;

            final FakeTask created = new FakeTask(plugin);

            this.handle = created;

            if (this.retiredBeforeSubmit) {
                // 真实实现：实体在提交前就已退休 → 给出取消态任务，并同步回调 retired。
                created.cancel();

                if (null != retired) {
                    retired.run();
                }

                return created;
            }

            if (this.cancelledAtSubmit) {
                // 另一种实现：只给取消态任务，不同步回调。
                created.cancel();

                return created;
            }

            this.pending = () -> task.accept(created);

            return created;
        }

        @Override
        public ScheduledTask runDelayed(
                final Plugin plugin,
                final Consumer<ScheduledTask> task,
                final Runnable retired,
                final long delay
        ) {
            throw new UnsupportedOperationException("本插件不使用延迟任务");
        }

        @Override
        public ScheduledTask runAtFixedRate(
                final Plugin plugin,
                final Consumer<ScheduledTask> task,
                final Runnable retired,
                final long initialDelay,
                final long period
        ) {
            // 复刻 26.x FoliaEntityScheduler 的前置校验：不满足就抛，任务根本不会注册。
            // 线上那次「看不到预览」正是撞在这里，因此这里必须照抄，不能宽容。
            if (initialDelay <= 0L) {
                throw new IllegalArgumentException("Initial delay ticks may not be <= 0");
            }

            if (period <= 0L) {
                throw new IllegalArgumentException("Period ticks may not be <= 0");
            }

            this.repeatSubmitted++;
            this.repeatDelay = initialDelay;
            this.repeatPeriod = period;

            final FakeRepeatingTask created = new FakeRepeatingTask(plugin);

            this.repeatHandle = created;
            this.repeatBody = task;

            if (this.retiredBeforeSubmit) {
                created.cancel();

                if (null != retired) {
                    retired.run();
                }

                return created;
            }

            if (this.cancelledAtSubmit) {
                created.cancel();

                return created;
            }

            return created;
        }

        int repeatSubmitted() {
            return this.repeatSubmitted;
        }

        long repeatDelay() {
            return this.repeatDelay;
        }

        long repeatPeriod() {
            return this.repeatPeriod;
        }

        FakeRepeatingTask repeatHandle() {
            return this.repeatHandle;
        }

        /** 模拟玩家区域线程跑到下一个周期：已取消的任务不执行（真实调度器同样如此）。 */
        void repeatTick() {
            this.repeatDrain(true);
        }

        /** 模拟「取消发生时这一拍已经开始执行」的窗口：无视取消状态照跑。 */
        void forceRepeatTick() {
            this.repeatDrain(false);
        }

        private void repeatDrain(final boolean honorCancellation) {
            if (null == this.repeatBody) {
                return;
            }

            if (honorCancellation && this.repeatHandle.isCancelled()) {
                return;
            }

            this.repeatBody.accept(this.repeatHandle);
        }

        void tick() {
            final Runnable body = this.pending;
            this.pending = null;

            if (null == body) {
                return;
            }

            this.handle.markRunning();
            body.run();
            this.handle.markFinished();
        }
    }

    private static final class FakeRegionScheduler implements RegionScheduler {

        private Runnable pending;
        private FakeTask handle;
        private Location location;
        private int submitted;

        int submitted() {
            return this.submitted;
        }

        Location lastLocation() {
            return this.location;
        }

        @Override
        public void execute(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable task) {
            throw new UnsupportedOperationException("区域任务必须走可取消的 run(...)，否则停用时无法取消");
        }

        @Override
        public ScheduledTask run(
                final Plugin plugin,
                final World world,
                final int chunkX,
                final int chunkZ,
                final Consumer<ScheduledTask> task
        ) {
            throw new UnsupportedOperationException("区域任务应走带 Location 的重载，避免自己换算区块坐标");
        }

        @Override
        public ScheduledTask run(final Plugin plugin, final Location target, final Consumer<ScheduledTask> task) {
            this.submitted++;
            this.location = target;

            final FakeTask created = new FakeTask(plugin);

            this.handle = created;
            this.pending = () -> task.accept(created);

            return created;
        }

        @Override
        public ScheduledTask runDelayed(
                final Plugin plugin,
                final World world,
                final int chunkX,
                final int chunkZ,
                final Consumer<ScheduledTask> task,
                final long delay
        ) {
            throw new UnsupportedOperationException("本插件不使用延迟任务");
        }

        @Override
        public ScheduledTask runAtFixedRate(
                final Plugin plugin,
                final World world,
                final int chunkX,
                final int chunkZ,
                final Consumer<ScheduledTask> task,
                final long initialDelay,
                final long period
        ) {
            throw new UnsupportedOperationException("本插件不使用重复任务");
        }

        void tick() {
            final Runnable body = this.pending;
            this.pending = null;

            if (null == body) {
                return;
            }

            this.handle.markRunning();
            body.run();
            this.handle.markFinished();
        }
    }
}
