package com.linmumua.jsenchant.api;

import com.linmumua.jsenchant.platform.RegionTasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 无服务端测试用的最小替身：假的调度服务 + 假实体。
 *
 * <p>为什么必须自带这些：{@code ScriptApi} 只在「有调度服务」时才做归属检查，
 * 检查本身要靠 {@link RegionTasks#owns(Entity)}。用假实现既能断言路由（动作被投到哪个实体、
 * 有没有在本线程直接执行），又保证测试<b>不会</b>碰到真实 {@code Bukkit} 静态入口。
 */
final class ApiMocks {

    private ApiMocks() {
    }

    /**
     * 记录型调度服务。
     *
     * <p>默认「谁都不属于当前线程」，因为跨区才是需要被验证的那条路径；需要同区语义时
     * 覆盖 {@link #entityOwnership} 即可。
     */
    static final class RecordingTasks implements RegionTasks {

        /** 实体归属判定。 */
        Predicate<Entity> entityOwnership = entity -> false;

        /** 范围归属判定（受控爆炸用）。 */
        Predicate<Location> areaOwnership = location -> true;

        /**
         * 哪些实体任务可以就地执行。
         *
         * <p>返回 {@code false} 时只登记、不执行——用来证明「动作没有在当前线程上发生」，
         * 也用来在无服务端环境里避开必须读注册表的动作（音效解析）。
         */
        Predicate<Entity> executeFor = entity -> true;

        private final List<Entity> routes = new ArrayList<>();
        private final List<Float> powers = new ArrayList<>();
        private final List<Location> centers = new ArrayList<>();
        private int globals;
        private int laters;
        private int repeats;

        @Override
        public void global(final Runnable task) {
            // 只登记，不执行：生产里它跑在全局区域线程上，当前线程本来就看不到结果。
            this.globals++;
        }

        @Override
        public boolean entity(final Entity entity, final Runnable task, final Runnable retired) {
            this.routes.add(entity);

            if (!this.executeFor.test(entity)) {
                // 已受理但还没跑：用来断言「动作没有在调用线程上直接发生」。
                return true;
            }

            task.run();

            return true;
        }

        @Override
        public void region(final Location location, final Runnable task) {
            task.run();
        }

        @Override
        public boolean repeat(
                final Entity entity,
                final long delayTicks,
                final long periodTicks,
                final Runnable task,
                final Runnable retired
        ) {
            // 只登记不执行：周期任务在生产里由玩家调度器反复驱动，
            // 测试里跑一次会掩盖「它其实每 4 tick 才跑一次」这个事实。
            this.routes.add(entity);
            this.repeats++;

            return true;
        }

        @Override
        public boolean later(
                final Entity entity,
                final long delayTicks,
                final Runnable task,
                final Runnable retired
        ) {
            // 与 repeat 同一条理由：只登记不执行。
            // 立刻执行会掩盖「它是延迟任务」这件事，也让「延迟里读到的状态可能已经变了」测不出来。
            this.routes.add(entity);
            this.laters++;

            return true;
        }

        @Override
        public boolean owns(final Entity entity) {
            return this.entityOwnership.test(entity);
        }

        @Override
        public boolean owns(final Location location, final int squareRadiusChunks) {
            return this.areaOwnership.test(location);
        }

        @Override
        public boolean active() {
            return true;
        }

        @Override
        public void close() {
        }

        /** 一次性延迟任务的提交次数。 */
        int laters() {
            return this.laters;
        }

        /** 周期任务的提交次数。 */
        int repeats() {
            return this.repeats;
        }

        /** 动作被投递到的实体，按顺序。 */
        List<Entity> routes() {
            return this.routes;
        }

        /** 投到全局线程的任务数。 */
        int globals() {
            return this.globals;
        }

        /** 已发生的爆炸威力。 */
        List<Float> powers() {
            return this.powers;
        }

        /** 已发生的爆炸中心。 */
        List<Location> centers() {
            return this.centers;
        }
    }

    /** 记录代理上发生的调用。 */
    static final class Calls {

        private final List<String> messages = new ArrayList<>();
        private int sounds;
        private int expGiven;

        List<String> messages() {
            return this.messages;
        }

        int sounds() {
            return this.sounds;
        }

        int expGiven() {
            return this.expGiven;
        }
    }

    /**
     * 世界替身：只认 {@code createExplosion(Location, float, boolean, boolean)}，其余返回默认值。
     */
    static World world(final RecordingTasks tasks) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "createExplosion" -> {
                            tasks.centers.add((Location) args[0]);
                            tasks.powers.add((Float) args[1]);

                            // 真实签名返回 boolean：代理对原始返回值返回 null 会在调用点拆箱 NPE。
                            return Boolean.TRUE;
                        }
                        case "getName" -> {
                            return "world";
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "FakeWorld";
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                }
        );
    }

    /** 玩家替身：音效/消息/经验这些「玩家身上的效果」都会被记账。 */
    static Player player(final World world, final Location location, final Calls calls) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation" -> {
                            return location;
                        }
                        case "getWorld" -> {
                            return world;
                        }
                        case "getUniqueId" -> {
                            return FIXED_ID;
                        }
                        case "playSound" -> {
                            calls.sounds++;

                            return null;
                        }
                        case "sendMessage" -> {
                            calls.messages.add(String.valueOf(args[0]));

                            return null;
                        }
                        case "giveExp" -> {
                            calls.expGiven += (Integer) args[0];

                            return null;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "FakePlayer";
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                }
        );
    }

    /** 目标实体替身：只提供位置/世界/身份。 */
    static LivingEntity living(final World world, final Location location) {
        return (LivingEntity) Proxy.newProxyInstance(
                LivingEntity.class.getClassLoader(),
                new Class<?>[]{LivingEntity.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation" -> {
                            return location;
                        }
                        case "getWorld" -> {
                            return world;
                        }
                        case "getUniqueId" -> {
                            return VICTIM_ID;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "FakeVictim";
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                }
        );
    }

    private static final UUID FIXED_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VICTIM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID PROJECTILE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    /** 方块替身：只提供位置。 */
    static Block block(final World world, final Location location) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation" -> {
                            return location;
                        }
                        case "getWorld" -> {
                            return world;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "FakeBlock";
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                }
        );
    }

    /** 射弹替身：只提供位置与身份。 */
    static Projectile projectile(final World world, final Location location) {
        return (Projectile) Proxy.newProxyInstance(
                Projectile.class.getClassLoader(),
                new Class<?>[]{Projectile.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation" -> {
                            return location;
                        }
                        case "getWorld" -> {
                            return world;
                        }
                        case "getUniqueId" -> {
                            return PROJECTILE_ID;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "FakeProjectile";
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }

        if (boolean.class == type) {
            return false;
        }

        if (char.class == type) {
            return (char) 0;
        }

        if (long.class == type) {
            return 0L;
        }

        if (double.class == type) {
            return 0.0D;
        }

        if (float.class == type) {
            return 0.0F;
        }

        if (short.class == type) {
            return (short) 0;
        }

        if (byte.class == type) {
            return (byte) 0;
        }

        if (void.class == type) {
            return null;
        }

        return 0;
    }
}
