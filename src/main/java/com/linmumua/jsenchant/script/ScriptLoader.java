package com.linmumua.jsenchant.script;

import org.mozilla.javascript.RhinoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 脚本加载与热替换。
 *
 * <p>核心约定：<b>编译失败绝不清空旧版本</b>。改崩一次脚本不应该让整套附魔失效，
 * 这是热重载最容易踩的坑。
 *
 * <p>第二个约定：<b>失败要能被调用方看到</b>。{@link #loadAll()} 只给数量，
 * {@link #reloadAll()} 额外带回每个文件的失败原因，供 {@code /jse reload} 如实报告。
 *
 * <p>第三个约定：<b>发布物只有不可变源码与版本号</b>。加载过程绝不产出可被多个线程共享的
 * 作用域：校验用一个独立临时作用域（用完即弃），执行线程的运行态由
 * {@link ScriptEnginePool} 各自建立。版本号只在源码真的变了时才递增，未变化的重载因此不会
 * 让各线程重建运行态，也不会每 reload 一次就多堆一个版本。
 *
 * <p>本类不依赖 Bukkit，便于脱离服务端做单元测试；也正因如此，线程约束由调用方保证：
 * 加载会执行脚本顶层语句（在临时作用域里校验），应排在服务端调度线程上执行。
 */
public final class ScriptLoader {

    private final ScriptEnginePool pool;
    private final ScriptRegistry registry;
    private final Logger logger;
    private final Path scriptDir;

    public ScriptLoader(
            final ScriptEnginePool pool,
            final ScriptRegistry registry,
            final Logger logger,
            final Path scriptDir
    ) {
        this.pool = pool;
        this.registry = registry;
        this.logger = logger;
        this.scriptDir = scriptDir;
    }

    /**
     * 全量加载。目录不存在时自动创建。
     *
     * @return 成功加载的脚本数量；需要失败明细请用 {@link #reloadAll()}
     */
    public int loadAll() {
        return this.reloadAll().loaded();
    }

    /**
     * 全量加载，并如实报告每个失败文件。
     *
     * <p>丢掉的错误必须回到调用方：{@code /jse reload} 只有拿到明细才能说清
     * 「哪些脚本没换成」，否则编译失败会被显示成一次成功的重载。
     *
     * <p>全量加载同时负责<b>清理已删除的脚本</b>（{@link #dropOrphans()}）：
     * 手动 {@code /jse reload} 不能依赖热重载监听器在场，否则删掉的文件会一直留在注册表里。
     *
     * <p>加载只做「读文件 → 临时作用域校验 → 发布不可变源码」，不改写任何执行线程的运行态，
     * 但调度责任仍在调用方：无必要不要放在事件线程上跑。
     */
    public ScriptLoadReport reloadAll() {
        try {
            Files.createDirectories(this.scriptDir);
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "无法创建脚本目录: " + this.scriptDir, exception);

            return new ScriptLoadReport(0, List.of("无法创建目录: " + exception.getMessage()), false);
        }

        final List<Path> files = this.listScripts();

        if (null == files) {
            return new ScriptLoadReport(0, List.of("无法列举目录"), false);
        }

        int loaded = 0;
        final List<String> failures = new ArrayList<>();

        for (final Path file : files) {
            // 列举之后被删掉：这是正常删除，交给 dropOrphans 处理，不算「编译失败」。
            if (!Files.isRegularFile(file)) {
                continue;
            }

            final FileResult result = this.load(file);

            if (result.ok()) {
                loaded++;
            } else {
                failures.add(file.getFileName() + ": " + result.error());
            }
        }

        this.dropOrphans();

        return new ScriptLoadReport(loaded, failures, true);
    }

    /**
     * 清掉磁盘上已不存在的脚本。
     *
     * <p>手动重载与热重载共用这一条清理路径，行为才不会分叉。
     * 目录不可列举时<b>保留</b>注册表现有内容——读不到目录不等于文件没了。
     *
     * @return 被移除的脚本数量
     */
    public int dropOrphans() {
        final List<Path> files = this.listScripts();

        if (null == files) {
            this.logger.warning("无法列举脚本目录，已保留现有脚本: " + this.scriptDir);

            return 0;
        }

        final Set<String> present = new HashSet<>();

        for (final Path file : files) {
            final String id = idOf(file);

            if (null != id) {
                present.add(id);
            }
        }

        int removed = 0;

        // 复制一份再遍历：ids() 是注册表键集的视图，边遍历边删会踩到弱一致性。
        for (final String id : new HashSet<>(this.registry.ids())) {
            if (present.contains(id)) {
                continue;
            }

            if (null != this.registry.remove(id)) {
                // 脚本没了，它在各线程上的运行态也必须跟着消失，否则会一直挂到插件停用。
                this.pool.discard(id);

                this.logger.info("脚本文件已不存在，已移除: " + id);

                removed++;
            }
        }

        return removed;
    }

    /**
     * 加载或热替换单个脚本文件。
     *
     * @return 成功编译并替换时返回 {@code true}；失败时旧版本保持不变并返回 {@code false}
     */
    public boolean reload(final Path file) {
        return this.load(file).ok();
    }

    /**
     * 真正的加载逻辑。任何失败都不写注册表，因此旧版本天然保留。
     */
    private FileResult load(final Path file) {
        final String id = idOf(file);

        if (null == id) {
            return new FileResult(false, "文件名不是 *.js");
        }

        final String source;
        final long lastModified;

        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "读取脚本失败: " + file, exception);

            return new FileResult(false, "读取失败: " + exception.getMessage());
        }

        try {
            final CompiledScript script = this.compile(id, source, lastModified);

            this.registry.put(script);

            // 新版本已生效：淘汰该脚本旧版本的线程运行态。在途调用握着旧引用，会正常跑完。
            this.pool.publish(script);

            this.logger.info(
                    "脚本已加载: " + file.getFileName() + " (" + id + "，版本 " + script.version() + ")"
            );

            return new FileResult(true, null);
        } catch (final Exception exception) {
            final boolean hasPrevious = null != this.registry.get(id);
            final String reason = describe(exception);

            this.logger.log(
                    Level.SEVERE,
                    "脚本编译失败，"
                            + (hasPrevious
                                    ? "已保留上一可用版本（脚本池以解释模式运行，无需重新生成字节码）: "
                                    : "该脚本当前没有可用版本，对应附魔不会触发: ")
                            + file.getFileName() + " — " + reason,
                    exception
            );

            return new FileResult(false, reason);
        }
    }

    /** 删除已不存在的脚本。 */
    public void unload(final String id) {
        if (null != this.registry.remove(id)) {
            // 与 dropOrphans 同样：脚本消失时连带清掉各线程的运行态。
            this.pool.discard(id);

            this.logger.info("脚本已移除: " + id);
        }
    }

    /**
     * 把源码变成可发布的脚本版本。
     *
     * <p><b>先验证再发布</b>：这里用一个<b>独立的临时作用域</b>执行一遍顶层语句，确认脚本
     * 真的能跑起来（语法对、但顶层一执行就抛错的脚本同样算加载失败）。临时作用域用完即弃，
     * 绝不放进任何线程的运行态——否则就等于把同一份可变作用域摊给多个 region 线程。
     *
     * <p>版本号只在源码真的变化时递增，见 {@link #versionOf}。
     */
    private CompiledScript compile(final String id, final String source, final long lastModified) {
        final CompiledScript previous = this.registry.get(id);
        final CompiledScript candidate = new CompiledScript(id, source, versionOf(previous, source), lastModified);

        this.pool.withContext(context -> {
            ScriptEnginePool.scopeOf(context, candidate, this.logger);

            return Boolean.TRUE;
        });

        return candidate;
    }

    /**
     * 版本标识：内容变了才 +1。
     *
     * <p>内容没变的重载沿用旧版本号，执行线程的运行态键（线程 + id + 版本）因此不变，
     * 不会每次 reload 都重建作用域、也不会把版本号越堆越高。
     */
    private static long versionOf(final CompiledScript previous, final String source) {
        if (null == previous) {
            return 1L;
        }

        return previous.source().equals(source) ? previous.version() : previous.version() + 1L;
    }

    /**
     * @return 目录里的脚本文件；列举失败时返回 {@code null}（与「目录为空」区分开）
     */
    private List<Path> listScripts() {
        try (final Stream<Path> stream = Files.list(this.scriptDir)) {
            final List<Path> files = new ArrayList<>();

            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".js"))
                    // 固定按文件名排序：失败明细与日志顺序才是确定的，便于比对两次重载的差异。
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);

            return files;
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "列举脚本目录失败: " + this.scriptDir, exception);

            return null;
        }
    }

    /**
     * 把编译/执行异常整理成「第 N 行: 原因」。
     *
     * <p>不带行号的「syntax error」对服主没有价值——关掉日志里的堆栈后没人能定位，
     * 所以行号要进到命令输出里。
     */
    private static String describe(final Exception exception) {
        if (exception instanceof RhinoException rhino) {
            final String details = rhino.details();
            final String message = null == details ? String.valueOf(rhino.getMessage()) : details;
            final int line = rhino.lineNumber();

            return line > 0 ? "第 " + line + " 行: " + message : message;
        }

        final String message = exception.getMessage();

        return null == message ? exception.getClass().getSimpleName() : message;
    }

    /** 文件名去掉 .js 作为脚本 id。 */
    private static String idOf(final Path file) {
        final String name = file.getFileName().toString();

        if (!name.endsWith(".js")) {
            return null;
        }

        return name.substring(0, name.length() - ".js".length());
    }

    /** 单文件加载结果；成功时 {@code error} 为 {@code null}。 */
    private record FileResult(boolean ok, String error) {
    }
}
