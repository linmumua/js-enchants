package com.linmumua.jsenchant.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptEnginePool#invoke} 是事件派发层唯一能进的门口，Folia 的正确性全压在这里：
 * 同一份脚本会被多个 region 线程同时执行，所以作用域必须按线程独立，且容器不能靠
 * {@code ThreadLocal} 的自动回收来收尾。
 *
 * <p>这些测试只验语义（谁看到哪一份全局、什么时候被清理），不复制任何共享作用域的取函数捷径。
 */
class ScriptEnginePoolTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsPoolTest");

    /** 脚本写回结果的记录器；必须 public，且包名在沙箱放行前缀内。 */
    public static final class Recorder {

        private final List<Integer> ints = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();

        public void recordInt(final int value) {
            this.ints.add(value);
        }

        public void recordText(final String value) {
            this.texts.add(value);
        }

        public List<Integer> ints() {
            return this.ints;
        }

        public List<String> texts() {
            return this.texts;
        }
    }

    private record Fixture(ScriptEnginePool pool, ScriptRegistry registry, ScriptLoader loader) {
    }

    private Fixture fixture(final Path dir) {
        final ScriptEnginePool pool = new ScriptEnginePool(LOGGER);
        final ScriptRegistry registry = new ScriptRegistry();

        return new Fixture(pool, registry, new ScriptLoader(pool, registry, LOGGER, dir));
    }

    /** 写脚本并加载，返回发布后的那一份。 */
    private CompiledScript load(final Fixture fixture, final Path dir, final String id, final String source)
            throws IOException {
        Files.writeString(dir.resolve(id + ".js"), source, StandardCharsets.UTF_8);

        assertTrue(fixture.loader().reload(dir.resolve(id + ".js")), "脚本应加载成功: " + id);

        final CompiledScript script = fixture.registry().get(id);

        assertNotNull(script, "加载成功后注册表里应有该脚本: " + id);

        return script;
    }

    @Test
    void 多线程执行同一脚本各自持有独立全局(@TempDir final Path dir) throws Exception {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "counter", """
                var count = 0;
                function bump(rec) {
                  count = count + 1;
                  rec.recordInt(count);
                }
                """);

        final int threads = 4;
        final int calls = 3;
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final List<Recorder> recorders = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final List<Thread> workers = new ArrayList<>();

        for (int index = 0; index < threads; index++) {
            final Recorder recorder = new Recorder();

            recorders.add(recorder);

            final Thread worker = new Thread(() -> {
                try {
                    // 一起开跑：如果作用域被共享，计数就会互相可见。
                    barrier.await(15, TimeUnit.SECONDS);

                    for (int call = 0; call < calls; call++) {
                        assertTrue(fixture.pool().invoke(script, "bump", recorder), "并发调用应成功");
                    }
                } catch (final Throwable throwable) {
                    failures.add(throwable);
                }
            }, "region-" + index);

            worker.start();
            workers.add(worker);
        }

        for (final Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(15));
            assertFalse(worker.isAlive(), "region 线程未在超时内结束");
        }

        assertTrue(failures.isEmpty(), "并发执行不应出错: " + failures);

        for (final Recorder recorder : recorders) {
            assertEquals(
                    List.of(1, 2, 3),
                    recorder.ints(),
                    "每个 region 线程都必须从自己的全局 count 开始计数"
            );
        }
    }

    @Test
    void 同一线程内全局状态跨调用保留(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "counter", """
                var count = 0;
                function bump(rec) {
                  count = count + 1;
                  rec.recordInt(count);
                }
                """);

        final Recorder recorder = new Recorder();

        for (int call = 0; call < 3; call++) {
            assertTrue(fixture.pool().invoke(script, "bump", recorder));
        }

        assertEquals(List.of(1, 2, 3), recorder.ints(), "同一线程应当看到自己上一次的修改（Paper 单线程即全局一份）");
        assertEquals(1, fixture.pool().runtimeCount(), "一个线程一个脚本只该有一份运行态");
    }

    @Test
    void 缺少目标函数时返回false(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "noop", "function other(rec) { rec.recordInt(1); }");

        assertFalse(fixture.pool().invoke(script, "onTrigger"), "没有该全局函数时必须报「未执行」");
    }

    @Test
    void 脚本异常原样抛给调用方(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "boom", """
                function boom(rec) {
                  throw new Error('炸了');
                }
                """);

        // 不吞异常：调度层要靠它记日志并回滚记账，静默成功会被当成触发成功。
        assertThrows(RuntimeException.class, () -> fixture.pool().invoke(script, "boom", new Recorder()));
    }

    @Test
    void 版本切换后新旧引用互不污染(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript old = this.load(fixture, dir, "demo", "function tag(rec) { rec.recordText('v1'); }");

        final CompiledScript current = this.load(fixture, dir, "demo", "function tag(rec) { rec.recordText('v2'); }");

        assertTrue(current.version() > old.version(), "内容变了就必须换版本号");

        final Recorder recorder = new Recorder();

        assertTrue(fixture.pool().invoke(current, "tag", recorder));
        // 在途的旧引用：它握着不可变源码，仍然按旧脚本执行，且不能把新版本挤掉。
        assertTrue(fixture.pool().invoke(old, "tag", recorder));
        assertTrue(fixture.pool().invoke(current, "tag", recorder));

        assertEquals(List.of("v2", "v1", "v2"), recorder.texts(), "旧引用按旧源码执行，新引用按新源码执行");
    }

    @Test
    void 反复重载只保留当前版本的运行态(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        for (int round = 1; round <= 5; round++) {
            final CompiledScript script = this.load(
                    fixture,
                    dir,
                    "demo",
                    "function tag(rec) { rec.recordText('v" + round + "'); }"
            );

            final Recorder recorder = new Recorder();

            assertTrue(fixture.pool().invoke(script, "tag", recorder));
            assertEquals(List.of("v" + round), recorder.texts());
            assertEquals(1, fixture.pool().runtimeCount(), "换版本是替换而不是累计");
        }
    }

    @Test
    void 线程退役可显式回收运行态(@TempDir final Path dir) throws Exception {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "demo", "function tag(rec) { rec.recordText('v1'); }");

        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Thread worker = new Thread(() -> {
            try {
                assertTrue(fixture.pool().invoke(script, "tag", new Recorder()));
            } catch (final Throwable throwable) {
                failures.add(throwable);
            }
        }, "region-retired");

        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(15));

        assertTrue(failures.isEmpty(), "线程内调用不应出错: " + failures);
        assertEquals(1, fixture.pool().runtimeCount(), "该线程应建立起自己的一份运行态");

        fixture.pool().releaseThread(worker);

        // 显式回收要立刻生效：兜底清扫只在每 N 次查询后发生，退役当刻就得能清干净。
        assertEquals(0, fixture.pool().runtimeCount(), "线程退役后必须能显式清掉它的运行态");
    }

    @Test
    void 线程终止后无需reload也会被兜底清理(@TempDir final Path dir) throws Exception {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "demo", "function tag(rec) { rec.recordText('v1'); }");

        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Thread worker = new Thread(() -> {
            try {
                assertTrue(fixture.pool().invoke(script, "tag", new Recorder()));
            } catch (final Throwable throwable) {
                failures.add(throwable);
            }
        }, "region-terminated");

        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(15));

        assertTrue(failures.isEmpty(), "线程内调用不应出错: " + failures);
        assertEquals(1, fixture.pool().runtimeCount(), "死线程那一份还在：它不靠 ThreadLocal，不会自己消失");

        // 全程没有任何 reload（也就没有 publish/discard），只靠后续访问把死线程残留扫掉。
        final Recorder recorder = new Recorder();
        final int calls = ScriptRuntimeCache.SWEEP_INTERVAL + 1;

        for (int call = 0; call < calls; call++) {
            assertTrue(fixture.pool().invoke(script, "tag", recorder), "清扫不得影响脚本照常执行");
        }

        assertEquals(1, fixture.pool().runtimeCount(), "固定计数触发的兜底清扫必须收掉已终止线程的运行态");
        assertEquals(calls, recorder.texts().size(), "清扫只摘表项，不能少执行或多执行一次");
    }

    @Test
    void 停止后拒绝执行并清空缓存(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);
        final CompiledScript script = this.load(fixture, dir, "demo", "function tag(rec) { rec.recordText('v1'); }");

        final Recorder recorder = new Recorder();

        assertTrue(fixture.pool().invoke(script, "tag", recorder));

        fixture.pool().close();
        fixture.pool().close();

        assertTrue(fixture.pool().isClosed(), "关闭应可重复调用");
        assertEquals(0, fixture.pool().runtimeCount(), "停用必须清空运行态缓存");
        assertFalse(fixture.pool().invoke(script, "tag", recorder), "停用后不得再执行脚本");
        assertEquals(List.of("v1"), recorder.texts(), "停用后到达的调用一律未执行");
    }
}
