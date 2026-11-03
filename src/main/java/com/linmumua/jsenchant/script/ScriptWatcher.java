package com.linmumua.jsenchant.script;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 监听脚本目录，文件保存后延迟去抖再重载。
 *
 * <p>去抖是必须的：编辑器保存一个文件通常会连发多个 {@code ENTRY_MODIFY}，
 * 不去抖会触发多次重复编译。
 *
 * <p>删除事件走同一个去抖窗口，而且到真正执行时才检查文件是否还是真的不在：
 * 编辑器「写临时文件再 rename」的原子保存会先发 DELETE 再发 CREATE，立刻卸载
 * 会让正在用的脚本凭空消失几百毫秒。
 *
 * <p><b>线程模型</b>：监视线程与去抖线程只负责「发现变化」，真正的加载（编译 + 执行顶层语句）
 * 通过 {@code mainThread} 调度到主线程执行。脚本顶层若调用 Bukkit API，在监视线程跑就是错的。
 * 未注入调度器时退化为在原线程内联执行，并在启动时告警——那只适用于单元测试。
 */
public final class ScriptWatcher implements AutoCloseable {

    /** 去抖窗口。足够覆盖编辑器的多次写事件，又不会让人等。 */
    private static final long DEBOUNCE_MILLIS = 300L;

    private final Logger logger;
    private final ScriptLoader loader;
    private final Path scriptDir;

    /** 主线程调度器；{@code null} 表示没有服务端调度器（测试环境）。 */
    private final Consumer<Runnable> mainThread;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "JsEnchants-Watcher");

        thread.setDaemon(true);

        return thread;
    });

    private final Map<Path, Long> pending = new ConcurrentHashMap<>();

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running;

    /**
     * 无主线程调度器的构造，仅供单元测试。
     *
     * <p>生产环境请用带 {@code mainThread} 的版本：内联执行会把脚本顶层跑在监视线程上。
     */
    public ScriptWatcher(
            final Logger logger,
            final ScriptLoader loader,
            final ScriptRegistry registry,
            final Path scriptDir
    ) {
        this(logger, loader, registry, scriptDir, null);
    }

    /**
     * @param registry   脚本表；清理孤儿项由 {@link ScriptLoader#dropOrphans()} 统一处理，
     *                   这里保留入参只为不打断既有调用方
     * @param mainThread 把任务排到主线程的调度器，例如
     *                   {@code task -> Bukkit.getScheduler().runTask(plugin, task)}
     */
    public ScriptWatcher(
            final Logger logger,
            final ScriptLoader loader,
            final ScriptRegistry registry,
            final Path scriptDir,
            final Consumer<Runnable> mainThread
    ) {
        this.logger = logger;
        this.loader = loader;
        this.scriptDir = scriptDir;
        this.mainThread = mainThread;
    }

    public void start() {
        if (this.running) {
            return;
        }

        if (this.scheduler.isShutdown()) {
            this.logger.warning("脚本监听已关闭，无法重新启动: " + this.scriptDir);

            return;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.scriptDir.register(
                    this.watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "无法监听脚本目录，热重载已禁用: " + this.scriptDir, exception);

            return;
        }

        this.running = true;
        this.watchThread = new Thread(this::poll, "JsEnchants-WatchThread");
        this.watchThread.setDaemon(true);
        this.watchThread.start();

        // 已删除的脚本在下次全量加载时不会出现，这里主动清一次。
        // 复用 loader 的清理逻辑，保证手动 /jse reload 与热重载的行为一致。
        this.loader.dropOrphans();

        if (null == this.mainThread) {
            this.logger.warning(
                    "未注入主线程调度器，脚本重载将在监视线程上执行（仅适用于测试）: " + this.scriptDir
            );
        }

        this.logger.info("脚本热重载已启用: " + this.scriptDir);
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

                final Path file = this.scriptDir.resolve((Path) event.context());
                final String name = file.getFileName().toString();

                if (!name.endsWith(".js")) {
                    continue;
                }

                // 删除事件同样走去抖：编辑器「写临时文件再 rename」的原子保存会先发 DELETE，
                // 紧接着再发 CREATE。立刻 unload 会让正在用的脚本凭空消失几百毫秒。
                this.schedule(file);
            }

            if (!key.reset()) {
                break;
            }
        }
    }

    /**
     * 把一次重载动作排到主线程。
     *
     * <p>本方法只排队，不编译、不执行——这正是「脚本顶层 exec 不在 watcher 线程」的落点。
     * 关闭后入队的任务会在执行前自我取消。
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
            // 服务端已在卸载、调度器已关闭：丢弃这一次，不要把它抛回 WatchService 线程。
            if (this.running) {
                this.logger.log(Level.WARNING, "无法调度脚本重载任务，已跳过: " + exception.getMessage());
            }
        }
    }

    /** 同一文件在去抖窗口内只排一次任务。 */
    private void schedule(final Path file) {
        final long now = System.currentTimeMillis();
        final Long previous = this.pending.get(file);

        if (null != previous && now - previous < DEBOUNCE_MILLIS) {
            return;
        }

        this.pending.put(file, now);

        try {
            this.scheduler.schedule(() -> {
                this.pending.remove(file);

                // 存在性检查放到真正执行的那一刻（主线程）：DELETE→CREATE 的短窗口
                // 到这里通常已经结束，看到的是最终状态，而不是中间态。
                this.dispatch(() -> {
                    if (Files.isRegularFile(file)) {
                        this.loader.reload(file);
                    } else {
                        this.loader.unload(idOf(file));
                    }
                });
            }, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException exception) {
            // 已关闭：不留下任何排队的残留任务。
            this.pending.remove(file);
        }
    }

    /** 文件名去掉 .js 作为脚本 id。 */
    private static String idOf(final Path file) {
        final String name = file.getFileName().toString();

        return name.endsWith(".js") ? name.substring(0, name.length() - ".js".length()) : name;
    }

    @Override
    public void close() {
        this.running = false;

        this.scheduler.shutdownNow();
        this.pending.clear();

        final WatchService service = this.watchService;
        this.watchService = null;

        if (null != service) {
            try {
                service.close();
            } catch (final IOException | ClosedWatchServiceException exception) {
                // 已关闭或正在关闭：close() 必须可重复调用。
                this.logger.log(Level.FINE, "关闭脚本监听时出现异常（可忽略）", exception);
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
