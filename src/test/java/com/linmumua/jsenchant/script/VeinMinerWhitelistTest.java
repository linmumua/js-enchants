package com.linmumua.jsenchant.script;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连锁采集白名单的解析与匹配。
 *
 * <p>测的是<b>随插件分发的那份 vein_miner.js</b>，不是测试里复制的一段逻辑：
 * 白名单写法被改坏、或者"含冒号就当 CE id"这条判据被写反时，这里必须红。
 *
 * <p>两个被直接调用的函数是刻意做成纯函数的（只碰字符串，不碰 Bukkit、不碰 CraftEngine）：
 * {@code parseBlockSpec} 把一条写法解析成 spec，{@code judgeSpec} 判断 spec 是否命中方块。
 * 标签是否命中由调用方先算好再传进来，所以这里不需要服务端注册表；
 * CE 的真实判定只能在服务端验（见脚本里的 {@code customBlockId}）。
 *
 * <p>连锁代价同理：{@code extraWear} 是纯算术，{@code requireSneak} /
 * {@code durabilityMultiplier} / {@code hungerPerBlock} 只读 {@code ctx.option}，
 * 都能在这里钉住"1.2 倍是按总数算""没配时落回默认值"这类业务含义。
 */
class VeinMinerWhitelistTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final ScriptEnginePool POOL = new ScriptEnginePool(LOGGER);

    private static CompiledScript script() throws IOException {
        try (final InputStream stream = VeinMinerWhitelistTest.class.getResourceAsStream("/scripts/vein_miner.js")) {
            assertNotNull(stream, "默认脚本必须随插件分发: /scripts/vein_miner.js");

            return new CompiledScript("vein_miner", new String(stream.readAllBytes(), StandardCharsets.UTF_8), 1L, 0L);
        }
    }

    /** 每次新建作用域：用例之间不共享脚本全局变量。 */
    private static Object callInScope(final String function, final Object... args) throws IOException {
        final CompiledScript compiled = script();

        return POOL.withContext(context -> {
            final Scriptable scope = ScriptEnginePool.scopeOf(context, compiled, LOGGER);

            return call(scope, function, args);
        });
    }

    private static Object call(final Scriptable scope, final String function, final Object... args) {
        final Object handler = scope.get(function, scope);

        assertTrue(handler instanceof Function, "默认脚本必须导出 " + function);

        return ((Function) handler).call(Context.getCurrentContext(), scope, scope, args);
    }

    /** 取解析出的 spec 的某个字段。 */
    private static String field(final String raw, final String name) throws IOException {
        final Object spec = callInScope("parseBlockSpec", raw);

        assertNotNull(spec, "'" + raw + "' 应能解析出 spec");
        assertTrue(spec instanceof Scriptable, "spec 必须是对象");

        final Scriptable object = (Scriptable) spec;
        final Object value = object.get(name, object);

        assertTrue(value != Scriptable.NOT_FOUND, "spec 缺少字段 " + name);

        return Context.toString(value);
    }

    /**
     * judgeSpec：{@code ceId} 为 {@code null} 表示"不是 CE 自定义方块"，
     * {@code tagMatched} 是调用方预先算好的标签命中结果。
     */
    private static boolean judge(
            final String raw,
            final String name,
            final String ceId,
            final boolean tagMatched
    ) throws IOException {
        final CompiledScript compiled = script();

        return POOL.withContext(context -> {
            final Scriptable scope = ScriptEnginePool.scopeOf(context, compiled, LOGGER);
            final Object spec = call(scope, "parseBlockSpec", raw);

            assertNotNull(spec, "'" + raw + "' 应能解析出 spec");

            return Boolean.TRUE.equals(call(scope, "judgeSpec", spec, name, ceId, tagMatched));
        });
    }

    // --- 三种写法 ---

    @Test
    void 原版方块名解析成material() throws IOException {
        assertEquals("material", this.field("DIAMOND_ORE", "kind"));
        assertEquals("DIAMOND_ORE", this.field("DIAMOND_ORE", "name"));
    }

    @Test
    void 标签写法解析成tag() throws IOException {
        assertEquals("tag", this.field("#minecraft:ores", "kind"));
        assertEquals("ORES", this.field("#minecraft:ores", "tag"));
        assertEquals("ORES", this.field("#ores", "tag"), "不带命名空间的标签也要认");
        assertEquals("STONE_BRICKS", this.field("#minecraft:stone_bricks", "tag"));
    }

    @Test
    void 带命名空间的CE方块解析成ce() throws IOException {
        assertEquals("ce", this.field("betterend:amber_ore", "kind"));
        assertEquals("betterend:amber_ore", this.field("betterend:amber_ore", "id"));
    }

    @Test
    void 三种写法大小写不敏感() throws IOException {
        assertEquals("DIAMOND_ORE", this.field(" diamond_ore ", "name"));
        assertEquals("ORES", this.field("#Minecraft:ORES", "tag"));
        assertEquals("betterend:amber_ore", this.field("BetterEnd:Amber_Ore", "id"),
                "CE id 必须归一成小写，否则大小写不同就匹配不上");
    }

    @Test
    void minecraft命名空间按原版方块名处理() throws IOException {
        // 写 minecraft:diamond_ore 的人显然指原版钻石矿；
        // 当成 CE id 比对永远不可能命中，那才是"配了却不起作用"。
        assertEquals("material", this.field("minecraft:diamond_ore", "kind"));
        assertEquals("DIAMOND_ORE", this.field("minecraft:diamond_ore", "name"));
    }

    @Test
    void 空白与残缺写法解析成null() throws IOException {
        assertNull(callInScope("parseBlockSpec", "   "));
        assertNull(callInScope("parseBlockSpec", ""));
        assertNull(callInScope("parseBlockSpec", "#"));
        assertNull(callInScope("parseBlockSpec", "betterend:"),
                "只有命名空间没有值的 CE id 必须被丢掉，而不是匹配一切");
    }

    /**
     * 用一个只有 {@code option(key)} 的假 ctx 调脚本里的配置读取函数。
     *
     * <p>真 ctx 是 {@code ScriptApi}，读它的 option 要拖来整套 Bukkit 替身；
     * 这几个函数只碰 {@code ctx.option}，所以给最小替身就够。
     */
    private static Object callWithOption(final String function, final String key, final Object value)
            throws IOException {
        final CompiledScript compiled = script();

        return POOL.withContext(context -> {
            final Scriptable scope = ScriptEnginePool.scopeOf(context, compiled, LOGGER);
            final Object factory = context.evaluateString(scope,
                    "(function (key, value) { return { option: function (k) { return k === key ? value : null; } }; })",
                    "optionStub", 1, null);
            final Object ctx = ((Function) factory).call(context, scope, scope, new Object[]{ key, value });

            return call(scope, function, ctx);
        });
    }

    private static double number(final String function, final String key, final Object value) throws IOException {
        return ((Number) callWithOption(function, key, value)).doubleValue();
    }

    /** {@code extraWear(blocks, charged, multiplier)}：还要补扣多少点耐久。 */
    private static double wear(final int blocks, final int charged, final double multiplier) throws IOException {
        final Object result = callInScope("extraWear", blocks, charged, multiplier);

        return ((Number) result).doubleValue();
    }

    // --- 代价旋钮 ---

    @Test
    void 连锁耐久按总数乘倍数后向下取整() throws IOException {
        // 倍数是「总数」而不是「每块」：每块单独取整会得到 floor(1.2) = 1，等于没有代价。
        assertEquals(3.0D, this.wear(3, 0, 1.2D), "3 块 → floor(3 × 1.2) = 3");
        assertEquals(9.0D, this.wear(8, 0, 1.2D), "8 块 → floor(9.6) = 9，小数部分必须舍掉");
        assertEquals(28.0D, this.wear(24, 0, 1.2D), "24 块 → floor(28.8) = 28");
        assertEquals(0.0D, this.wear(0, 0, 1.2D), "一块都没挖就不该扣耐久");
    }

    @Test
    void 已经扣过的耐久会从目标值里减掉() throws IOException {
        // 这是整段逻辑的意义所在：breakNaturally 到底扣不扣取决于服务端实现，
        // 所以只补差额——它扣了 8 点就补 1 点，一点没扣就补 9 点，总量都是 floor(8 × 1.2)。
        assertEquals(1.0D, this.wear(8, 8, 1.2D), "breakNaturally 已经按每块 1 点扣过，只补差");
        assertEquals(0.0D, this.wear(8, 20, 1.2D), "已经扣超了（附魔/其它插件扣过）不能倒扣");
        assertEquals(2.0D, this.wear(3, 1, 1.0D), "倍率配成 1.0 时只补到「每块 1 点」");
    }

    @Test
    void 三个代价旋钮从yml读取() throws IOException {
        assertEquals(Boolean.FALSE, callWithOption("requireSneak", "require-sneak", Boolean.FALSE),
                "yml 写 false 时必须真的变成「不要求潜行」");
        assertEquals(Boolean.TRUE, callWithOption("requireSneak", "require-sneak", Boolean.TRUE));
        assertEquals(2.5D, this.number("durabilityMultiplier", "durability-multiplier", 2.5D), 1e-9);
        assertEquals(0.25D, this.number("hungerPerBlock", "hunger-per-block", 0.25D), 1e-9);
    }

    @Test
    void 没配这几项时落回默认值() throws IOException {
        // 存量 yml 里没有这三个键：升级后不能变成「站着也连锁、还不扣耐久」。
        assertEquals(Boolean.TRUE, callWithOption("requireSneak", "不存在的键", null));
        assertEquals(1.2D, this.number("durabilityMultiplier", "不存在的键", null), 1e-9);
        assertEquals(0.1D, this.number("hungerPerBlock", "不存在的键", null), 1e-9);
    }

    @Test
    void 耐久倍数配成0或负数时落回默认值() throws IOException {
        // 写成 0 的意图多半是「先关掉试试」，结果却是连锁完全不耗耐久；
        // 这里宁可落回默认值，也不要静默变成免费。
        assertEquals(1.2D, this.number("durabilityMultiplier", "durability-multiplier", 0.0D), 1e-9);
        assertEquals(1.2D, this.number("durabilityMultiplier", "durability-multiplier", -3.0D), 1e-9);
        assertEquals(0.0D, this.number("hungerPerBlock", "hunger-per-block", 0.0D), 1e-9,
                "饱食度写 0 是「关掉这项消耗」，要照办");
    }

    // --- 匹配 ---

    @Test
    void 原版方块名必须完全相同才命中() throws IOException {
        assertTrue(this.judge("DIAMOND_ORE", "DIAMOND_ORE", null, false));
        assertFalse(this.judge("DIAMOND_ORE", "IRON_ORE", null, false));
        assertFalse(this.judge("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", null, false),
                "原版方块名是精确匹配，不做前缀/包含");
    }

    @Test
    void CE方块不会被原版材质名放行() throws IOException {
        // CE 方块在 Bukkit 侧就是"某个原版材质 + 一段 blockdata"，
        // 按材质放行会把自定义矿和真正的原版矿混成一种（一镐挖掉别人的方块）。
        assertFalse(this.judge("DIAMOND_ORE", "DIAMOND_ORE", "betterend:amber_ore", false),
                "已经是 CE 自定义方块的，原版名不算命中");
        assertFalse(this.judge("#minecraft:ores", "DIAMOND_ORE", "betterend:amber_ore", true),
                "标签命中也不该把 CE 方块带进来");
    }

    @Test
    void CEid必须完全相同才命中() throws IOException {
        assertTrue(this.judge("betterend:amber_ore", "NOTE_BLOCK", "betterend:amber_ore", false));
        assertFalse(this.judge("betterend:amber_ore", "NOTE_BLOCK", "betterend:ruby_ore", false));
        assertFalse(this.judge("betterend:amber_ore", "NOTE_BLOCK", null, false),
                "不是自定义方块（或没装 CE）时 CE 白名单项一律不命中");
    }

    @Test
    void 标签命中由调用方预先算好() throws IOException {
        assertTrue(this.judge("#minecraft:ores", "DIAMOND_ORE", null, true));
        assertFalse(this.judge("#minecraft:ores", "DIAMOND_ORE", null, false));
    }
}
