package com.linmumua.jsenchant.script;

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
 * 脚本加载会执行顶层语句，而顶层语句可能碰 Bukkit API——所以「编译与执行必须在主线程」
 * 是硬要求，不是风格问题。这里验证监视线程只排队、不执行，关闭后排队任务自我取消，
 * 以及删除事件必须走同一个去抖窗口（DELETE→CREATE 的原子保存不能把脚本卸掉）。
 */
class ScriptWatcherTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private ScriptLoader loader(final Path dir, final ScriptRegistry registry) {
        return new ScriptLoader(new ScriptEnginePool(), registry, LOGGER, dir);
    }

    @Test
    void 脚本加载不在监视线程执行(@TempDir final Path dir) throws IOException, InterruptedException {
        final ScriptRegistry registry = new ScriptRegistry();
        final ScriptLoader loader = this.loader(dir, registry);

        final List<Runnable> dispatched = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        try (ScriptWatcher watcher = new ScriptWatcher(LOGGER, loader, registry, dir, task -> {
            dispatched.add(task);
            latch.countDown();
        })) {
            watcher.start();

            Files.writeString(dir.resolve("demo.js"), "function answer() { return 5; }", StandardCharsets.UTF_8);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "脚本变化后应把重载排到主线程");
            assertNull(registry.get("demo"), "监视线程不得编译/执行脚本");

            dispatched.forEach(Runnable::run);

            assertNotNull(registry.get("demo"), "主线程执行之后脚本才可用");
        }
    }

    @Test
    void 删除后立即重建不会让脚本消失(@TempDir final Path dir) throws IOException, InterruptedException {
        final ScriptRegistry registry = new ScriptRegistry();
        final ScriptLoader loader = this.loader(dir, registry);
        final Path script = dir.resolve("demo.js");

        Files.writeString(script, "function answer() { return 1; }", StandardCharsets.UTF_8);
        loader.reloadAll();

        final List<Runnable> dispatched = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        try (ScriptWatcher watcher = new ScriptWatcher(LOGGER, loader, registry, dir, task -> {
            dispatched.add(task);
            latch.countDown();
        })) {
            watcher.start();

            // 模拟编辑器的原子保存：先删再写，两步都在去抖窗口内。
            Files.delete(script);
            Files.writeString(script, "function answer() { return 2; }", StandardCharsets.UTF_8);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "删除/重建后仍应派发一次重载");
            dispatched.forEach(Runnable::run);

            assertNotNull(registry.get("demo"), "短窗口内的 DELETE 不能立刻卸载正在用的脚本");
        }
    }

    @Test
    void 真删除在去抖后才卸载(@TempDir final Path dir) throws IOException, InterruptedException {
        final ScriptRegistry registry = new ScriptRegistry();
        final ScriptLoader loader = this.loader(dir, registry);
        final Path script = dir.resolve("demo.js");

        Files.writeString(script, "function answer() { return 1; }", StandardCharsets.UTF_8);
        loader.reloadAll();

        final List<Runnable> dispatched = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        try (ScriptWatcher watcher = new ScriptWatcher(LOGGER, loader, registry, dir, task -> {
            dispatched.add(task);
            latch.countDown();
        })) {
            watcher.start();

            Files.delete(script);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "删除后应派发一次任务");
            assertNotNull(registry.get("demo"), "去抖结束前不得卸载");

            dispatched.forEach(Runnable::run);

            assertNull(registry.get("demo"), "文件确实不在了才卸载");
        }
    }

    @Test
    void 关闭后已派发的重载不再执行(@TempDir final Path dir) throws IOException, InterruptedException {
        final ScriptRegistry registry = new ScriptRegistry();
        final ScriptLoader loader = this.loader(dir, registry);

        final List<Runnable> dispatched = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        final ScriptWatcher watcher = new ScriptWatcher(LOGGER, loader, registry, dir, task -> {
            dispatched.add(task);
            latch.countDown();
        });

        watcher.start();

        Files.writeString(dir.resolve("demo.js"), "function answer() { return 1; }", StandardCharsets.UTF_8);

        assertTrue(latch.await(15, TimeUnit.SECONDS), "应先收到一次派发的重载");

        watcher.close();
        watcher.close();

        dispatched.forEach(Runnable::run);

        assertNull(registry.get("demo"), "插件关闭后才轮到主线程执行的任务必须自我取消");
        assertEquals(0, registry.size());
    }
}
