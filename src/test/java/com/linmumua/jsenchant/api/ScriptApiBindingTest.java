package com.linmumua.jsenchant.api;

import com.linmumua.jsenchant.script.CompiledScript;
import com.linmumua.jsenchant.script.ScriptEnginePool;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这是整个方案的关键假设：沙箱禁止脚本访问 java.* 的同时，
 * 仍必须能调用我们注入的 ScriptApi。
 *
 * <p>如果这条不成立，「JS 写附魔逻辑」就没有意义。所以这里用真实 Rhino
 * 编译并执行脚本，而不是假定它能工作。
 */
class ScriptApiBindingTest {

    /**
     * 只检查绑定行为，不需要真实 Player，因此用假实现记录调用。
     *
     * <p>必须是 {@code public}：Rhino 只暴露 public 类的成员，private 嵌套类的方法
     * 在脚本里会变成「找不到函数」。
     */
    public static final class RecordingApi {

        private final int level;
        private final double chance;
        private final boolean cooldownReady;
        private final List<String> sounds = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private int expGiven;

        private RecordingApi(final int level, final double chance, final boolean cooldownReady) {
            this.level = level;
            this.chance = chance;
            this.cooldownReady = cooldownReady;
        }

        public int level() {
            return this.level;
        }

        public boolean rollChance() {
            return this.chance >= 1.0D;
        }

        public boolean cooldownReady() {
            return this.cooldownReady;
        }

        public void playSound(final String sound) {
            this.sounds.add(sound);
        }

        public void message(final String text) {
            this.messages.add(text);
        }

        public void giveExp(final int amount) {
            this.expGiven += amount;
        }

        public String echo(final String text) {
            return text;
        }

        public int doubleIt(final int value) {
            return value * 2;
        }
    }

    /** 编译脚本并返回其作用域，供后续调用。 */
    private Scriptable compile(final ScriptEnginePool pool, final String source) {
        return pool.withContext(context -> {
            final Scriptable scope = context.initStandardObjects();

            context.compileString(source, "test", 1, null).exec(context, scope);

            return scope;
        });
    }

    @Test
    void 脚本能调用注入对象的公开方法() {
        final ScriptEnginePool pool = new ScriptEnginePool();

        final Scriptable scope = this.compile(pool, """
                function onTrigger(ctx) {
                  if (!ctx.rollChance()) return 'no-chance';
                  ctx.playSound('ENTITY_GENERIC_EXPLODE');
                  ctx.message('火球触发 Lv' + ctx.level());
                  ctx.giveExp(5);
                  return 'done';
                }
                """);

        final RecordingApi api = new RecordingApi(3, 1.0D, true);

        final Object result = pool.withContext(context -> {
            final Object handler = scope.get("onTrigger", scope);

            assertTrue(handler instanceof Function, "onTrigger 应能被取出为函数");

            return ((Function) handler).call(context, scope, scope, new Object[]{ api });
        });

        assertEquals("done", Context.toString(result));
        assertEquals(List.of("ENTITY_GENERIC_EXPLODE"), api.sounds);
        assertEquals(List.of("火球触发 Lv3"), api.messages);
        assertEquals(5, api.expGiven);
    }

    @Test
    void 脚本返回值可影响流程() {
        final ScriptEnginePool pool = new ScriptEnginePool();

        final Scriptable scope = this.compile(pool, """
                function onTrigger(ctx) {
                  return ctx.cooldownReady() ? 'ready' : 'waiting';
                }
                """);

        final Object ready = pool.withContext(context -> {
            final Object handler = scope.get("onTrigger", scope);

            return ((Function) handler).call(context, scope, scope,
                    new Object[]{ new RecordingApi(1, 1.0D, true) });
        });

        final Object waiting = pool.withContext(context -> {
            final Object handler = scope.get("onTrigger", scope);

            return ((Function) handler).call(context, scope, scope,
                    new Object[]{ new RecordingApi(1, 1.0D, false) });
        });

        assertEquals("ready", Context.toString(ready));
        assertEquals("waiting", Context.toString(waiting));
    }

    @Test
    void 沙箱仍然阻断java访问() {
        final ScriptEnginePool pool = new ScriptEnginePool();

        final Scriptable scope = this.compile(pool, """
                function onTrigger(ctx) {
                  try {
                    return java.lang.System.getProperty('user.name');
                  } catch (e) {
                    return 'blocked';
                  }
                }
                """);

        final Object result = pool.withContext(context -> {
            final Object handler = scope.get("onTrigger", scope);

            return ((Function) handler).call(context, scope, scope,
                    new Object[]{ new RecordingApi(1, 1.0D, true) });
        });

        assertEquals("blocked", Context.toString(result), "注入 API 可用不等于沙箱失效");
    }

    @Test
    void 脚本调用不存在的方法会抛错而不是静默成功() {
        final ScriptEnginePool pool = new ScriptEnginePool();

        final Scriptable scope = this.compile(pool, """
                function onTrigger(ctx) {
                  return ctx.noSuchMethod();
                }
                """);

        // 关键约定：脚本写错方法名必须报错，不能被当成「触发成功」而写入冷却。
        assertThrows(RuntimeException.class, () -> pool.withContext(context -> {
            final Object handler = scope.get("onTrigger", scope);

            return ((Function) handler).call(context, scope, scope,
                    new Object[]{ new RecordingApi(1, 1.0D, true) });
        }));
    }

    @Test
    void 基础类型参数传递不被沙箱误伤() {
        final ScriptEnginePool pool = new ScriptEnginePool();

        final Scriptable scope = this.compile(pool, """
                function onTrigger(ctx) {
                  return ctx.echo('值') + '/' + ctx.doubleIt(21);
                }
                """);

        // 沙箱若把 java.lang.String / Integer 一并拦掉，这里会直接失败。
        // 这是实测踩过的坑，必须锁死。
        final Object result = pool.withContext(context -> {
            final Object handler = scope.get("onTrigger", scope);

            return ((Function) handler).call(context, scope, scope,
                    new Object[]{ new RecordingApi(1, 1.0D, true) });
        });

        assertEquals("值/42", Context.toString(result));
    }
}
