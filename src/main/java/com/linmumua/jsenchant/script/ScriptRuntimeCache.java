package com.linmumua.jsenchant.script;

import org.mozilla.javascript.Scriptable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 各执行线程自己的脚本运行态（作用域）。
 *
 * <p><b>为什么按线程存</b>：Folia 的多个 region 线程会同时执行同一份脚本，而 Rhino 的
 * 作用域与函数对象可变，跨线程共享就是数据竞争。这里给「线程 + 脚本 id + 版本 + 源码指纹」
 * 各存一份作用域，任何一份都只会被创建它的那个线程读取，脚本的全局变量因此天然按线程隔离。
 *
 * <p><b>为什么显式清理</b>：{@code ThreadLocal} 只在所属线程还活着时才有意义——region 线程
 * 退役、线程池回收之后，挂在里面的作用域不会自己消失，插件停用时也没有「自动回收」可言。
 * 所以这里是一个可以显式清理的容器：删脚本用 {@link #discardScript}，线程退役用
 * {@link #releaseThread}，插件停用用 {@link #clear}。
 *
 * <p><b>兜底</b>：生产代码只在插件停用时会调 {@code close}，region 线程退役时没人通知，
 * 也没有重载就没有 publish/discard。因此查询路径上按固定计数（{@link #SWEEP_INTERVAL}）
 * 低频清扫已终止线程的残留：这是有界开销，不是每次查表全表扫，也不需要外部回调。
 *
 * <p><b>不做的事</b>：不持锁、不串行化脚本执行。并发安全只依赖 {@link ConcurrentHashMap}
 * 的单次原子操作；编译与执行本身完全发生在各自线程内，容器不会把任何一次脚本调用挡在门外。
 */
final class ScriptRuntimeCache {

    /**
     * 死线程残留的清扫间隔，按查询次数计（必须是 2 的幂，用掩码判断）。
     *
     * <p>取固定计数而不是「每次查表都顺手扫一遍」：清扫是 O(表大小) 的纯 Java 操作，
     * 摊到每 256 次查询一次后，开销与调用量无关，既不会每 tick 全表扫，也不持锁、不阻塞脚本执行。
     */
    static final int SWEEP_INTERVAL = 256;

    /** (线程, 脚本 id, 版本, 源码指纹) → 该线程上这一份内容的作用域。 */
    private final Map<Key, Scriptable> scopes = new ConcurrentHashMap<>();

    /** 查询计数，只用于按固定间隔触发清扫。 */
    private final AtomicLong lookups = new AtomicLong();

    /**
     * 取本线程上该版本的运行态；没有就现建一个。
     *
     * <p>建作用域（编译 + 执行顶层）放在锁外进行：容器只做一次 {@code putIfAbsent}，
     * 不会因为某线程首次执行脚本而拖住别的线程。
     *
     * <p>键里除版本号还带一份源码指纹：{@link CompiledScript} 是公开 record，若有人手工拼出
     * 同 id 同版本但内容不同的实例，也不能让它命中别人的运行态而静默执行旧代码。
     *
     * @param factory 在本线程上新建运行态的方式
     */
    Scriptable scopeFor(final Thread thread, final CompiledScript script, final ScopeFactory factory) {
        final Key key = Key.of(thread, script);
        final Scriptable cached = this.scopes.get(key);

        if (null != cached) {
            this.sweepIfDue();

            return cached;
        }

        final Scriptable created = factory.create(script);

        // 同一个线程不会和自己竞争；putIfAbsent 只是为了不覆盖已有作用域。
        final Scriptable raced = this.scopes.putIfAbsent(key, created);

        this.sweepIfDue();

        return null == raced ? created : raced;
    }

    /**
     * 脚本换成新内容后，淘汰该 id 的其它版本（以及同版本但不同内容）的运行态。
     *
     * <p>只是从表里摘掉条目：正在执行的一帧仍握着自己的作用域引用，会正常跑完。
     */
    void retainVersion(final CompiledScript script) {
        final long version = script.version();
        final int sourceHash = script.source().hashCode();

        this.scopes.keySet().removeIf(key -> key.id().equals(script.id())
                && (key.version() != version || key.sourceHash() != sourceHash));
        this.sweepDeadThreads();
    }

    /** 删除脚本时清掉它在所有线程上的运行态。 */
    void discardScript(final String id) {
        this.scopes.keySet().removeIf(key -> key.id().equals(id));
        this.sweepDeadThreads();
    }

    /** 线程退役时立刻回收它的全部运行态；不调用也有查询路径上的低频兜底。 */
    void releaseThread(final Thread thread) {
        this.scopes.keySet().removeIf(key -> key.thread() == thread);
    }

    /** 插件停用：全部清空。 */
    void clear() {
        this.scopes.clear();
    }

    /** 当前缓存的运行态数量，用于诊断与测试。 */
    int size() {
        return this.scopes.size();
    }

    /**
     * 按固定查询计数触发一次死线程清扫。
     *
     * <p>兜底不能依赖外部回调：生产代码只在插件停用时调 {@code close}，region 线程退役时
     * 没人通知，没有重载就没有 publish/discard。所以清扫挂在查询路径上，每
     * {@link #SWEEP_INTERVAL} 次查询扫一遍——空闲期不扫，繁忙期也只是把一次短扫描摊薄。
     */
    private void sweepIfDue() {
        if (0L != (this.lookups.incrementAndGet() & (SWEEP_INTERVAL - 1))) {
            return;
        }

        this.sweepDeadThreads();
    }

    /**
     * 剔掉已终止线程的残留条目。
     *
     * <p>这是容器自己的兜底，不依赖 {@code ThreadLocal}：没人调 {@code releaseThread} 时，
     * 查询路径上的低频清扫会把死线程的作用域收掉。只摘表项，不动任何正在执行的作用域。
     */
    private void sweepDeadThreads() {
        this.scopes.keySet().removeIf(key -> !key.thread().isAlive());
    }

    /**
     * @param sourceHash 源码指纹，防止同 id 同版本但内容不同的实例命中别人的运行态
     */
    private record Key(Thread thread, String id, long version, int sourceHash) {

        static Key of(final Thread thread, final CompiledScript script) {
            return new Key(thread, script.id(), script.version(), script.source().hashCode());
        }
    }

    /** 在指定线程上新建一份运行态。 */
    @FunctionalInterface
    interface ScopeFactory {
        Scriptable create(CompiledScript script);
    }
}
