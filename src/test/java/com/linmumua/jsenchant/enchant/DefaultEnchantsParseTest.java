package com.linmumua.jsenchant.enchant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随包分发的每一份附魔定义都必须能被真实解析器读进来。
 *
 * <p>为什么整目录跑一遍而不是逐个写用例：yml 里最容易出错的恰恰是手写的那部分——
 * {@code options} 里少了引号的 {@code #标签}、被 YAML 当成映射符的冒号、
 * 写成 {@code [1, 1, 2]} 的列表、{@code -10} 这种带符号数字。
 * 这些错误在 {@code /jse reload} 之前都不会暴露，而单测里只钉住一份文件等于没覆盖。
 *
 * <p>断言只锁「能解析 + options 每一项类型合法」，不锁具体数值：
 * 平衡参数会一直调，把它写死只会让每次调整都要改测试。
 */
class DefaultEnchantsParseTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final Path DIRECTORY = Path.of("src/main/resources/enchants");

    private final EnchantParser parser = new EnchantParser(LOGGER);

    @Test
    void 随包附魔全部可解析() throws IOException {
        final List<String> files = yamlFiles();

        assertFalse(files.isEmpty(), "默认资源里必须至少有附魔定义");

        for (final String name : files) {
            final EnchantDefinition definition = this.parser.parse(DIRECTORY.resolve(name));

            assertNotNull(definition, name + " 解析失败");

            // 空值选项会被解析器拒绝，所以这里能过就说明没有「冒号/井号被 YAML 吃掉」的问题。
            for (final var entry : definition.options().entrySet()) {
                final Object value = entry.getValue();

                assertNotNull(value, name + " 的 options." + entry.getKey() + " 是空值");
                assertTrue(
                        value instanceof String || value instanceof Number || value instanceof Boolean
                                || value instanceof List<?>,
                        name + " 的 options." + entry.getKey() + " 类型不合法: " + value.getClass()
                );
            }
        }
    }

    @Test
    void 每个启用的附魔都指向存在的脚本() throws IOException {
        for (final String name : yamlFiles()) {
            final EnchantDefinition definition = this.parser.parse(DIRECTORY.resolve(name));

            if (null == definition || !definition.enabled()) {
                continue;
            }

            final Path script = Path.of("src/main/resources/scripts").resolve(definition.script());

            assertTrue(Files.isRegularFile(script),
                    name + " 指向的脚本不存在: " + definition.script());
        }
    }

    @Test
    void 附魔id各不相同() throws IOException {
        final List<String> ids = new ArrayList<>();

        for (final String name : yamlFiles()) {
            final EnchantDefinition definition = this.parser.parse(DIRECTORY.resolve(name));

            if (null == definition) {
                continue;
            }

            assertFalse(ids.contains(definition.id()), "附魔 id 重复: " + definition.id());
            ids.add(definition.id());
        }

        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    private static List<String> yamlFiles() throws IOException {
        try (final Stream<Path> stream = Files.list(DIRECTORY)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
