package com.linmumua.jsenchant.api;

import com.linmumua.jsenchant.script.CompiledScript;
import com.linmumua.jsenchant.script.ScriptEnginePool;
import com.linmumua.jsenchant.script.ScriptRuntimeHost;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交给脚本的新能力里「纯读」那部分的语义。
 *
 * <p>挑这四条来钉，是因为它们各自对应一次线上会出错的具体情形：
 * <ul>
 *   <li>配置取值：把 {@code 1.2} 读成 {@code null} 或 {@code 0} 会让耐久倍率静默失效；</li>
 *   <li>按等级取值：越界时若抛错，一次 III 级的触发就整次失败；</li>
 *   <li>跨区同步读：把别人的实体状态读成本线程的值，等于凭空造出一个不属于自己的世界视图；</li>
 *   <li>状态键规整：中文或大写键名直接扔进 {@link NamespacedKey} 会抛错，脚本因此整次触发失败。</li>
 * </ul>
 *
 * <p>延迟回调本身要在真实调度器上跑，这里只钉「有限次数走的是链式延迟而不是周期任务」——
 * 那条决定了几次之后它真的会停。
 */
class ScriptApiEffectsTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static ScriptApi apiWith(final Map<String, Object> options, final int level) {
        return ScriptApi.generic(null, null, null, null, level, "vein_miner").withOptions(options);
    }

    // --- 配置取值 ---

    @Test
    void 数字选项支持整数浮点与字符串写法() {
        final ScriptApi api = apiWith(Map.<String, Object>of(
                "int", 3,
                "double", 1.2,
                "text", "2.5"
        ), 1);

        assertEquals(3.0D, api.optionNumber("int", -1.0D), 1e-9,
                "yml 里的整数是 Integer，必须能读成数字");
        assertEquals(1.2D, api.optionNumber("double", -1.0D), 1e-9);
        assertEquals(2.5D, api.optionNumber("text", -1.0D), 1e-9,
                "写成字符串的数字也该读得出来，否则服主加个引号就静默失效");
        assertEquals(-1.0D, api.optionNumber("不存在的键", -1.0D), 1e-9,
                "没配这一项时必须用调用方给的默认值");
    }

    @Test
    void 非法数字回落而不是抛错() {
        final ScriptApi api = apiWith(Map.<String, Object>of("bad", "很快"), 1);

        assertEquals(1.2D, api.optionNumber("bad", 1.2D), 1e-9,
                "读不出数字时必须回落，不能让一次触发因为配置写错而整次失败");
    }

    @Test
    void 布尔选项只认true与false() {
        final ScriptApi api = apiWith(Map.<String, Object>of(
                "on", true,
                "off", "false",
                "noise", "yes"
        ), 1);

        assertTrue(api.optionFlag("on", false));
        assertFalse(api.optionFlag("off", true), "字符串 false 也要读成 false");
        assertTrue(api.optionFlag("noise", true), "认不出时用默认值");
        assertFalse(api.optionFlag("missing", false));
    }

    @Test
    void 按等级取值的越界行为是取最后一项而不是抛错() {
        final ScriptApi api = apiWith(Map.<String, Object>of("damage", List.of("5", "10", "20")), 5);

        assertEquals(20.0D, api.byLevel("damage", -1.0D), 1e-9,
                "等级超出列表长度时取最后一项：max-level 调大以后旧配置不该炸");
        assertEquals(-1.0D, api.byLevel("missing", -1.0D), 1e-9);

        final ScriptApi second = apiWith(Map.<String, Object>of("damage", List.of("5", "10", "20")), 2);

        assertEquals(10.0D, second.byLevel("damage", -1.0D), 1e-9, "II 级取第二项");
    }

    // --- 跨区同步读 ---

    @Test
    void 不拥有触发者时同步动作一律不执行() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();

        // 当前线程不拥有这个玩家：读背包、改背包、读 PDC 都属于越界读。
        tasks.entityOwnership = entity -> false;

        final ScriptApi api = ScriptApi.attack(player(), null, 1, "vein_miner", true, true)
                .withScheduler(tasks);

        assertFalse(api.give("DIAMOND", 1), "越界时不能悄悄改别人的背包");
        assertFalse(api.take("DIAMOND", 1));
        assertFalse(api.setState("counter", "1"));
        assertNull(api.state("counter"));
        assertEquals(0, api.addState("counter", 1));
    }

    // --- 状态键 ---

    @Test
    void 状态键带上附魔id并规整成合法键名() {
        final ScriptRuntimeHost host = host();

        final NamespacedKey key = host.stateKey("counter");

        assertNotNull(key);
        assertEquals("vein_miner.counter", key.getKey(), "键必须带附魔 id，否则两个附魔会互相覆盖");

        final NamespacedKey chinese = host.stateKey("计数");

        assertNotNull(chinese, "非法字符要被替换掉，而不是让 NamespacedKey 抛错");
        assertFalse(chinese.getKey().contains("计"));

        assertNull(host.stateKey(null));
        assertNull(host.stateKey("   "));
    }

    // --- 延迟回调的调度形态 ---

    @Test
    void 有限次数的重复走链式延迟而不是周期任务() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final ScriptRuntimeHost host = new ScriptRuntimeHost(
                plugin(), "vein_miner", script(), new ScriptEnginePool(LOGGER), tasks, LOGGER);

        assertTrue(host.repeat(anchor(), 20L, 20L, 3, "tick"));

        assertEquals(1, tasks.laters(), "第一次是延迟任务");
        assertEquals(0, tasks.repeats(),
                "有限次数不能用周期任务：RegionTasks.repeat 没有「跑 N 次就停」的开关，次数用完后会一直空转");
    }

    @Test
    void 无限次数的重复才用周期任务() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final ScriptRuntimeHost host = new ScriptRuntimeHost(
                plugin(), "vein_miner", script(), new ScriptEnginePool(LOGGER), tasks, LOGGER);

        assertTrue(host.repeat(anchor(), 20L, 20L, 0, "tick"));
        assertEquals(1, tasks.repeats());
        assertEquals(0, tasks.laters());
    }

    // --- 替身 ---

    private static ScriptRuntimeHost host() {
        return new ScriptRuntimeHost(
                plugin(), "vein_miner", script(), new ScriptEnginePool(LOGGER),
                new ApiMocks.RecordingTasks(), LOGGER);
    }

    private static CompiledScript script() {
        return new CompiledScript("vein_miner", "function tick() {}", 1L, 0L);
    }

    /** 状态键只读插件名，其它方法一律当作误用。 */
    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{ Plugin.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "JsEnchants";
                    // 26.x 的 NamespacedKey(Plugin, String) 取的是 namespace() 而不是 getName()。
                    case "namespace" -> "jsenchants";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlugin";
                    default -> throw new UnsupportedOperationException(
                            "状态键只该读插件名，被调用的方法: " + method.getName());
                }
        );
    }

    /** 归属判定只看调度器给的谓词，玩家对象本身不被读取。 */
    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{ Player.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlayer";
                    default -> throw new UnsupportedOperationException(
                            "越界判定不该读玩家状态，被调用的方法: " + method.getName());
                }
        );
    }

    private static Entity anchor() {
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{ Entity.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeAnchor";
                    default -> throw new UnsupportedOperationException(
                            "调度只登记锚点，不该读实体状态，被调用的方法: " + method.getName());
                }
        );
    }
}
