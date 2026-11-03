package com.linmumua.jsenchant.config;

import com.linmumua.jsenchant.enchant.EnchantRarity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lore.rarity} 与 {@code datapack} 两段新配置的读取规则。
 *
 * <p>这里的每条断言都对应一个「改错了却看不出来」的真实故障：
 * 模板少一个占位符会让物品上只剩名字；{@code weight: 0} 让附魔永远抽不到；
 * 调色板缺项若不回落到旧模板，升级后玩家会看到物品说明突然换色。
 */
class ConfigLoreDatapackTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final String LEGACY_FORMAT = "<yellow>{display} Lv{level}";

    private Path write(final Path dir, final String content) throws IOException {
        final Path file = dir.resolve("config.yml");

        Files.writeString(file, content, StandardCharsets.UTF_8);

        return file;
    }

    @Test
    void 调色板四档独立读取(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "%s"
                  rarity:
                    common: "<gray>{display} <dark_gray>{level}"
                    uncommon: "<green>{display} <dark_green>{level}"
                    rare: "<aqua>{display} <dark_aqua>{level}"
                    epic: "<light_purple>{display} <dark_purple>{level}"
                """.formatted(LEGACY_FORMAT)));

        assertTrue(service.load());

        final ConfigService.LorePalette palette = service.current().lorePalette();

        assertEquals("<gray>{display} <dark_gray>{level}", palette.templateFor(EnchantRarity.COMMON));
        assertEquals("<green>{display} <dark_green>{level}", palette.templateFor(EnchantRarity.UNCOMMON));
        assertEquals("<aqua>{display} <dark_aqua>{level}", palette.templateFor(EnchantRarity.RARE));
        assertEquals("<light_purple>{display} <dark_purple>{level}", palette.templateFor(EnchantRarity.EPIC));
        assertEquals(palette.common(), palette.templateFor(null), "稀有度缺失时按 common，调用方不必自己兜底");
    }

    @Test
    void 调色板缺项回落到loreFormat(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "%s"
                  rarity:
                    rare: "<aqua>{display} <dark_aqua>{level}"
                """.formatted(LEGACY_FORMAT)));

        assertTrue(service.load());

        final ConfigService.LorePalette palette = service.current().lorePalette();

        assertEquals("<aqua>{display} <dark_aqua>{level}", palette.templateFor(EnchantRarity.RARE));
        assertEquals(LEGACY_FORMAT, palette.templateFor(EnchantRarity.COMMON), "升级前的旧配置不能因为缺项就换色");
        assertEquals(LEGACY_FORMAT, palette.templateFor(EnchantRarity.UNCOMMON));
        assertEquals(LEGACY_FORMAT, palette.templateFor(EnchantRarity.EPIC));
    }

    @Test
    void 整节缺席时四档都沿用loreFormat(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "%s"
                """.formatted(LEGACY_FORMAT)));

        assertTrue(service.load());

        assertEquals(LEGACY_FORMAT, service.current().lorePalette().templateFor(EnchantRarity.EPIC));
    }

    @Test
    void 模板缺占位符只回落该项而不拖垮整份配置(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, """
                lore:
                  format: "<gray>{display} <dark_gray>{level}"
                  rarity:
                    rare: "<aqua>{display} <dark_aqua>{level}"
                """);
        final ConfigService service = new ConfigService(LOGGER, file);

        assertTrue(service.load());

        final String accepted = service.current().lorePalette().templateFor(EnchantRarity.RARE);

        // 只含一个占位符：物品上会只剩名字（或只剩等级），而现场看不出是模板写错了。
        Files.writeString(file, """
                lore:
                  format: "<gray>{display} <dark_gray>{level}"
                  rarity:
                    rare: "<aqua>{display}"
                """, StandardCharsets.UTF_8);

        // 刻意**不**整份拒绝：模板决定 Lore 的纯文本形态，而旧物品的自有行靠纯文本相等识别。
        // 整份回退会把回落模板换成默认值，旧行从此认不出来、永久残留。
        assertTrue(service.load(), "单个模板写错不应拒绝整份配置");
        assertEquals(
                "<gray>{display} <dark_gray>{level}",
                service.current().lorePalette().templateFor(EnchantRarity.RARE),
                "写错的项回落到 lore.format"
        );
        assertNotEquals(accepted, service.current().lorePalette().templateFor(EnchantRarity.RARE));
    }

    @Test
    void datapack缺节时用默认参数(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, "debug: false\n"));

        assertTrue(service.load());

        final ConfigService.Datapack datapack = service.current().datapack();

        assertTrue(datapack.enabled());
        assertEquals(10, datapack.weight());
        assertEquals(1, datapack.minCostBase());
        assertEquals(11, datapack.minCostPerLevel());
        assertEquals(21, datapack.maxCostBase());
        assertEquals(11, datapack.maxCostPerLevel());
        assertEquals(1, datapack.anvilCost());
    }

    @Test
    void datapack读取自定义值并允许base为0(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                datapack:
                  enabled: false
                  weight: 3
                  min-cost: { base: 0, per-level: 5 }
                  max-cost: { base: 40, per-level: 7 }
                  anvil-cost: 2
                """));

        assertTrue(service.load());

        final ConfigService.Datapack datapack = service.current().datapack();

        assertFalse(datapack.enabled());
        assertEquals(3, datapack.weight());
        assertEquals(0, datapack.minCostBase());
        assertEquals(5, datapack.minCostPerLevel());
        assertEquals(40, datapack.maxCostBase());
        assertEquals(7, datapack.maxCostPerLevel());
        assertEquals(2, datapack.anvilCost());
    }

    @Test
    void datapack数值越界整份配置拒绝(@TempDir final Path dir) throws IOException {
        final String[] invalid = {
                "datapack:\n  weight: 0\n",
                "datapack:\n  weight: 1.5\n",
                "datapack:\n  weight: \"10\"\n",
                "datapack:\n  min-cost: { per-level: 0 }\n",
                "datapack:\n  max-cost: { base: -1 }\n",
                "datapack:\n  anvil-cost: 0\n",
                "datapack:\n  anvil-cost: 2.5\n"
        };

        for (final String content : invalid) {
            final ConfigService service = new ConfigService(LOGGER, this.write(dir, content));

            assertFalse(service.load(), "非法数值必须拒绝整份配置: " + content.trim());
            // 拒绝之后仍是默认快照，而不是「解析了一半」的状态。
            assertEquals(10, service.current().datapack().weight(), "拒绝后不能改写已生效设置: " + content.trim());
        }
    }

    // ------------------------------------------------------------------
    // 阶位占位符与 legacy-formats
    // ------------------------------------------------------------------

    /** 只收 SEVERE 的 Handler：用来断言「写错必须留痕」，而不是静默回落。 */
    private static Handler severeCollector(final List<String> sink) {
        return new Handler() {

            @Override
            public void publish(final LogRecord record) {
                if (Level.SEVERE.equals(record.getLevel())) {
                    sink.add(String.valueOf(record.getMessage()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void 只含tier的模板是合法形态(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "<gray>{display} <dark_gray>{level}"
                  rarity:
                    common: "<gray>{display} <dark_gray>{tier}"
                    epic: "<light_purple>{display}<dark_gray> · </dark_gray><light_purple>{tier}"
                """));

        assertTrue(service.load());

        final ConfigService.LorePalette palette = service.current().lorePalette();

        assertEquals(
                "<gray>{display} <dark_gray>{tier}",
                palette.templateFor(EnchantRarity.COMMON),
                "只含 {tier}、不含 {level} 的模板必须被接受：阶位就是新的等级写法"
        );
        assertEquals(
                "<light_purple>{display}<dark_gray> · </dark_gray><light_purple>{tier}",
                palette.templateFor(EnchantRarity.EPIC),
                "带 MiniMessage 标签与分隔符的阶位模板同样合法"
        );
        assertEquals(
                "<gray>{display} <dark_gray>{level}",
                palette.templateFor(EnchantRarity.RARE),
                "没写的档继续回落到 lore.format"
        );
    }

    @Test
    void 两种等级占位符都没有时回落该项并记SEVERE(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "<gray>{display} <dark_gray>{level}"
                  rarity:
                    rare: "<aqua>固定文本"
                """));
        final List<String> severe = new ArrayList<>();
        final Handler handler = severeCollector(severe);

        LOGGER.addHandler(handler);

        try {
            assertTrue(service.load(), "单项写错不能拒绝整份配置——整份回退会让更多旧 Lore 行失联");
        } finally {
            LOGGER.removeHandler(handler);
        }

        assertEquals(
                "<gray>{display} <dark_gray>{level}",
                service.current().lorePalette().templateFor(EnchantRarity.RARE),
                "写错的项回落到 lore.format"
        );
        assertEquals(1, severe.size(), "必须留一条 SEVERE，否则服主看不出模板根本没生效");
        assertTrue(severe.get(0).contains("{level}"), "错误消息要说明可以用 {level}");
        assertTrue(severe.get(0).contains("{tier}"), "错误消息也要说明可以用 {tier}");
    }

    @Test
    void legacy_formats被读成不可变列表(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "<gray>{display} <dark_gray>{tier}"
                  legacy-formats:
                    - "<gray>{display} <dark_gray>Lv.{level}"
                    - "<aqua>{display}<dark_aqua> · </dark_aqua><aqua>Lv.{level}</aqua>"
                """));

        assertTrue(service.load());

        final List<String> legacy = service.current().loreLegacyFormats();

        assertEquals(
                List.of(
                        "<gray>{display} <dark_gray>Lv.{level}",
                        "<aqua>{display}<dark_aqua> · </dark_aqua><aqua>Lv.{level}</aqua>"
                ),
                legacy,
                "顺序必须保持配置里的原样，排查时才对得上"
        );
        assertThrows(UnsupportedOperationException.class, () -> legacy.add("x"), "必须是不可变防御拷贝");
        assertEquals(
                legacy,
                service.current().lorePalette().legacyFormats(),
                "调色板要带上同一份登记表：它是它抵达 EnchantApplier 的唯一通道"
        );
    }

    @Test
    void legacy_formats写错时只按空列表处理(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "<gray>{display} <dark_gray>{tier}"
                  legacy-formats: "<gray>{display} <dark_gray>Lv.{level}"
                """));
        final List<String> severe = new ArrayList<>();
        final Handler handler = severeCollector(severe);

        LOGGER.addHandler(handler);

        try {
            assertTrue(
                    service.load(),
                    "这一项只影响旧行能不能被清理，写错不该连累已生效的配色与开关"
            );
        } finally {
            LOGGER.removeHandler(handler);
        }

        assertEquals(List.of(), service.current().loreLegacyFormats(), "类型写错按空列表处理");
        assertEquals(1, severe.size(), "必须留痕，否则服主以为旧行清理是「插件坏了」");
    }

    @Test
    void legacy_formats里的空白项被丢弃(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  legacy-formats:
                    - ""
                    - "<gray>{display} <dark_gray>Lv.{level}"
                    - "   "
                """));

        assertTrue(service.load());

        assertEquals(
                List.of("<gray>{display} <dark_gray>Lv.{level}"),
                service.current().loreLegacyFormats(),
                "空白模板渲染出的纯文本是空串，留着会让空 Lore 行被误判成自有行"
        );
    }

    @Test
    void 缺legacy_formats节时为空列表(@TempDir final Path dir) throws IOException {
        final ConfigService service = new ConfigService(LOGGER, this.write(dir, """
                lore:
                  format: "<gray>{display} <dark_gray>{tier}"
                """));

        assertTrue(service.load());

        assertEquals(List.of(), service.current().loreLegacyFormats(), "旧配置没有这一节是正常的升级场景");
        assertEquals(
                List.of(),
                service.current().lorePalette().legacyFormats(),
                "调色板侧同样不能凭空造出登记项"
        );
    }
}
