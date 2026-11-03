package com.linmumua.jsenchant.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rhino 执行入口，同时持有各执行线程的脚本运行态。
 *
 * <p>刻意使用解释执行模式（optimization level -1）：Rhino 默认会为热点代码生成
 * 字节码类，反复热重载后 Metaspace 只增不减。解释模式不生成任何类，这是脚本能
 * 长跑热重载而不崩的前提。
 *
 * <p>沙箱由两层构成：
 * <ul>
 *   <li>{@link ClassShutter} 拒绝脚本以任何方式直接触达宿主类（<code>java.*</code>、
 *       <code>Packages</code> 等）；</li>
 *   <li>脚本能用的对象只有框架显式注入的那几个。</li>
 * </ul>
 * 传入的对象本身仍可正常调方法——这是脚本工作需要的能力，不属于逃逸。
 *
 * <p>不修改 Rhino 的全局 {@code ContextFactory}：那是 JVM 级单例，改它会影响同进程内
 * 其他用 Rhino 的插件。全部策略在每次 {@code Context.enter()} 后按上下文设置。
 *
 * <p><b>线程模型（Folia）</b>：发布出去的只有不可变源码与版本号，见 {@link CompiledScript}。
 * 作用域、函数对象、编译结果全部按<b>执行线程</b>各建一份，由 {@link ScriptRuntimeCache}
 * 持有，任何一份都不跨线程使用；因此多个 region 线程能同时跑同一份脚本，全局变量互不可见。
 * 这里既不加「每脚本跨 region 串行锁」，也不用「共享 handler + 子作用域」这类折中——
 * 两者都会把可变状态重新摊回线程之间。在 Paper 这类单线程服务端上，只有那一个线程会建立
 * 运行态，行为与改造前一致。
 */
public final class ScriptEnginePool {

    /** 默认策略：精确放行框架 API 与 Bukkit 对象，其余拒绝。 */
    private static final ClassShutter DEFAULT_SHUTTER = new ScriptSandbox();

    /** 与插件日志器同名；在服务端里这就是插件自己的 logger。 */
    private static final Logger DEFAULT_LOGGER = Logger.getLogger("JsEnchants");

    private static final Object[] NO_ARGS = new Object[0];

    private final ClassShutter shutter;
    private final Logger logger;

    /** 各执行线程的运行态；显式清理，不依赖 ThreadLocal 的自动回收。 */
    private final ScriptRuntimeCache runtime = new ScriptRuntimeCache();

    /** 停用后为 {@code true}：{@link #invoke} 一律不执行，缓存已清空。 */
    private volatile boolean closed;

    public ScriptEnginePool() {
        this(DEFAULT_SHUTTER, DEFAULT_LOGGER);
    }

    public ScriptEnginePool(final Logger logger) {
        this(DEFAULT_SHUTTER, logger);
    }

    public ScriptEnginePool(final ClassShutter shutter) {
        this(shutter, DEFAULT_LOGGER);
    }

    /**
     * @param shutter 自定义放行策略，生产环境应保持拒绝
     * @param logger  脚本内 {@code console.*} 的落地日志器
     */
    public ScriptEnginePool(final ClassShutter shutter, final Logger logger) {
        this.shutter = shutter;
        this.logger = logger;
    }

    /**
     * 执行脚本里的某个全局函数——与事件派发层的固定对接入口。
     *
     * <p>线程语义：作用域与函数对象全部由<b>当前线程</b>自己的运行态提供，本方法不持锁，
     * 也不会把任何 {@link Scriptable}/{@link Function} 交给别的线程。所以同一脚本可以在多个
     * region 线程上并发执行，各自的全局变量互不可见；同一线程重复调用则看到自己上一次的修改。
     *
     * <p>返回 {@code false} 表示<b>没有执行</b>：脚本池已停用、脚本为空、或脚本里没有这个
     * 全局函数。脚本自身抛出的异常<b>不吞</b>——交给调用方（dispatcher）记日志并决定是否记账。
     *
     * @return 是否真的执行了
     */
    public boolean invoke(final CompiledScript script, final String functionName, final Object... args) {
        if (this.closed || null == script || null == functionName) {
            return false;
        }

        final Object[] actualArgs = null == args ? NO_ARGS : args;

        return this.withContext(context -> {
            // 该作用域只属于当前线程：Rhino 的 Scriptable/Function 可变，不能跨线程共享。
            final Scriptable scope = this.runtime.scopeFor(
                    Thread.currentThread(),
                    script,
                    target -> scopeOf(context, target, this.logger)
            );

            final Object handler = scope.get(functionName, scope);

            if (!(handler instanceof Function function)) {
                return Boolean.FALSE;
            }

            function.call(context, scope, scope, actualArgs);

            return Boolean.TRUE;
        });
    }

