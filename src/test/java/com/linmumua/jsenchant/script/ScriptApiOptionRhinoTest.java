package com.linmumua.jsenchant.script;

import com.linmumua.jsenchant.api.ScriptApi;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 附魔自定义选项在脚本里真正看到的形状。
 *
 * <p>Java 侧断言"返回列表"是不够的：Rhino 怎么装箱决定脚本里能不能写 {@code list.length}。
 * 第一版这里返回 {@code String[]}，脚本一读直接抛"禁止访问 Java 类 [Ljava.lang.String;"——
 * 数组要建成员表，而数组类被 ClassShutter 拒绝。换成 {@code List} 之后走 NativeJavaList，
 * 下标与 length 都可用。这条用例就是那份证据：白名单读不出来时，
 * 连锁采集会静默变成"只有白名单为空"的老行为，服务端不会有任何报错。
 */
class ScriptApiOptionRhinoTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final ScriptEnginePool POOL = new ScriptEnginePool(LOGGER);

    private static final String PROBE = """
            function probe(ctx) {
              var list = ctx.optionList("blocks");
              var either = ctx.option("blocks");

              return "" + list.length                       // 数组语义的长度必须可读
                  + "/" + list[1]                           // 必须能下标访问
                  + "/" + list.size()                       // List 自身的 API 也要在
                  + "/" + either.length                     // option(...) 拿到列表时同样是数组语义
                  + "/" + ctx.optionList("single").length    // 单值被包成一项
                  + "/" + ctx.option("radius")               // 数字原样是数字
                  + "/" + ctx.optionList("missing").length    // 缺省是空列表
                  + "/" + ctx.option("missing");             // 缺省是 null
            }
            """;

    @Test
    void 脚本侧读到的列表可以按数组使用() {
        final ScriptApi api = ScriptApi.generic(null, null, null, null, 1, "vein_miner")
                .withOptions(Map.<String, Object>of(
                        "blocks", List.of("#minecraft:ores", "DIAMOND_ORE"),
                        "single", "DIAMOND_ORE",
                        "radius", 4
                ));

        final CompiledScript probe = new CompiledScript("option_probe", PROBE, 1L, 0L);

        final String result = POOL.withContext(context -> {
            final Scriptable scope = ScriptEnginePool.scopeOf(context, probe, LOGGER);
            final Function function = (Function) scope.get("probe", scope);

            return Context.toString(function.call(context, scope, scope, new Object[]{api}));
        });

        assertEquals("2/DIAMOND_ORE/2/2/1/4/0/null", result);
    }
}
