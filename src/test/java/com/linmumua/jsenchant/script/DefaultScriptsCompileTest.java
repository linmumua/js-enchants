package com.linmumua.jsenchant.script;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随包分发的每一个脚本都必须能被真实 Rhino 编译通过，并导出至少一个入口。
 *
 * <p>为什么要整目录跑：脚本是热加载的，写坏一个语法错误的后果是「运行期那一个附魔静默失效」——
 * 服务端只在重载时打一行日志，玩家那边表现为「附魔没反应」。这类错误必须在校验阶段就红。
 *
 * <p>只编译与执行顶层（顶层只有变量与函数声明），不调用 onTrigger：
 * 调用它需要完整的 Bukkit 环境，那是集成测试的活，不是这里的。
 */
class DefaultScriptsCompileTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final Path DIRECTORY = Path.of("src/main/resources/scripts");

    private static final ScriptEnginePool POOL = new ScriptEnginePool(LOGGER);

    @Test
    void 随包脚本全部可编译() throws IOException {
        final List<Path> scripts = scriptFiles();

        assertFalse(scripts.isEmpty(), "默认资源里必须至少有脚本");

        for (final Path path : scripts) {
            final String name = path.getFileName().toString();
            final String source = Files.readString(path, StandardCharsets.UTF_8);
            final CompiledScript compiled = new CompiledScript(name.replace(".js", ""), source, 1L, 0L);

            POOL.withContext(context -> {
                final Scriptable scope = ScriptEnginePool.scopeOf(context, compiled, LOGGER);

                // example.js 是给人看的写法示例，没有挂到任何附魔上，不要求有入口。
                if ("example.js".equals(name)) {
                    return Boolean.TRUE;
                }

                final Object trigger = scope.get("onTrigger", scope);
                final Object hover = scope.get("onHover", scope);

                assertTrue(
                        trigger instanceof Function || hover instanceof Function,
                        name + " 既没有 onTrigger 也没有 onHover，加载了也不会有任何效果"
                );

                return Boolean.TRUE;
            });
        }
    }

    private static List<Path> scriptFiles() throws IOException {
        try (final Stream<Path> stream = Files.list(DIRECTORY)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".js"))
                    .sorted()
                    .toList();
        }
    }
}
