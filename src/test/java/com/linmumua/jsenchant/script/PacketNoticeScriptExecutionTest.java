package com.linmumua.jsenchant.script;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真正把随包分发的 {@code scripts/packet_notice.js} 放进生产引擎（{@link ScriptEnginePool}
 * 与它自己的 {@code console}）里执行，覆盖它必须区分的四条路径：
 * <ol>
 *   <li>PacketEvents 可用：包被提交，且日志说的是「已提交」——不是「客户端已收到」；</li>
 *   <li>PacketEvents 未初始化：报「尚未初始化」并跳过，不假装成功；</li>
 *   <li>写包抛异常：异常被报出来（带原因），不再被空 catch 吞掉；</li>
 *   <li>PE 的类根本不可见（类加载器看不到）：同样要留下可读日志。</li>
 * </ol>
 *
 * <p>为什么不是「配置字符串测试」：脚本的失败路径全在脚本内部，只有真的执行到
 * {@code console.warn} 才能证明「故障会说出来」。日志断言取自 {@link Logger} 实际收到的
 * {@link LogRecord}，也就是生产环境写进服务端日志的那条通道，不是测试自己的桩。
 *
 * <p>第 4 条靠线程上下文类加载器实现：Rhino 在没有显式设置 application classloader 时
 * 优先用 TCCL（见 {@code Context#getApplicationClassLoader}），所以给工作线程换一个
 * 「屏蔽 com.github.retrooper.*」的 TCCL 就能复现「类不可见」，不需要改沙箱。
 */
class PacketNoticeScriptExecutionTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsPacketNoticeTest");

    private static final String SCRIPT_RESOURCE = "scripts/packet_notice.js";

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private final Handler collector = new Handler() {

        @Override
        public void publish(final LogRecord record) {
            PacketNoticeScriptExecutionTest.this.records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** 脚本注入的对象必须 public，且包名落在沙箱放行前缀 {@code com.linmumua.jsenchant.} 内。 */
    public static final class StubPlayer {

        private final String name;

        public StubPlayer(final String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    /**
     * 诊断字段坏掉的玩家：{@code getName()} 直接抛异常。
     *
     * <p>用它钉住一条真实踩过的坑：提交日志若和发包挤在同一个 try 里，读名字失败会被
     * 记成「发送 ActionBar 失败」——包其实已经交出去了，日志却在报另一种故障。
     */
    public static final class BrokenPlayer {

        public String getName() {
            throw new IllegalStateException("模拟 getName 失败");
        }

        @Override
        public String toString() {
            return "broken-player";
        }
    }

    /**
     * 脚本只用到 {@code ctx.player()} 与三个读配置的方法。
     *
     * <p>读配置的三个方法一律返回默认值：这些用例关心的是「日志说了什么」，
     * 不是文案从哪里来；给它们真实选项会让断言依赖一份与 yml 同步的副本。
     */
    public static final class StubContext {

        private final Object player;

        public StubContext(final Object player) {
            this.player = player;
        }

        public Object player() {
            return this.player;
        }

        public Object option(final String key) {
            return null;
        }

        public boolean optionFlag(final String key, final boolean fallback) {
            return fallback;
        }

        public String optionText(final String key, final String fallback) {
            return fallback;
        }
    }

    private record Fixture(ScriptEnginePool pool, CompiledScript script) {
    }

    @BeforeEach
    void attach() {
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);
        LOGGER.addHandler(this.collector);

        PacketEvents.reset();
        this.records.clear();
    }

    @AfterEach
    void detach() {
        LOGGER.removeHandler(this.collector);

        PacketEvents.reset();
    }

    @Test
    void 可用时提交并只报告已提交(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        assertTrue(fixture.pool().invoke(fixture.script(), "onTrigger", new StubContext(new StubPlayer("linmumua"))));
        // 第二次：首次类日志不得重复刷。
        assertTrue(fixture.pool().invoke(fixture.script(), "onTrigger", new StubContext(new StubPlayer("linmumua"))));

        assertTrue(this.logged(Level.INFO, "PlayerToggleSneakEvent 已派发到脚本"), "首次派发必须留一行可核对的日志");

        assertEquals(1, this.count(Level.INFO, "PlayerToggleSneakEvent 已派发到脚本"), "首次日志只该出现一次");
        assertEquals(1, this.count(Level.INFO, "ActionBar 单播已提交"), "提交日志只该出现一次");

        // 措辞边界：sendPacket 返回只代表交给 PacketEvents，日志不能替客户端作证。
        assertTrue(this.logged(Level.INFO, "客户端是否收到无法在服务端证实"), "提交日志必须写明它不证明客户端已收到");
        assertFalse(this.logged(Level.INFO, "已送达"), "日志里不允许出现「已送达」这类端到端结论");

        assertEquals("linmumua", PacketEvents.playerManager().lastTarget(), "包应当只提交给触发者本人");

        final Object packet = PacketEvents.playerManager().lastPacket();

        assertNotNull(packet, "应当真的构造并发出了一个包");
        assertTrue(packet instanceof WrapperPlayServerActionBar, "走的是 ActionBar 包装类");

        assertEquals(
                Component.text("以太回声 · 已听见"),
                ((WrapperPlayServerActionBar) packet).getActionBarText(),
                "包里的文本应与脚本一致"
        );
    }

    @Test
    void 未初始化时明确跳过且不提交(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        PacketEvents.state(PacketEvents.State.NOT_INITIALIZED);

        assertTrue(fixture.pool().invoke(fixture.script(), "onTrigger", new StubContext(new StubPlayer("linmumua"))));

        assertTrue(this.logged(Level.WARNING, "PacketEvents 尚未初始化"), "未初始化必须写进日志，不能静默跳过");
        assertEquals(0, PacketEvents.playerManager().sent(), "未初始化时不该发出任何包");
        assertFalse(this.logged(Level.INFO, "ActionBar 单播已提交"), "没发出去就不能报「已提交」");
    }

    @Test
    void 写包抛异常时把原因报出来(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        PacketEvents.state(PacketEvents.State.SEND_FAILS);

        // 脚本自己吞掉异常是有意的（可选的展示依赖不该污染冷却），但必须留下原因。
        assertTrue(fixture.pool().invoke(fixture.script(), "onTrigger", new StubContext(new StubPlayer("linmumua"))));

        assertTrue(this.logged(Level.WARNING, "发送 ActionBar 失败"), "发包异常必须写进日志");
        assertTrue(
                this.logged(Level.WARNING, "模拟网络层写入失败"),
                "日志必须带上原始异常信息，否则「失败」两个字等于没说"
        );
        assertEquals(0, PacketEvents.playerManager().sent());
        assertFalse(this.logged(Level.INFO, "ActionBar 单播已提交"));
    }

    @Test
    void PE类不可见时同样留下日志(@TempDir final Path dir) throws Exception {
        final Fixture fixture = this.fixture(dir);
        final AtomicReference<Boolean> invoked = new AtomicReference<>();

        // 换成看不到 PE 的 TCCL：Rhino 会按这个 loader 解析 Java 类名。
        final Thread worker = new Thread(
                () -> invoked.set(fixture.pool().invoke(fixture.script(), "onTrigger", new StubContext(new StubPlayer("linmumua")))),
                "no-packetevents"
        );

        worker.setContextClassLoader(new BlockingLoader());
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(15));

        assertFalse(worker.isAlive(), "工作线程未在超时内结束");
        assertEquals(Boolean.TRUE, invoked.get(), "脚本应当执行完并把原因写进日志，而不是把异常抛给派发层");

        assertTrue(
                this.logged(Level.WARNING, "访问 com.github.retrooper.packetevents.PacketEvents 失败"),
                "类不可见时必须报出「访问失败」，并带上 Rhino 原始错误"
        );
        assertEquals(0, PacketEvents.playerManager().sent());
        assertFalse(this.logged(Level.INFO, "ActionBar 单播已提交"));
    }

    @Test
    void 诊断字段异常不得误报成发送失败(@TempDir final Path dir) throws IOException {
        final Fixture fixture = this.fixture(dir);

        // 包能正常交出去，只是「读玩家名」这一步坏掉。
        assertTrue(fixture.pool().invoke(fixture.script(), "onTrigger", new StubContext(new BrokenPlayer())));

        assertEquals(1, PacketEvents.playerManager().sent(), "诊断字段坏掉不该影响发包本身");
        assertFalse(
                this.logged(Level.WARNING, "发送 ActionBar 失败"),
                "日志步骤的异常绝不能落进发送 try 的 catch：包已经提交了，报发送失败就是假故障"
        );

        assertTrue(this.logged(Level.INFO, "ActionBar 单播已提交"), "提交已经发生，日志就该如实说已提交");
        assertTrue(this.logged(Level.INFO, "触发者"), "读不到名字时退回固定文案，而不是丢整条日志");
    }

    private Fixture fixture(final Path dir) throws IOException {
        final ScriptEnginePool pool = new ScriptEnginePool(new ScriptSandbox(this.sandboxPackages()), LOGGER);
        final ScriptRegistry registry = new ScriptRegistry();
        final ScriptLoader loader = new ScriptLoader(pool, registry, LOGGER, dir);
        final Path file = dir.resolve("packet_notice.js");

        try (final InputStream stream = PacketNoticeScriptExecutionTest.class
                .getClassLoader()
                .getResourceAsStream(SCRIPT_RESOURCE)) {
            assertNotNull(stream, "测试类路径上找不到 " + SCRIPT_RESOURCE);

            Files.copy(stream, file);
        }

        assertTrue(loader.reload(file), "随包分发的脚本必须能被正式加载器加载");

        final CompiledScript script = registry.get("packet_notice");

        assertNotNull(script, "加载成功后注册表里应有 packet_notice");

        return new Fixture(pool, script);
    }

    /** 与 {@code config.yml} 默认值等价，外加示例脚本需要的 PacketEvents 前缀。 */
    private List<String> sandboxPackages() {
        final List<String> packages = new ArrayList<>(ScriptSandbox.DEFAULT_PACKAGES);

        packages.add("com.github.retrooper.packetevents.");

        return packages;
    }

    private boolean logged(final Level level, final String fragment) {
        return 0 < this.count(level, fragment);
    }

    private long count(final Level level, final String fragment) {
        return this.records.stream()
                .filter(record -> level.equals(record.getLevel()))
                .filter(record -> null != record.getMessage() && record.getMessage().contains(fragment))
                .count();
    }

    /** 只挡住 PE 的类；其余照常委派，否则连 Rhino 自己都加载不了。 */
    private static final class BlockingLoader extends ClassLoader {

        private BlockingLoader() {
            super(ClassLoader.getSystemClassLoader());
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.github.retrooper.packetevents")) {
                throw new ClassNotFoundException(name);
            }

            return super.loadClass(name, resolve);
        }
    }
}
