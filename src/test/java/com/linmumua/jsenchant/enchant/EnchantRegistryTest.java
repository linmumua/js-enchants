package com.linmumua.jsenchant.enchant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附魔表的业务价值只有一条：<b>重载出错时玩家手上的附魔不能变没</b>。
 * 这里锁住「整批原子发布（任一文件失败就不发布）」「失败原因能带出去」「快照不可变」
 * 三件事，外加首次启动无旧表时的明确失败。
 */
class EnchantRegistryTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private Path write(final Path dir, final String name, final String content) throws IOException {
        final Path file = dir.resolve(name);

        Files.writeString(file, content, StandardCharsets.UTF_8);

        return file;
    }

    @Test
    void 重载成功后整表替换(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "a.yml", "id: a\n");
        assertEquals(1, registry.loadAll(dir));

        this.write(dir, "b.yml", "id: b\n");
        Files.delete(dir.resolve("a.yml"));

        final EnchantLoadReport report = registry.reload(dir);

        assertTrue(report.success());
        assertTrue(report.published());
        assertEquals(1, report.loaded());
        assertNotNull(registry.get("b"));
        assertNull(registry.get("a"), "被删掉的定义必须真的消失");
    }

    @Test
    void 目录不可用时保留旧表(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "a.yml", "id: a\n");
        registry.loadAll(dir);

        // 把「附魔目录」指向一个普通文件的子路径：createDirectories 必然失败。
        final Path blocked = dir.resolve("a.yml").resolve("enchants");
        final EnchantLoadReport report = registry.reload(blocked);

        assertFalse(report.directoryReady());
        assertFalse(report.published());
        assertFalse(report.success());
        assertEquals(1, report.loaded(), "报告的应是保留下来的旧快照数量");
        assertNotNull(registry.get("a"), "目录不可用时绝不能把旧定义清空");
        assertEquals(1, registry.size());
    }

    @Test
    void 坏文件导致整批不发布(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "a.yml", "id: a\n");
        registry.loadAll(dir);

        // 这次同时出现「一个新的合法定义」和「一个坏文件」：整批都不许发布，
        // 否则一次手误就会把 b 偷偷换上去、而 a 之外的其他附魔被悄悄下掉。
        this.write(dir, "b.yml", "id: b\n");
        this.write(dir, "bad.yml", "id: Bad Enchant!\n");

        final EnchantLoadReport report = registry.reload(dir);

        assertTrue(report.directoryReady());
        assertFalse(report.published(), "有文件校验失败时整批不发布");
        assertFalse(report.success());
        assertEquals(1, report.loaded(), "load 应是保留下来的旧快照数量");
        assertEquals(1, report.failed());
        assertTrue(report.failures().get(0).startsWith("bad.yml"), "失败明细要指名文件");
        assertNotNull(registry.get("a"), "旧快照必须完全保留");
        assertNull(registry.get("b"), "新文件不能以「部分表」的形式生效");
    }

    @Test
    void 重复id导致整批不发布且旧表保留(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "a.yml", "id: a\n");
        registry.loadAll(dir);

        // 两个文件抢同一个 id：附魔行为会随文件列举顺序漂移，必须整批拒绝。
        this.write(dir, "dup-1.yml", "id: dup\n");
        this.write(dir, "dup-2.yml", "id: dup\n");

        final EnchantLoadReport report = registry.reload(dir);

        assertFalse(report.published(), "重复 id 属于校验失败，整批不发布");
        assertFalse(report.success());
        assertEquals(1, report.loaded(), "仍是上一份快照的数量");
        assertEquals(1, report.failed());
        assertTrue(report.failures().get(0).contains("重复"), "要说明是重复: " + report.failures().get(0));
        assertTrue(report.failures().get(0).contains("dup"), "要指名冲突的 id: " + report.failures().get(0));
        assertNotNull(registry.get("a"), "旧快照必须完全保留");
        assertNull(registry.get("dup"), "重复 id 的任一方都不能生效");
    }

    @Test
    void 首次启动遇坏文件明确失败(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "bad.yml", "id: Bad Enchant!\n");

        final EnchantLoadReport report = registry.reload(dir);

        // 没有旧表可退：必须如实报「没有发布任何定义」，而不是静默变成 0 个。
        assertFalse(report.published());
        assertFalse(report.success());
        assertEquals(0, report.loaded());
        assertEquals(1, report.failed());
        assertEquals(0, registry.size());
        assertTrue(report.summary().contains("没有任何可用定义"), "摘要要说清当前是空表: " + report.summary());
    }

    @Test
    void 禁用定义被跳过(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "off.yml", """
                id: off
                enabled: false
                """);

        assertEquals(0, registry.loadAll(dir));
        assertNull(registry.get("off"));
    }

    @Test
    void 对外快照不可变(@TempDir final Path dir) throws IOException {
        final EnchantRegistry registry = new EnchantRegistry(LOGGER);

        this.write(dir, "a.yml", "id: a\n");
        registry.loadAll(dir);

        final Map<String, EnchantDefinition> snapshot = registry.all();

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put("hack", snapshot.get("a")),
                "调用方不能绕过注册表改动生效状态"
        );
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
