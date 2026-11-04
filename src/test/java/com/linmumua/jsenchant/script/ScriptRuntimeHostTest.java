package com.linmumua.jsenchant.script;

import com.linmumua.jsenchant.platform.RegionTasks;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 延迟/周期回调在真实 Rhino 上的执行语义。
 *
 * <p>钉住三件线上会出错的事：
 * <ul>
 *   <li>按函数名重查真的调到了脚本里的函数（这一段坏了，所有 ctx.delay 都静默失效）；</li>
 *   <li>热重载后到期的旧回调执行的是<b>新版本</b>脚本，而不是派发那一刻的旧代码；</li>
 *   <li>每名玩家每个附魔的存活名额有上限，链结束后归还——否则 onTrigger 里一句 repeat
 *       会让任务随攻击次数无限累积。</li>
 * </ul>
 *
 * <p>调度器替身只把任务收进队列，由测试手动「推进一拍」，这样能断言「到期前没有执行」。
 */
class ScriptRuntimeHostTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    /** 脚本把调用记到这里；必须 public，且包名在沙箱放行前缀内。 */
    public static final class Sink {

        private final List<String> hits = new ArrayList<>();

        public void hit(final String value) {
            this.hits.add(value);
        }

        List<String> hits() {
            return this.hits;
        }
    }

    @Test
    void 延迟回调按函数名调到脚本里的函数() {
        final Fixture fixture = new Fixture(16);
        fixture.publish("function later(sink) { sink.hit('v1'); }", 1L);

        final Sink sink = new Sink();
        assertTrue(fixture.host().later(fixture.anchor, 20L, "later", sink));

        assertTrue(sink.hits().isEmpty(), "到期之前不能执行");

        fixture.tasks.tick();

        assertEquals(List.of("v1"), sink.hits());
    }

    @Test
    void 热重载后到期的回调执行新版本() {
        final Fixture fixture = new Fixture(16);
        fixture.publish("function later(sink) { sink.hit('old'); }", 1L);

        final Sink sink = new Sink();
        assertTrue(fixture.host().later(fixture.anchor, 20L, "later", sink));

        // 回调排队期间脚本被改写并重载。
        fixture.publish("function later(sink) { sink.hit('new'); }", 2L);
        fixture.tasks.tick();

        assertEquals(List.of("new"), sink.hits(), "旧回调必须跑新代码，否则 /jse reload 之后还会执行改掉的逻辑");
    }

    @Test
    void 脚本被删除后回调不再执行且归还名额() {
        final Fixture fixture = new Fixture(16);
        fixture.publish("function later(sink) { sink.hit('x'); }", 1L);

        final Sink sink = new Sink();
        assertTrue(fixture.host().later(fixture.anchor, 20L, "later", sink));
        assertEquals(1, fixture.live());

        fixture.scripts.remove("demo");
        fixture.tasks.tick();

        assertTrue(sink.hits().isEmpty());
        assertEquals(0, fixture.live());
    }

    @Test
    void 有限次数跑满后停下并归还名额() {
        final Fixture fixture = new Fixture(16);
        fixture.publish("function tick(sink) { sink.hit('t'); }", 1L);

        final Sink sink = new Sink();
        assertTrue(fixture.host().repeat(fixture.anchor, 20L, 20L, 3, "tick", sink));
        assertEquals(1, fixture.live(), "整条链只占一个名额");

        for (int i = 0; i < 10; i++) {
            fixture.tasks.tick();
        }

        assertEquals(3, sink.hits().size(), "次数用完必须停下");
        assertEquals(0, fixture.live(), "链结束后名额必须归还，否则玩家迟早再也排不上回调");
        assertEquals(0, fixture.tasks.pending(), "不能留下空转的任务");
    }

    @Test
    void 超过上限的回调被拒绝已在跑的不受影响() {
        final Fixture fixture = new Fixture(2);
        fixture.publish("function later(sink) { sink.hit('x'); }", 1L);

        final Sink sink = new Sink();
        assertTrue(fixture.host().later(fixture.anchor, 20L, "later", sink));
        assertTrue(fixture.host().repeat(fixture.anchor, 20L, 20L, 0, "later", sink));
        assertFalse(fixture.host().later(fixture.anchor, 20L, "later", sink), "名额满了必须拒绝");

        assertEquals(2, fixture.live());

        fixture.tasks.tick();
        assertEquals(1, fixture.live(), "一次性延迟执行完归还名额，无限周期继续占着");
        assertTrue(fixture.host().later(fixture.anchor, 20L, "later", sink), "归还后又能排上");
    }

    @Test
    void 名额按附魔隔离() {
        final Fixture fixture = new Fixture(1);
        fixture.publish("function later(sink) {}", 1L);

        assertTrue(fixture.host("a").later(fixture.anchor, 20L, "later"));
        assertTrue(fixture.host("b").later(fixture.anchor, 20L, "later"), "一个附魔用满不该挤掉另一个附魔");
        assertFalse(fixture.host("a").later(fixture.anchor, 20L, "later"));
    }

    @Test
    void 实体退休时无限周期归还名额() {
        final Fixture fixture = new Fixture(16);
        fixture.publish("function later(sink) {}", 1L);

        assertTrue(fixture.host().repeat(fixture.anchor, 20L, 20L, 0, "later"));
        assertEquals(1, fixture.live());

        fixture.tasks.retireAll();

        assertEquals(0, fixture.live());
    }

    // --- 替身 ---

    private static final class Fixture {

        final QueueTasks tasks = new QueueTasks();
        final Map<String, CompiledScript> scripts = new ConcurrentHashMap<>();
        final ScriptEnginePool pool = new ScriptEnginePool(LOGGER);
        final CallbackBudget budget;
        final UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        final Entity anchor = anchor(this.id);

        Fixture(final int limit) {
            this.budget = new CallbackBudget(limit);
        }

        void publish(final String source, final long version) {
            final CompiledScript script = new CompiledScript("demo", source, version, version);

            this.scripts.put("demo", script);
            this.pool.publish(script);
        }

        ScriptRuntimeHost host() {
            return this.host("demo_enchant");
        }

        ScriptRuntimeHost host(final String enchantId) {
            return new ScriptRuntimeHost(null, enchantId, "demo", this.scripts::get,
                    this.pool, this.tasks, this.budget, LOGGER);
        }

        int live() {
            return this.budget.live(this.id, "demo_enchant");
        }
    }

    /** 收进队列、手动推进；周期任务每拍跑一次，退休时回调 retired。 */
    private static final class QueueTasks implements RegionTasks {

        private record Job(Runnable task, Runnable retired, boolean periodic) {
        }

        private List<Job> queue = new ArrayList<>();

        /** 推进一拍：本拍之前排上的任务各跑一次，一次性任务出队。 */
        void tick() {
            final List<Job> due = this.queue;

            this.queue = new ArrayList<>();

            for (final Job job : due) {
                if (job.periodic()) {
                    this.queue.add(job);
                }

                job.task().run();
            }
        }

        void retireAll() {
            final List<Job> due = this.queue;

            this.queue = new ArrayList<>();

            for (final Job job : due) {
                if (null != job.retired()) {
                    job.retired().run();
                }
            }
        }

        int pending() {
            return this.queue.size();
        }

        @Override
        public boolean later(final Entity entity, final long delayTicks, final Runnable task, final Runnable retired) {
            this.queue.add(new Job(task, retired, false));

            return true;
        }

        @Override
        public boolean repeat(final Entity entity, final long delayTicks, final long periodTicks,
                              final Runnable task, final Runnable retired) {
            this.queue.add(new Job(task, retired, true));

            return true;
        }

        @Override
        public void global(final Runnable task) {
            task.run();
        }

        @Override
        public boolean entity(final Entity entity, final Runnable task, final Runnable retired) {
            task.run();

            return true;
        }

        @Override
        public void region(final Location location, final Runnable task) {
            task.run();
        }

        @Override
        public boolean owns(final Entity entity) {
            return true;
        }

        @Override
        public boolean owns(final Location location, final int squareRadiusChunks) {
            return true;
        }

        @Override
        public boolean active() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static Entity anchor(final UUID id) {
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{ Entity.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeAnchor";
                    default -> throw new UnsupportedOperationException(
                            "调度只该读 UUID，被调用的方法: " + method.getName());
                }
        );
    }
}
