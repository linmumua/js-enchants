package com.linmumua.jsenchant.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热重载是本框架的核心承诺，这里验证的是「改脚本能让行为变化」和
 * 「改崩脚本不会让附魔失效」这两条业务保证，而不只是文件读没读进来。
 *
 * <p>调用一律走 {@link ScriptEnginePool#invoke}：脚本不再暴露共享作用域，
 * 测试也不给自己开一条「直接从作用域取函数」的后门——那条路正是 Folia 下的竞争源头。
 * 脚本把结果写进 {@link Recorder}，断言仍然落在「脚本实际做了什么」上。
 */
class ScriptLoaderTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    /**
     * 脚本把结果写回 Java 侧记录器。
     *
     * <p>必须是 {@code public}：Rhino 只暴露 public 类的 public 成员；包名也必须在沙箱放行
     * 前缀（{@code com.linmumua.jsenchant.}）内，否则脚本会「找不到方法」。
     */
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

    /** 一次装配：注册表与脚本池必须成对出现，调用只走池的 invoke 入口。 */
    private record Fixture(ScriptEnginePool pool, ScriptRegistry registry, ScriptLoader loader) {
    }

    private Fixture fixture(final Path dir) {
        final ScriptEnginePool pool = new ScriptEnginePool(LOGGER);
        final ScriptRegistry registry = new ScriptRegistry();

        return new Fixture(pool, registry, new ScriptLoader(pool, registry, LOGGER, dir));
    }

    private Path write(final Path dir, final String name, final String content) throws IOException {
        final Path file = dir.resolve(name);

        Files.writeString(file, content, StandardCharsets.UTF_8);

        return file;
    }

    /** 执行脚本里的全局函数，并取回它记录的值。 */
    private Recorder run(final Fixture fixture, final CompiledScript script, final String function) {
        final Recorder recorder = new Recorder();

        assertTrue(fixture.pool().invoke(script, function, recorder), "脚本里应存在可执行的函数 " + function);

        return recorder;
    }

    @Test
    void 加载脚本并执行顶层函数(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function answer(rec) { rec.recordInt(42); }");

        assertEquals(1, fixture.loader().loadAll());
        assertEquals(List.of(42), this.run(fixture, fixture.registry().get("demo"), "answer").ints());
    }

    @Test
    void 加载校验不发布运行态到执行线程(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function answer(rec) { rec.recordInt(42); }");

        assertEquals(1, fixture.loader().loadAll());

        // 校验只能用独立临时作用域：一旦把校验收到的那个作用域留下来当运行态，
        // 它就会被多个 region 线程共用，正是要避免的情形。
        assertEquals(0, fixture.pool().runtimeCount(), "加载阶段不得留下任何线程运行态");
    }

    @Test
    void 热重载后行为随新脚本变化(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function answer(rec) { rec.recordText('v1'); }");
        fixture.loader().loadAll();

        assertEquals(List.of("v1"), this.run(fixture, fixture.registry().get("demo"), "answer").texts());

        final Path file = this.write(dir, "demo.js", "function answer(rec) { rec.recordText('v2'); }");

        assertTrue(fixture.loader().reload(file));
        assertEquals(List.of("v2"), this.run(fixture, fixture.registry().get("demo"), "answer").texts());
    }

    @Test
    void 内容未变的重载沿用同一版本号(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        final Path file = this.write(dir, "demo.js", "function answer(rec) { rec.recordInt(1); }");
        fixture.loader().loadAll();

        final CompiledScript first = fixture.registry().get("demo");

        assertTrue(fixture.loader().reload(file));

        final CompiledScript again = fixture.registry().get("demo");

        assertEquals(first.source(), again.source());
        assertEquals(first.version(), again.version(), "内容没变就不该涨版本：涨了会让各线程白重建运行态");
    }

    @Test
    void 编译失败时保留旧版本(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function tag(rec) { rec.recordInt(7); }");
        fixture.loader().loadAll();

        // 写坏语法：这是热重载最容易踩的坑，不能因此把能用的附魔清掉。
        final Path broken = this.write(dir, "demo.js", "function tag( { return");

        assertFalse(fixture.loader().reload(broken), "坏脚本应返回失败");

        final CompiledScript kept = fixture.registry().get("demo");

        assertNotNull(kept, "旧版本必须仍然存在");
        assertEquals(List.of(7), this.run(fixture, kept, "tag").ints(), "旧版本行为必须不变");
    }

    @Test
    void 顶层执行失败也视为加载失败并保留旧版本(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function tag(rec) { rec.recordInt(3); }");
        fixture.loader().loadAll();

        // 语法正确、但顶层一执行就抛错：同样不能让旧版本失效。
        final Path broken = this.write(dir, "demo.js", "throw new Error('顶层炸了'); function tag(rec) { rec.recordInt(4); }");

        assertFalse(fixture.loader().reload(broken), "顶层抛错也必须算加载失败");

        final CompiledScript kept = fixture.registry().get("demo");

        assertNotNull(kept, "旧版本必须仍然可用");
        assertEquals(List.of(3), this.run(fixture, kept, "tag").ints());
    }

    @Test
    void 全量加载如实报告失败明细(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "good.js", "function answer(rec) { rec.recordInt(1); }");
        this.write(dir, "bad.js", "function answer( { return");

        final ScriptLoadReport report = fixture.loader().reloadAll();

        assertTrue(report.directoryReady());
        assertFalse(report.success(), "有文件编译失败时不能报成功");
        assertEquals(1, report.loaded());
        assertEquals(1, report.failed());
        assertTrue(fixture.registry().ids().contains("good"));
        assertFalse(fixture.registry().ids().contains("bad"), "编译不过的脚本不能进注册表");

        final String failure = report.failures().get(0);

        assertTrue(failure.startsWith("bad.js"), "失败明细要指名文件，命令才能直接回显: " + failure);
        assertTrue(failure.contains("第 1 行"), "失败明细要带行号，否则服主无法定位: " + failure);
    }

    @Test
    void 全量加载会移除已删除的脚本(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "keep.js", "function answer(rec) { rec.recordInt(1); }");
        this.write(dir, "gone.js", "function answer(rec) { rec.recordInt(2); }");

        assertEquals(2, fixture.loader().loadAll());
        assertNotNull(fixture.registry().get("gone"));

        // 先真的执行一次，让「gone」拥有线程运行态，后面的清理断言才有意义。
        this.run(fixture, fixture.registry().get("gone"), "answer");
        assertEquals(1, fixture.pool().runtimeCount());

        Files.delete(dir.resolve("gone.js"));

        final ScriptLoadReport report = fixture.loader().reloadAll();

        assertTrue(report.success());
        assertEquals(1, report.loaded());
        assertNull(fixture.registry().get("gone"), "手动 /jse reload 也要清掉已删除的脚本，不能只靠 watcher");
        assertNotNull(fixture.registry().get("keep"));
        assertEquals(1, fixture.registry().size());
        assertEquals(0, fixture.pool().runtimeCount(), "脚本删除后它的运行态也必须清掉");
    }

    @Test
    void 卸载脚本会清理它的运行态(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function tag(rec) { rec.recordText('v1'); }");
        fixture.loader().loadAll();

        final CompiledScript loaded = fixture.registry().get("demo");

        assertEquals(List.of("v1"), this.run(fixture, loaded, "tag").texts());
        assertEquals(1, fixture.pool().runtimeCount());

        fixture.loader().unload("demo");

        assertNull(fixture.registry().get("demo"));
        assertEquals(0, fixture.pool().runtimeCount(), "删除即清理，不能留到插件停用");

        // 在途引用仍然只是「不可变源码」：脚本没了，手里这一份还能正常跑完。
        assertEquals(List.of("v1"), this.run(fixture, loaded, "tag").texts());
    }

    @Test
    void 目录不可列举时保留现有脚本(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "keep.js", "function answer(rec) { rec.recordInt(1); }");
        fixture.loader().loadAll();

        // 把 scriptDir 指向一个普通文件：既不能创建也不能列举。
        final Path blocked = dir.resolve("blocked.txt");

        Files.writeString(blocked, "x", StandardCharsets.UTF_8);

        final ScriptLoadReport report = new ScriptLoader(fixture.pool(), fixture.registry(), LOGGER, blocked).reloadAll();

        assertFalse(report.directoryReady());
        assertFalse(report.success());
        assertNotNull(fixture.registry().get("keep"), "读不到目录不等于文件没了，不能清空注册表");
        assertEquals(1, fixture.registry().size());
    }

    @Test
    void 编译失败时报告保留旧版本(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "demo.js", "function tag(rec) { rec.recordInt(7); }");
        fixture.loader().loadAll();

        this.write(dir, "demo.js", "function tag( { return");

        final ScriptLoadReport report = fixture.loader().reloadAll();

        assertEquals(0, report.loaded(), "编译失败的文件不计入已加载数量");
        assertEquals(1, report.failed());
        assertFalse(report.success());

        final CompiledScript kept = fixture.registry().get("demo");

        assertNotNull(kept, "旧版本必须仍然可用");
        assertEquals(List.of(7), this.run(fixture, kept, "tag").ints());
    }

    @Test
    void 脚本无法触达宿主类(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        // 沙箱要求：脚本不能自行拿到 java 类。
        this.write(dir, "evil.js", """
                function escape(rec) {
                  try {
                    rec.recordText(java.lang.System.getProperty("user.name"));
                  } catch (e) {
                    rec.recordText("blocked");
                  }
                }
                """);

        assertTrue(fixture.loader().loadAll() > 0);

        assertEquals(
                List.of("blocked"),
                this.run(fixture, fixture.registry().get("evil"), "escape").texts(),
                "脚本不应能访问 java.lang"
        );
    }

    @Test
    void 非js文件被忽略(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "readme.txt", "not a script");

        assertEquals(0, fixture.loader().loadAll());
        assertNull(fixture.registry().get("readme"));
    }

    @Test
    void console输出不抛异常(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        this.write(dir, "chatty.js", "console.log('hello', 1);");

        assertTrue(fixture.loader().loadAll() > 0);
        assertNotNull(fixture.registry().get("chatty"));
    }
}
