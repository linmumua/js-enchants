package com.linmumua.jsenchant.api;

import com.linmumua.jsenchant.platform.RegionTasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 脚本可用的<b>受控</b>爆炸。
 *
 * <p>为什么不让脚本直接 {@code victim.getWorld().createExplosion(...)}：
 * <ol>
 *   <li>位置必须在目标自己的区域线程上读。脚本手里若只有一个跨区引用，
 *       读位置那一刻就已经越界了；</li>
 *   <li>爆炸影响的范围可能跨出本线程拥有的区块，Folia 上那等于从错误的线程改世界；</li>
 *   <li>威力不收口，一条脚本配置失误就能炸掉半张地图。</li>
 * </ol>
 *
 * <p>因此执行顺序被写死为：<b>先路由到目标实体的区域线程 → 在那里读位置 →
 * 检查影响范围是否全归本线程 → 再做威力钳制 → 最后才真的爆炸</b>。
 * 检查不通过就拒绝，并按附魔限流告警——拒绝比「炸一半」安全，告警比静默安全。
 *
 * <p><b>不会发生的事</b>：这里不会、也无法异步修改触发它的原事件；
 * 爆炸是一个独立的世界效果，与原事件的取消/伤害结算无关。
 */
public final class ControlledExplosion {

    /** 威力上限：与「闪电苦力怕」同量级，再大就不属于附魔效果了。 */
    public static final double MAX_POWER = 6.0D;

    /** 一个区块的边长（方块）。 */
    private static final double CHUNK_SIZE = 16.0D;

    /** 同类告警的最小间隔。 */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;

    /**
     * 告警落地用的日志器。
     *
     * <p>本类拿不到插件实例（它由脚本 API 直接触发），而 {@code Bukkit.getLogger()} 在无服务端的
     * 单测里可能为 {@code null}，所以用一个与插件同名的 JDK 日志器——名字与
     * {@code owner.getLogger()} 一致，运维看起来是同一处来源。
     */
    private static final Logger LOGGER = Logger.getLogger("JsEnchants");

    /** 告警限流：告警键 → 上次告警时间戳。 */
    private static final Map<String, AtomicLong> LAST_WARN = new ConcurrentHashMap<>();

    private ControlledExplosion() {
    }

    /**
     * 请求在目标处爆炸。
     *
     * @param tasks  区域调度服务；{@code null} 表示单线程语义（旧路径/单测），就地执行
     * @param target 目标实体；{@code null} 直接放弃
     * @param power  期望威力
     * @param source 请求来源（附魔 id），用于告警定位与限流
     * @return 是否已受理：已同步执行，或已交给目标实体的调度器
     */
    public static boolean request(
            final RegionTasks tasks,
            final LivingEntity target,
            final double power,
            final String source
    ) {
        if (null == target) {
            return false;
        }

        if (!Double.isFinite(power) || 0.0D >= power) {
            warn(source, "invalid-power",
                    "附魔 " + source + " 请求的爆炸威力非法（" + power + "），已拒绝。");

            return false;
        }

        final double clamped = Math.min(MAX_POWER, power);

        if (clamped < power) {
            warn(source, "clamped-power",
                    "附魔 " + source + " 请求的爆炸威力 " + power + " 超过上限，已钳到 " + clamped + "。");
        }

        final Runnable task = () -> detonate(tasks, target, clamped, source);

        if (null == tasks || tasks.owns(target)) {
            task.run();

            return true;
        }

        return tasks.entity(target, task, null);
    }

    /**
     * 真正炸：只在目标实体的区域线程上执行。
     *
     * <p>位置在这一步才读，所以调用它之前不碰目标的任何状态。
     */
    private static void detonate(
            final RegionTasks tasks,
            final LivingEntity target,
            final double power,
            final String source
    ) {
        final Location center = target.getLocation();

        if (null == center) {
            return;
        }

        // 方形半径：威力 p 的球形影响半径约 p 个方块，换算成区块后向上取整，再 +1 覆盖边界那一圈
        // （(2r+1)² 的方形若只取 ceil(p/16)，正好落在区块边界的方块会被漏算）。
        // 宁可多要一圈、多拒绝几次，也不要漏判——漏判就是从一个不拥有该区块的线程改世界。
        final int radiusChunks = (int) Math.ceil(power / CHUNK_SIZE) + 1;

        if (null != tasks && !tasks.owns(center, radiusChunks)) {
            warn(source, "out-of-region",
                    "附魔 " + source + " 的爆炸影响范围超出当前区域线程的区块所有权，已拒绝执行。");

            return;
        }

        final World world = center.getWorld();

        if (null == world) {
            return;
        }

        world.createExplosion(center, (float) power, false, false);
    }

    /** 限流告警：同一附魔的同类问题每分钟最多一条。 */
    private static void warn(final String source, final String reason, final String message) {
        final AtomicLong last = LAST_WARN.computeIfAbsent(source + ':' + reason, ignored -> new AtomicLong(0L));
        final long now = System.currentTimeMillis();
        final long previous = last.get();

        if (now - previous < WARN_INTERVAL_MILLIS || !last.compareAndSet(previous, now)) {
            return;
        }

        LOGGER.warning(message);
    }
}
