package com.linmumua.jsenchant.api;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folia 语义的行为锁定：动作投到哪条线程、跨区时哪些东西不许碰、越界时怎么报错。
 *
 * <p>这些用例全部走「假调度服务 + 假实体」，因此可以在没有服务端的情况下断言线程路由——
 * 真要靠实测的话，「消息是投给玩家还是投给目标」这种问题只能上服务器猜。
 */
class ScriptApiRoutingTest {

    /** 只需要一个可写事件的形状：本用例不依赖任何具体事件字段。 */
    private static final class FakeEvent extends Event {

        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static Location location(final World world) {
        return new Location(world, 8.0D, 64.0D, 8.0D);
    }

    @Test
    void 玩家效果在跨区时投到玩家自己的实体线程() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final ApiMocks.Calls calls = new ApiMocks.Calls();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), calls);

        // 只受理不执行：既能证明动作没有在「当前线程」上直接发生，
        // 也顺带证明音效解析没有提前在触发线程上跑（解析要碰服务端注册表，只能在玩家自己的线程上做）。
        tasks.executeFor = entity -> false;

        final ScriptApi api = ScriptApi.attack(player, null, 1, "test", true, true).withScheduler(tasks);

        api.message("hi");
        api.giveExp(5);
        api.playSound("BLOCK_NOTE_BLOCK_PLING");

        assertEquals(3, tasks.routes().size(), "三个动作都应该各投一次");
        assertTrue(tasks.routes().stream().allMatch(route -> route == player), "都要投给触发玩家自己");
        assertEquals(List.of(), calls.messages(), "跨区时不该在调用线程上直接发消息");
        assertEquals(0, calls.expGiven(), "跨区时不该在调用线程上直接加经验");
        assertEquals(0, calls.sounds(), "跨区时不该在调用线程上直接播音效");
    }

    @Test
    void 已经在正确线程时就地执行不再排队() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final ApiMocks.Calls calls = new ApiMocks.Calls();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), calls);

        tasks.entityOwnership = entity -> true;

        final ScriptApi api = ScriptApi.attack(player, null, 1, "test", true, true).withScheduler(tasks);

        // 只验不碰服务端注册表的两个效果：消息与经验是纯玩家状态。
        // 音效的名字解析要读注册表，无服务端环境里没有实现，它的「在哪条线程解析」由上一个用例锁定。
        api.message("hi");
        api.giveExp(5);

        assertEquals(List.of("hi"), calls.messages(), "同区应就地执行");
        assertEquals(5, calls.expGiven(), "同区应就地执行");
        assertEquals(0, tasks.routes().size(), "同区不该多排一个任务");
    }

    @Test
    void 命令固定投到全局区域线程() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());

        ScriptApi.attack(player, null, 1, "test", true, true).withScheduler(tasks).runCommand("say hi");

        assertEquals(1, tasks.globals(), "命令不绑实体，必须走全局区域");
        assertEquals(0, tasks.routes().size(), "命令不该绑到实体上");
    }

    @Test
    void 目标效果路由到目标实体() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final LivingEntity victim = ApiMocks.living(world, location(world));

        final ScriptApi api = ScriptApi.attack(player, victim, 2, "fireball", true, true).withScheduler(tasks);

        assertTrue(api.explodeAtTarget(3.0D));

        assertEquals(List.of(victim), tasks.routes(), "爆炸必须排到目标自己的线程，而不是触发者的");
        assertEquals(1, tasks.powers().size(), "爆炸应当执行一次");
        assertEquals(3.0D, tasks.powers().get(0).doubleValue(), 0.0001D);
        assertEquals(8.0D, tasks.centers().get(0).getX(), 0.0001D, "中心点必须是目标自己的位置");
    }

    @Test
    void 爆炸威力超过上限被钳制() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final LivingEntity victim = ApiMocks.living(world, location(world));

        ScriptApi.attack(player, victim, 1, "fireball", true, true)
                .withScheduler(tasks)
                .explodeAtTarget(50.0D);

        assertEquals(ControlledExplosion.MAX_POWER, tasks.powers().get(0).doubleValue(), 0.0001D,
                "脚本不该能把威力开到任意大");
    }

    @Test
    void 爆炸范围跨出本线程区块时拒绝执行() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final LivingEntity victim = ApiMocks.living(world, location(world));

        // 目标所在区块归本线程，但爆炸会波及的相邻区块不归本线程。
        tasks.areaOwnership = center -> false;

        final ScriptApi api = ScriptApi.attack(player, victim, 1, "fireball", true, true).withScheduler(tasks);

        assertTrue(api.explodeAtTarget(4.0D), "请求本身已受理");
        assertEquals(List.of(), tasks.powers(), "范围不归本线程就必须拒绝，不能从错误的线程炸世界");
    }

    @Test
    void 非法威力被拒绝且不排队() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final LivingEntity victim = ApiMocks.living(world, location(world));

        final ScriptApi api = ScriptApi.attack(player, victim, 1, "fireball", true, true).withScheduler(tasks);

        assertFalse(api.explodeAtTarget(Double.NaN), "NaN 必须拒绝");
        assertFalse(api.explodeAtTarget(0.0D), "0 威力没有意义");
        assertFalse(api.explodeAtTarget(-3.0D), "负威力必须拒绝");
        assertEquals(List.of(), tasks.routes(), "被拒绝的请求不该占用调度");
        assertEquals(List.of(), tasks.powers());
    }

    @Test
    void 跨区触发没有可写事件且取事件会明确报错() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());

        final ScriptApi api = ScriptApi.attack(player, null, 1, "test", true, true)
                .withScheduler(tasks)
                .crossRegion(7.5D);

        assertFalse(api.eventWritable());
        assertEquals(7.5D, api.damage(), 0.0001D, "跨区只保留冻结的事件数值");

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, api::event);

        assertTrue(thrown.getMessage().contains("没有可写事件"), "必须明确说明取不到事件，不能静默返回 null");
        assertTrue(thrown.getMessage().contains("跨区"), "必须点明原因");
    }

    @Test
    void 同区触发能拿到可改事件() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final FakeEvent event = new FakeEvent();

        final ScriptApi api = ScriptApi.attack(player, null, 1, "test", true, true)
                .withScheduler(tasks)
                .withEvent(event);

        assertTrue(api.eventWritable());
        assertSame(event, api.event(), "同区同步路径必须把活事件交给脚本");
    }

    @Test
    void 跨区时原生实体getter明确抛错() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final LivingEntity victim = ApiMocks.living(world, location(world));

        // 当前线程谁都不拥有：目标与射弹都在别的区域线程上。
        final ScriptApi api = ScriptApi.attack(player, victim, 1, "test", true, true).withScheduler(tasks);

        final IllegalStateException victimThrown = assertThrows(IllegalStateException.class, api::victim);
        assertTrue(victimThrown.getMessage().contains("跨区"), "必须说明是跨区问题");
        assertTrue(victimThrown.getMessage().contains("explodeAtTarget"),
                "必须指向可用的受控 API，而不是让脚本自己猜");

        assertThrows(IllegalStateException.class, api::player);
        assertThrows(IllegalStateException.class, api::entity);
    }

    @Test
    void 方块与射弹的归属失败同样报错() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final Projectile projectile = ApiMocks.projectile(world, location(world));

        tasks.areaOwnership = center -> false;

        final ScriptApi mined = ScriptApi.mine(player, ApiMocks.block(world, location(world)), 1, "test", true, true)
                .withScheduler(tasks);

        assertThrows(IllegalStateException.class, mined::block, "外区方块不能交出去");

        final ScriptApi shot = ScriptApi.shoot(player, projectile, 1, "test", true, true).withScheduler(tasks);

        assertThrows(IllegalStateException.class, shot::projectile, "外区射弹不能交出去");
    }

    @Test
    void 旧签名没有调度服务时不做归属检查() {
        final ApiMocks.RecordingTasks tasks = new ApiMocks.RecordingTasks();
        final World world = ApiMocks.world(tasks);
        final Player player = ApiMocks.player(world, location(world), new ApiMocks.Calls());
        final LivingEntity victim = ApiMocks.living(world, location(world));

        // 没有调度服务 = 单线程语义：这里若去问归属，就会碰到真实 Bukkit 静态入口并直接炸掉。
        final ScriptApi legacy = ScriptApi.attack(player, victim, 1, "test", true, true);

        assertSame(victim, legacy.victim());
        assertSame(player, legacy.player());
    }
}
