package com.linmumua.jsenchant.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 附魔自定义选项的读取语义。
 *
 * <p>为什么列表是 {@code java.util.List} 而不是 {@code String[]}：Rhino 会给 Java 数组建成员表，
 * 数组类被沙箱的 ClassShutter 拒绝，脚本一读就报"禁止访问 Java 类"；
 * 而 {@code List} 走 {@code NativeJavaList}，脚本里 {@code list.length} / {@code list[i]} 照常可用。
 * 这条约束的运行时证据在 {@code ScriptApiOptionRhinoTest}，这里锁的是 Java 侧的返回形状。
 */
class ScriptApiOptionsTest {

    /** 不需要玩家/实体，只要一个带选项的上下文。 */
    private static ScriptApi apiWith(final Map<String, Object> options) {
        return ScriptApi.generic(null, null, null, null, 1, "vein_miner").withOptions(options);
    }

    @Test
    void 标量选项原样交出() {
        final ScriptApi api = apiWith(Map.<String, Object>of(
                "label", "古矿",
                "radius", 4,
                "highlight", true
        ));

        assertEquals("古矿", api.option("label"));
        assertEquals(4, api.option("radius"));
        assertEquals(Boolean.TRUE, api.option("highlight"));
    }

    @Test
    void 列表选项交出字符串列表() {
        final ScriptApi api = apiWith(Map.<String, Object>of("blocks", List.of("#minecraft:ores", "DIAMOND_ORE")));

        final Object value = api.option("blocks");

        assertInstanceOf(List.class, value, "列表必须是 List（Rhino 会包成可下标访问的 NativeJavaList）");
        assertEquals(List.of("#minecraft:ores", "DIAMOND_ORE"), value);
    }

    @Test
    void 不存在的选项返回null与空列表() {
        final ScriptApi api = apiWith(Map.of());

        assertNull(api.option("blocks"));
        assertNull(api.option("没这个键"));
        assertNull(api.option(null), "键为 null 时也返回 null，不抛错");

        final List<String> empty = api.optionList("blocks");

        assertNotNull(empty, "optionList 永远不返回 null");
        assertEquals(0, empty.size());
    }

    @Test
    void optionList把单值包成一项() {
        // 「白名单只写了一个」是常见配置，脚本不该为此多写一个分支。
        final ScriptApi api = apiWith(Map.<String, Object>of("blocks", "DIAMOND_ORE"));

        assertEquals(List.of("DIAMOND_ORE"), api.optionList("blocks"));
    }

    @Test
    void optionList返回的列表不可变() {
        // 脚本就地 sort/splice 不能改到定义里的选项：同一份定义会被反复派发。
        final ScriptApi api = apiWith(Map.<String, Object>of("blocks", List.of("a", "b")));
        final List<String> blocks = api.optionList("blocks");

        assertThrows(UnsupportedOperationException.class, () -> blocks.add("c"));
        assertEquals(List.of("a", "b"), api.optionList("blocks"));
    }

    @Test
    void 选项缺失时上下文仍可正常工作() {
        // 存量定义不带 options：withOptions(null) 也必须等价于空映射，
        // 而不是让 option(...) 抛 NPE 把整次触发打断。
        final ScriptApi api = ScriptApi.generic(null, null, null, null, 2, "legacy").withOptions(null);

        assertNull(api.option("blocks"));
        assertEquals(2, api.level(), "挂选项不能顺手改别的字段");
        assertEquals("legacy", api.enchantId());
    }

    @Test
    void 挂选项不影响既有上下文字段() {
        final ScriptApi base = ScriptApi.generic(null, null, null, null, 3, "vein_miner");
        final ScriptApi withOptions = base.withOptions(Map.<String, Object>of("blocks", List.of("DIAMOND_ORE")));

        assertEquals(base.level(), withOptions.level());
        assertEquals(base.enchantId(), withOptions.enchantId());
        assertEquals(base.slot(), withOptions.slot());
        assertEquals(base.eventWritable(), withOptions.eventWritable());
    }
}
