package com.linmumua.jsenchant.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启停协调器的纯逻辑与竞态校验（用假资源，不需要服务端）。
 *
 * <p>为什么必须测并发：装配是在全局区域线程上分步进行的，停用可能落在任意两步之间。
 * 出了问题不会报错，只会让监听线程或脚本运行态一直活到进程结束——线上表现为「插件已卸载
 * 但仍在改文件/吃 CPU」，靠人眼几乎不可能发现。
 */
class LifecycleCoordinatorTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    @Test
    void 装配只能开始一次() {
        final LifecycleCoordinator coordinator = new LifecycleCoordinator();

        assertTrue(coordinator.begin());
        assertFalse(coordinator.begin(), "重复 ENABLE 不得装配出第二套资源");
    }

    @Test
    void 停用之后不能再开始装配() {
        final LifecycleCoordinator coordinator = new LifecycleCoordinator();

        coordinator.stop(LOGGER);

        assertFalse(coordinator.begin(), "停用之后不得再开始装配");
        assertTrue(coordinator.stopped());
    }

    @Test
    void 停用会关闭全部已登记资源并清空清单() {
        final List<String> closed = Collections.synchronizedList(new ArrayList<>());
        final LifecycleCoordinator coordinator = new LifecycleCoordinator();

        assertTrue(coordinator.begin());
        assertTrue(coordinator.adopt(() -> closed.add("engine-pool")));
        assertTrue(coordinator.adopt(() -> closed.add("script-watcher")));
        assertTrue(coordinator.adopt(() -> closed.add("enchant-watcher")));

        assertEquals(3, coordinator.size());

        assertTrue(coordinator.stop(LOGGER));

        assertEquals(0, coordinator.size(), "停用后不得留下未关闭的资源");
        assertEquals(List.of("enchant-watcher", "script-watcher", "engine-pool"), closed, "应按登记逆序关闭");
    }

    @Test
    void 停用之后新建的资源当场关闭并让装配收尾() {
        final List<String> closed = Collections.synchronizedList(new ArrayList<>());
        final LifecycleCoordinator coordinator = new LifecycleCoordinator();

        assertTrue(coordinator.begin());
        assertTrue(coordinator.adopt(() -> closed.add("before-stop")));

        coordinator.stop(LOGGER);

        assertFalse(
                coordinator.adopt(() -> closed.add("after-stop")),
                "停用后登记必须返回 false，装配侧据此停止后续步骤"
        );

        assertTrue(closed.contains("before-stop"), "停用前登记的资源必须被关闭");
        assertTrue(closed.contains("after-stop"), "停用后新建的资源必须当场关闭，不能留成孤儿");
        assertEquals(0, coordinator.size());
    }

    @Test
    void 重复停用只关闭一次() {
        final AtomicInteger closed = new AtomicInteger();
        final LifecycleCoordinator coordinator = new LifecycleCoordinator();

        assertTrue(coordinator.begin());
        coordinator.adopt(closed::incrementAndGet);

        assertTrue(coordinator.stop(LOGGER));
        assertFalse(coordinator.stop(LOGGER), "重复停用不得重复关闭资源");

        assertEquals(1, closed.get());
    }

    @Test
    void 单个资源关闭失败不阻断其余资源() {
        final List<String> closed = Collections.synchronizedList(new ArrayList<>());
        final LifecycleCoordinator coordinator = new LifecycleCoordinator();

        assertTrue(coordinator.begin());
        coordinator.adopt(() -> closed.add("first"));
        coordinator.adopt(() -> {
            throw new IllegalStateException("关闭失败");
        });
        coordinator.adopt(() -> closed.add("third"));

        coordinator.stop(LOGGER);

        assertEquals(List.of("third", "first"), closed, "一个资源关不掉不能拖住其它资源");
        assertEquals(0, coordinator.size());
    }

    /**
     * 竞态用例：装配线程登记资源的同时停用线程收尾。
     *
     * <p>不变量：每次登记的资源<b>恰好被关闭一次</b>，且停用后清单必须为空。
     * 多轮重复以覆盖两种交错顺序（先登记后停用 / 先停用后登记）。
     */
    @Test
    void 装配与停用并发时每个资源恰好关闭一次() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            final LifecycleCoordinator coordinator = new LifecycleCoordinator();
            final AtomicInteger closed = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);

            assertTrue(coordinator.begin());

            final Thread assembler = new Thread(() -> {
                try {
                    start.await();

                    coordinator.adopt(closed::incrementAndGet);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "JsEnchants-Assembler");

            final Thread stopper = new Thread(() -> {
                try {
                    start.await();

                    coordinator.stop(LOGGER);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "JsEnchants-Stopper");

            assembler.start();
            stopper.start();
            start.countDown();
            done.await();

            assertEquals(0, coordinator.size(), "第 " + round + " 轮：停用后不得留下未关闭的资源");
            assertEquals(1, closed.get(), "第 " + round + " 轮：资源必须恰好被关闭一次");
        }
    }
}
