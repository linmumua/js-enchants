package com.linmumua.jsenchant.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件是服主手改的入口，重点不是「能读」，而是「读坏时不破坏已生效的设置」：
 * 一次缩进写错若让 Lore 配置退回默认值，玩家看到的物品说明会莫名变化，比报错更难排查。
 */
class ConfigServiceTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final String VALID = """
            lore:
              enabled: false
              format: "<yellow>{display}"
            debug: true
            """;

    private Path write(final Path dir, final String content) throws IOException {
        final Path file = dir.resolve("config.yml");

        Files.writeString(file, content, StandardCharsets.UTF_8);

        return file;
    }

    @Test
    void 有效配置替换快照(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, VALID));

        assertTrue(service.load());
        assertEquals("<yellow>{display}", service.current().loreFormat());
        assertFalse(service.current().loreEnabled());
        assertTrue(service.current().debug());
    }

    @Test
    void 未写出的项沿用默认值(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                debug: false
                """));

        assertTrue(service.load());
        assertEquals("<gray>{display} <dark_gray>{tier}", service.current().loreFormat());
        assertTrue(service.current().loreEnabled());
        assertFalse(service.current().debug());
    }

    @Test
    void 语法坏掉时保留上一版(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, VALID);
        final ConfigService service = new ConfigService(LOGGER, file);

        assertTrue(service.load());

        Files.writeString(file, "lore: [oops\n", StandardCharsets.UTF_8);

        assertFalse(service.load(), "坏配置必须返回失败");
        assertEquals("<yellow>{display}", service.current().loreFormat(), "已生效的格式不能被清掉");
        assertFalse(service.current().loreEnabled());
    }

    @Test
    void 类型写错时保留上一版(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, VALID);
        final ConfigService service = new ConfigService(LOGGER, file);

        assertTrue(service.load());

        // 引号包住的 "maybe" 不会被 YAML 解析成布尔，属于真实的类型错误。
        Files.writeString(file, """
                lore:
                  enabled: "maybe"
                """, StandardCharsets.UTF_8);

        assertFalse(service.load(), "类型错误不能被静默当成默认值");
        assertFalse(service.current().loreEnabled(), "上一版的 enabled 应保持不变");
    }

    @Test
    void 文件缺失时保留默认值并返回失败(@TempDir final Path dir) {
        final ConfigService service = new ConfigService(LOGGER, dir.resolve("config.yml"));

        assertFalse(service.load());
        assertEquals("<gray>{display} <dark_gray>{tier}", service.current().loreFormat());
        assertTrue(service.current().loreEnabled());
    }

    /**
     * 随插件分发的 config.yml 是服主第一次看到的文件：它必须能被同一套解析器读出，
     * 且值与代码里的默认值一致——否则「没改配置也生效」这件事就无从解释。
     */
    @Test
    void 分发的config_yml可解析且与默认值一致() {
        final ConfigService service = new ConfigService(LOGGER, Path.of("src/main/resources/config.yml"));

        assertTrue(service.load(), "随插件分发的 config.yml 必须能解析");
        assertEquals("<gray>{display} <dark_gray>{tier}", service.current().loreFormat());
        assertTrue(service.current().loreEnabled());
        assertFalse(service.current().debug());

        // 分发配置里的 legacy-formats 必须与代码默认值逐项相同，否则「旧物品的行认不出来」
        // 这件事会随安装方式漂移：装的是 jar 还是手写配置，行为不一样。
        assertEquals(
                List.of(
                        "<gray>{display} <dark_gray>{level}",
                        "<green>{display} <dark_green>{level}",
                        "<aqua>{display} <dark_aqua>{level}",
                        "<light_purple>{display} <dark_purple>{level}"
                ),
                service.current().loreLegacyFormats(),
                "上一版的四档默认模板必须登记在案，否则升级后旧 Lore 行永久残留"
        );

        // 战利品注入：分发配置写出来的值必须与代码默认值逐项相同。
        final ConfigService.LootBooks loot = service.current().lootBooks();

        assertTrue(loot.enabled());
        assertEquals(0.10D, loot.chance());
        assertEquals(List.of("minecraft:chests/*"), loot.tables());
        assertEquals(1, loot.anvilLevelCost());
    }

    /**
     * 战利品注入是「旧配置升级」场景：已经存在的 config.yml 没有这一节时必须落到
     * 明确默认值（默认开着），而不是静默关掉。
     */
    @Test
    void 缺loot_books节时用默认值(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, VALID));

        assertTrue(service.load());

        final ConfigService.LootBooks loot = service.current().lootBooks();

        assertTrue(loot.enabled(), "缺节等同于用默认值，而不是关闭");
        assertEquals(0.10D, loot.chance());
        assertEquals(List.of("minecraft:chests/*"), loot.tables());
        assertEquals(1, loot.anvilLevelCost());
    }

    @Test
    void loot_books节被完整解析(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                loot-books:
                  enabled: false
                  chance: 0.25
                  tables:
                    - "minecraft:chests/simple_dungeon"
                    - "minecraft:chests/village/*"
                  anvil-level-cost: 3
                """));

        assertTrue(service.load());

        final ConfigService.LootBooks loot = service.current().lootBooks();

        assertFalse(loot.enabled());
        assertEquals(0.25D, loot.chance());
        assertEquals(List.of("minecraft:chests/simple_dungeon", "minecraft:chests/village/*"), loot.tables());
        assertEquals(3, loot.anvilLevelCost());
    }

    /**
     * 写坏 {@code loot-books} 的某一行，整份配置都要被拒绝，且上一版概率原样保留——
     * 概率突然从 0.4 掉回默认 0.1 会让服主完全找不到头绪。
     */
    private ConfigService rejectLootBlocks(final Path dir, final String broken) throws IOException {
        final Path file = this.write(dir, """
                loot-books:
                  chance: 0.4
                """);
        final ConfigService service = new ConfigService(LOGGER, file);

        assertTrue(service.load());

        Files.writeString(file, "loot-books:\n" + broken, StandardCharsets.UTF_8);

        assertFalse(service.load(), "非法 loot-books 必须返回失败: " + broken);
        assertEquals(0.4D, service.current().lootBooks().chance(), "上一版设置必须保留: " + broken);

        return service;
    }

    @Test
    void 非法概率保留上一版(@TempDir final Path dir) throws IOException {
        final List<String> broken = List.of(
                "  chance: 1.5\n",
                "  chance: -0.1\n",
                "  chance: \"0.5\"\n",
                "  chance: .nan\n",
                "  chance: true\n"
        );

        for (final String line : broken) {
            this.rejectLootBlocks(dir, line);
        }
    }

    @Test
    void 非法铁砧费用保留上一版(@TempDir final Path dir) throws IOException {
        final List<String> broken = List.of(
                "  anvil-level-cost: 0\n",
                "  anvil-level-cost: -1\n",
                "  anvil-level-cost: 40\n",
                "  anvil-level-cost: 2.5\n",
                "  anvil-level-cost: \"1\"\n"
        );

        for (final String line : broken) {
            this.rejectLootBlocks(dir, line);
        }
    }

    /** 原版铁砧在总花费达到 40 级时拒绝合成，所以 39 是最后一个「还能用」的档位。 */
    @Test
    void 铁砧费用上限内可解析(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                loot-books:
                  anvil-level-cost: 39
                """));

        assertTrue(service.load());
        assertEquals(39, service.current().lootBooks().anvilLevelCost());
    }

    /**
     * 战利品表只放宽到原版容器的精确 key 或尾部通配：放宽到实体、钓鱼或任意 namespace
     * 会让「哪里会掉书」变得不可预期，所以这些写法一律整份拒绝。
     */
    @Test
    void 非法战利品表保留上一版(@TempDir final Path dir) throws IOException {
        final List<String> broken = List.of(
                "  tables: []\n",
                "  tables:\n    - \"minecraft:entities/zombie\"\n",
                "  tables:\n    - \"minecraft:fishing/treasure\"\n",
                "  tables:\n    - \"chests/simple_dungeon\"\n",
                "  tables:\n    - \"minecraft:chests/*/nested\"\n",
                "  tables:\n    - \"minecraft:chests/\"\n",
                "  tables:\n    - \"*\"\n",
                "  tables:\n    - 123\n",
                "  tables: \"minecraft:chests/*\"\n"
        );

        for (final String line : broken) {
            this.rejectLootBlocks(dir, line);
        }
    }

    @Test
    void 战利品表是防御拷贝(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, VALID));

        assertTrue(service.load());
        assertThrows(
                UnsupportedOperationException.class,
                () -> service.current().lootBooks().tables().add("minecraft:chests/injected")
        );
    }
}
