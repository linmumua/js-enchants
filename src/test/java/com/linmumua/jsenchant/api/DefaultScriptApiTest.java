package com.linmumua.jsenchant.api;

import com.linmumua.jsenchant.script.CompiledScript;
import com.linmumua.jsenchant.script.ScriptEnginePool;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随插件分发的默认脚本必须走受控接口——这是「跨区安全」能不能兑现的第一现场。
 *
 * <p>用例直接读 {@code src/main/resources/scripts/fireball.js} 并用真实的脚本池执行，
 * 不在测试里复制一份源码：默认脚本被改回「自己拿 victim.getWorld() 炸」时这里必须红。
 *
 * <p>判定前提是目标实体在<b>别的区域线程</b>上（{@code owns} 恒为假），因此脚本只要碰一下
 * 原生引用就会抛错；它能跑完并产生爆炸，就证明它是通过 {@code explodeAtTarget} 路由过去的。
 */
class DefaultScriptApiTest {

    /** 一次跨区触发的场景：脚本上下文、被路由的两个实体，以及玩家侧效果的记账。 */
    private record Scenario(ScriptApi api, Player player, LivingEntity victim, ApiMocks.Calls calls) {
    }

    private static Scenario crossRegion(final ApiMocks.RecordingTasks tasks, final int level) {
        final World world = ApiMocks.world(tasks);
        final ApiMocks.Calls calls = new ApiMocks.Calls();
        final Player player = ApiMocks.player(world, new Location(world, 0.0D, 64.0D, 0.0D), calls);
        final LivingEntity victim = ApiMocks.living(world, new Location(world, 8.0D, 64.0D, 8.0D));

        // 只让「目标」的实体任务真的跑：爆炸仍要在不变量下验证；
        // 投给玩家的音效任务需要读服务端注册表解析名字，无服务端环境里只锁定它被投出去、没被就地执行。
        tasks.executeFor = entity -> entity == victim;

        return new Scenario(
                ScriptApi.attack(player, victim, level, "fireball", true, true).withScheduler(tasks),
                player,
                victim,
                calls
        );
    }

    /** 读随插件分发的默认脚本。 */
    private static String bundled(final String path) throws IOException {
        try (InputStream stream = DefaultScriptApiTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "默认脚本必须随插件分发: " + path);

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CompiledScript fireball() throws IOException {
        return new CompiledScript("fireball", bundled("/scripts/fireball.js"), 1L, 0L);
    }

    @Test
    void 默认fireball脚本经受控接口在目标处爆炸() throws IOException {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final Scenario scenario = crossRegion(tasks, 2);

        // 前置事实：这条路径上拿原生引用会直接报错，而脚本仍然必须能跑完。
        assertThrows(IllegalStateException.class, scenario.api()::victim);

        final ScriptEnginePool pool = new ScriptEnginePool();

        assertTrue(pool.invoke(fireball(), "onTrigger", scenario.api()), "默认脚本必须有 onTrigger");

        assertTrue(tasks.routes().contains(scenario.victim()), "爆炸必须投递到目标自己的线程");
        assertTrue(tasks.routes().contains(scenario.player()), "音效必须投递到玩家自己的线程");
        assertEquals(0, scenario.calls().sounds(), "音效解析不能在触发线程上发生");
        assertEquals(1, tasks.powers().size(), "应当经受控接口爆炸一次");
        assertEquals(3.0D, tasks.powers().get(0).doubleValue(), 0.0001D, "1.5 × 2 级 = 3.0");

        final World world = tasks.centers().get(0).getWorld();
        assertNotNull(world, "爆炸中心必须带世界");
        assertEquals(8.0D, tasks.centers().get(0).getX(), 0.0001D, "中心点必须是目标自己的位置");
    }

    @Test
    void 默认fireball脚本的威力仍受上限约束() throws IOException {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final Scenario scenario = crossRegion(tasks, 10);

        final ScriptEnginePool pool = new ScriptEnginePool();

        assertTrue(pool.invoke(fireball(), "onTrigger", scenario.api()));

        assertEquals(ControlledExplosion.MAX_POWER, tasks.powers().get(0).doubleValue(), 0.0001D,
                "10 级请求 15.0 威力，必须被钳到上限");
    }
}
