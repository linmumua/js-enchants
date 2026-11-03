package com.linmumua.jsenchant.enchant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监视线程的职责只有「发现变化并把重载排到主线程」。这条边界一旦被越过，
 * 重载就会在监视线程跑；关闭后残留的任务则会在插件卸载后继续改状态。
 */
class EnchantWatcherTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    @Test
    void 重载只派发不自己执行(@TempDir final Path dir) throws IOException, InterruptedException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        registry.loadAll(dir);

        final List<Runnable> dispatched = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        try (EnchantWatcher watcher = new EnchantWatcher(LOGGER, registry, dir, task -> {
            dispatched.add(task);
            latch.countDown();
        })) {
            watcher.start();

            Files.writeString(dir.resolve("a.yml"), "id: a\n", StandardCharsets.UTF_8);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "定义变化后应把整表重载排到主线程");
            assertNull(registry.get("a"), "监视线程自己不得执行重载");

            // 模拟主线程调度器真正执行任务。
            dispatched.forEach(Runnable::run);

            assertNotNull(registry.get("a"), "主线程执行之后定义才生效");
            assertEquals(1, registry.size());
        }
    }

    @Test
    void 关闭后不再派发且可重复关闭(@TempDir final Path dir) throws IOException, InterruptedException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);
        final List<Runnable> dispatched = new CopyOnWriteArrayList<>();

        final EnchantWatcher watcher = new EnchantWatcher(LOGGER, registry, dir, dispatched::add);

        watcher.start();
        watcher.close();
        watcher.close();

        Files.writeString(dir.resolve("a.yml"), "id: a\n", StandardCharsets.UTF_8);

        // 越过一个去抖窗口：关闭后既不该有新任务，也不该卡住。
        Thread.sleep(1_200L);

        assertTrue(dispatched.isEmpty(), "关闭后不得残留重载任务");
        assertNull(registry.get("a"));
    }
}
