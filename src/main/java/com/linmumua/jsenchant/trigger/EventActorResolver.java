package com.linmumua.jsenchant.trigger;

import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.reflect.Method;

/**
 * 从任意 Bukkit 事件里找出「谁是玩家、目标、方块、投射物」。
 *
 * <p>只做<b>引用提取</b>，不读任何实体状态（位置、血量、背包）。这一点是刻意的：
 * Folia 下读取他区实体的状态会直接抛错，而引用本身是安全的。真正的归属检查
 * 留给派发层（{@code RegionTasks#owns}），那里才有上下文决定是排队还是放弃。
 *
 * <p>解析顺序是「具体到抽象」：先认几个语义明确的事件族，再退回反射
 * {@code getPlayer()} / {@code getEntity()}。都取不到就返回空角色，由调用方跳过本次派发
 * ——不猜、不降级到「随便找个玩家」。
 */
public final class EventActorResolver {

    private EventActorResolver() {
    }

    /**
     * 一次事件里能确定出来的角色，缺的为 {@code null}。
     */
    public record Actors(Player player, LivingEntity target, Block block, Projectile projectile) {

        public static Actors empty() {
            return new Actors(null, null, null, null);
        }

        public boolean hasPlayer() {
            return null != this.player;
        }
    }

    public static Actors resolve(final Event event) {
        if (null == event) {
            return Actors.empty();
        }

        // 1) 伤害事件：damager 是攻击者，entity 是承受者。
        if (event instanceof EntityDamageByEntityEvent damage) {
            final Player attacker = asPlayer(damage.getDamager());

            return new Actors(
                    attacker,
                    damage.getEntity() instanceof LivingEntity living ? living : null,
                    null,
                    damage.getDamager() instanceof Projectile projectile ? projectile : null
            );
        }

        // 2) 死亡事件：killer 是击杀者。
        if (event instanceof EntityDeathEvent death) {
            return new Actors(
                    death.getEntity().getKiller(),
                    death.getEntity(),
                    null,
                    null
            );
        }

        // 3) 射箭：射手 + 出膛的投射物。
        if (event instanceof EntityShootBowEvent shoot) {
            return new Actors(
                    shoot.getEntity() instanceof Player player ? player : null,
                    null,
                    null,
                    shoot.getProjectile() instanceof Projectile projectile ? projectile : null
            );
        }

        // 4) 投射物发射：shooter 可能是玩家，也可能是别的实体。
        if (event instanceof ProjectileLaunchEvent launch) {
            final ProjectileSource shooter = launch.getEntity().getShooter();

            return new Actors(
                    shooter instanceof Player player ? player : null,
                    null,
                    null,
                    launch.getEntity()
            );
        }

        // 5) 右键：玩家 + 被点的方块（可能为空，例如右键空气）。
        if (event instanceof PlayerInteractEvent interact) {
            return new Actors(interact.getPlayer(), null, interact.getClickedBlock(), null);
        }

        // 6) 方块事件本身不带玩家（BlockEvent 只有 Block）。
        if (event instanceof BlockEvent blockEvent) {
            return new Actors(playerOf(event), null, blockEvent.getBlock(), null);
        }

        // 7) 背包事件能反查持有者（打开箱子的玩家、被点击背包的玩家）。
        if (event instanceof InventoryEvent inventory) {
            final Player player = playerOf(event);

            return null != player
                    ? new Actors(player, null, null, null)
                    : new Actors(
                            inventory.getView().getPlayer() instanceof Player holder ? holder : null,
                            null,
                            null,
                            null
                    );
        }

        return new Actors(playerOf(event), entityOf(event), null, null);
    }

    /** 反射取 {@code getPlayer()}；返回类型不是 Player 时按「没有」处理。 */
    private static Player playerOf(final Event event) {
        final Object value = invoke(event, "getPlayer");

        return value instanceof Player player ? player : null;
    }

    /** 反射取 {@code getEntity()}。 */
    private static LivingEntity entityOf(final Event event) {
        final Object value = invoke(event, "getEntity");

        return value instanceof LivingEntity living ? living : null;
    }

    private static Object invoke(final Event event, final String methodName) {
        try {
            final Method method = event.getClass().getMethod(methodName);

            return method.invoke(event);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // 大多数事件没有这个方法，这是正常情况，不是错误。
            return null;
        }
    }

    private static Player asPlayer(final Object damager) {
        if (damager instanceof Player player) {
            return player;
        }

        // 抛射物的射手也可能是玩家（箭、投掷物）。
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof Player player ? player : null;
        }

        return null;
    }
}