    /**
     * 发布新版本：淘汰该脚本旧版本的运行态。
     *
     * <p>只动缓存、不打断在途调用——正在执行的那一帧仍握着自己的作用域引用，会正常跑完。
     * 版本号没变（源码内容未变）时这里是空操作。
     */
    public void publish(final CompiledScript script) {
        if (this.closed || null == script) {
            return;
        }

        this.runtime.retainVersion(script);
    }

    /** 脚本被删除：清掉它在所有线程上的运行态。 */
    public void discard(final String id) {
        if (null == id) {
            return;
        }

        this.runtime.discardScript(id);
    }

    /**
     * 某个执行线程退役（region 线程下线、线程池回收）时立刻回收它的运行态。
     *
     * <p>作用域挂在容器里而不是 {@code ThreadLocal} 里，线程结束不会自动带走它们。生产代码
     * 目前只在停用时调 {@link #close}，所以容器另有兜底：查询路径上按固定计数低频清扫已终止
     * 线程的残留。显式调用用于「退役即回收」，不必等下一次清扫。
     */
    public void releaseThread(final Thread thread) {
        if (null == thread) {
            return;
        }

        this.runtime.releaseThread(thread);
    }

    /**
     * 停用：清空缓存并禁止后续 {@link #invoke}。
     *
     * <p>可重复调用。停用期间已经进入 {@code invoke} 的调用会正常跑完（在途语义），
     * 之后的所有调用一律返回 {@code false}。
     */
    public void close() {
        this.closed = true;
        this.runtime.clear();
    }

    public boolean isClosed() {
        return this.closed;
    }

    /** 当前缓存的运行态数量（诊断用）：正常情况下约等于「活跃线程数 × 生效脚本数」。 */
    public int runtimeCount() {
        return this.runtime.size();
    }

    /**
     * 在受控上下文中执行一段逻辑。每次都进入独立 Context，避免线程间共享状态。
     *
     * <p>只做策略设置，不碰任何脚本运行态：作用域的归属由 {@link ScriptRuntimeCache} 按线程管理。
     */
    public <T> T withContext(final ContextAction<T> action) {
        final Context context = Context.enter();

        try {
            context.setClassShutter(this.shutter);
            context.setOptimizationLevel(-1);
            context.setLanguageVersion(Context.VERSION_ES6);

            return action.run(context);
        } finally {
            Context.exit();
        }
    }

    /**
     * 在全新作用域里编译并执行脚本顶层，返回该作用域。
     *
     * <p>两处调用：{@link #invoke} 用它建立执行线程的运行态；{@link ScriptLoader} 用它做
     * 独立临时校验（校验收到的那个作用域用完即弃，绝不发布给任何线程）。
     */
    static Scriptable scopeOf(final Context context, final CompiledScript script, final Logger logger) {
        final Scriptable scope = context.initStandardObjects();
        final Scriptable console = context.newObject(scope);

        ScriptableObject.putProperty(console, "log", new LogFunction(logger, Level.INFO));
        ScriptableObject.putProperty(console, "warn", new LogFunction(logger, Level.WARNING));
        ScriptableObject.putProperty(console, "error", new LogFunction(logger, Level.SEVERE));
        ScriptableObject.putProperty(scope, "console", console);

        // 编译结果同样是每线程一份、用完即放的临时对象，不跨线程复用。
        final Script compiled = context.compileString(script.source(), script.id(), 1, null);

        // 立刻执行一次，让顶层声明进入作用域。脚本顶层应只做声明/局部初始化：
        // 全局状态按执行线程各自独立，所以顶层不要假设自己是进程内唯一一份。
        compiled.exec(context, scope);

        return scope;
    }

    @FunctionalInterface
    public interface ContextAction<T> {
        T run(Context context);
    }

    /** 把 JS 的 console.* 转到插件日志。 */
    private static final class LogFunction extends BaseFunction {

        private final transient Logger logger;
        private final transient Level level;

        private LogFunction(final Logger logger, final Level level) {
            this.logger = logger;
            this.level = level;
        }

        @Override
        public Object call(
                final Context context,
                final Scriptable scope,
                final Scriptable thisObj,
                final Object[] args
        ) {
            final StringBuilder message = new StringBuilder("[JS] ");

            for (final Object arg : args) {
                message.append(Context.toString(arg)).append(' ');
            }

            this.logger.log(this.level, message.toString().trim());

            return Context.getUndefinedValue();
        }

        @Override
        public String getFunctionName() {
            return "log";
        }
    }
}
