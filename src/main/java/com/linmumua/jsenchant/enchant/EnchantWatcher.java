package com.linmumua.jsenchant.enchant;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 监听附魔定义目录，改动后延迟去抖再整表重载。
 *
 * <p>与脚本层分开是因为语义不同：脚本按 id 增量替换，附魔定义则整表重建——
 * 定义数量少，重建比维护增量状态更不容易出错。
 *
 * <p><b>线程模型</b>：监视线程只负责「发现变化」，整表重载通过 {@code mainThread}
 * 排到主线程执行。定义表本身是 volatile 快照替换、读侧无锁，但在主线程重建能保证
 * 与命令、事件、脚本执行之间没有意外的可见性顺序问题，也让重载日志归到主线程。
 */
public final class EnchantWatcher implements AutoCloseable {

    private static final long DEBOUNCE_MILLIS = 300L;

    private final Logger logger;
    private final EnchantRegistry registry;
    private final Path directory;

    /** 主线程调度器；{@code null} 表示没有服务端调度器（测试环境）。 */
    private final Consumer<Runnable> mainThread;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "JsEnchants-EnchantWatcher");

        thread.setDaemon(true);

        return thread;
    });

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running;

    /** 是否已有一个去抖中的整表重载在排队，避免一次保存触发多次重载。 */
    private final AtomicBoolean reloadQueued = new AtomicBoolean();

    /**
     * 无主线程调度器的构造，仅供单元测试。
     *
     * <p>生产环境请用带 {@code mainThread} 的版本。
     */
    public EnchantWatcher(final Logger logger, final EnchantRegistry registry, final Path directory) {
        this(logger, registry, directory, null);
    }

    /**
     * @param mainThread 把任务排到主线程的调度器，例如
     *                   {@code task -> Bukkit.getScheduler().runTask(owner, task)}
     */
    public EnchantWatcher(
            final Logger logger,
            final EnchantRegistry registry,
            final Path directory,
            final Consumer<Runnable> mainThread
    ) {
        this.logger = logger;
        this.registry = registry;
        this.directory = directory;
        this.mainThread = mainThread;
    }

    public void start() {
        if (this.running) {
            return;
        }

        if (this.scheduler.isShutdown()) {
            this.logger.warning("附魔监听已关闭，无法重新启动: " + this.directory);

            return;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.directory.register(
                    this.watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "无法监听附魔目录，定义热重载已禁用: " + this.directory, exception);

            return;
        }

        this.running = true;
        this.watchThread = new Thread(this::poll, "JsEnchants-EnchantWatchThread");
        this.watchThread.setDaemon(true);
        this.watchThread.start();

        if (null == this.mainThread) {
            this.logger.warning(
                    "未注入主线程调度器，附魔重载将在监视线程上执行（仅适用于测试）: " + this.directory
            );
        }

        this.logger.info("附魔定义热重载已启用: " + this.directory);
    }

    private void poll() {
        // 先取本地引用：close() 会把字段置空，循环里直接读字段会撞上 NPE。
        final WatchService service = this.watchService;

        if (null == service) {
            return;
        }

        while (this.running) {
            final WatchKey key;

            try {
                key = service.take();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();

                return;
            } catch (final ClosedWatchServiceException exception) {
                return;
            }

            for (final WatchEvent<?> event : key.pollEvents()) {
                if (StandardWatchEventKinds.OVERFLOW == event.kind()) {
                    continue;
                }

                final String name = ((Path) event.context()).getFileName().toString();

                if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                    continue;
                }

                // 整表重载，所以同一批写事件只需排一次。
                this.scheduleReload();
            }

            if (!key.reset()) {
                break;
            }
        }
    }

    /** 去抖后把整表重载排到主线程；同一批写事件只排一次。 */
    private void scheduleReload() {
        if (!this.running) {
            return;
        }

        if (!this.reloadQueued.compareAndSet(false, true)) {
            return;
        }

        try {
            this.scheduler.schedule(
                    () -> {
                        this.reloadQueued.set(false);

                        this.dispatch(this::reload);
                    },
                    DEBOUNCE_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        } catch (final RejectedExecutionException exception) {
            // 已关闭：不留下任何排队的残留任务。
            this.reloadQueued.set(false);

            this.logger.log(Level.FINE, "附魔监听已关闭，丢弃本次重载", exception);
        }
    }

    /**
     * 把一次整表重载排到主线程；关闭后入队的任务会在执行前自我取消。
     */
    private void dispatch(final Runnable action) {
        final Runnable guarded = () -> {
            if (!this.running) {
                return;
            }

            action.run();
        };

        if (null == this.mainThread) {
            guarded.run();

            return;
        }

        try {
            this.mainThread.accept(guarded);
        } catch (final RuntimeException exception) {
            if (this.running) {
                this.logger.log(Level.WARNING, "无法调度附魔重载任务，已跳过: " + exception.getMessage());
            }
        }
    }

    private void reload() {
        final EnchantLoadReport report = this.registry.reload(this.directory);

        if (!report.directoryReady()) {
            this.logger.severe("附魔目录不可用，已保留上一份定义（" + report.loaded() + " 个）: " + this.directory);

            return;
        }

        if (!report.published()) {
            // 具体是哪些文件、为什么失败，注册表已经记了 SEVERE，这里只补充「当前处在什么状态」。
            if (0 == report.loaded()) {
                this.logger.severe("附魔定义未发布，当前没有任何可用定义: " + this.directory);
            } else {
                this.logger.warning("附魔定义未发布，继续使用上一份定义（" + report.loaded() + " 个）: " + this.directory);
            }

            return;
        }

        this.logger.info("附魔定义已重载，共 " + report.loaded() + " 个。");
    }

    @Override
    public void close() {
        this.running = false;

        this.scheduler.shutdownNow();

        final WatchService service = this.watchService;
        this.watchService = null;

        if (null != service) {
            try {
                service.close();
            } catch (final IOException | ClosedWatchServiceException exception) {
                // 已关闭或正在关闭：close() 必须可重复调用。
                this.logger.log(Level.FINE, "关闭附魔监听时出现异常（可忽略）", exception);
            }
        }

        final Thread thread = this.watchThread;
        this.watchThread = null;

        if (null != thread) {
            // 只中断、不 join：从主线程关闭时任何等待都可能卡住关服。
            thread.interrupt();
        }
    }
}
