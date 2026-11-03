package com.linmumua.jsenchant.trigger;

import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Locale;

/**
 * 内置触发短名。每个值对应一个 Bukkit 事件，由 TriggerDispatcher 负责派发。
 */
public enum TriggerType {

    /** 近战攻击别人。 */
    ATTACK,

    /** 被别人攻击。 */
    DEFEND,

    /** 挖掘方块。 */
    MINE,

    /** 击杀生物。 */
    KILL,

    /** 右键。 */
    RIGHT_CLICK,

    /** 射箭。 */
    SHOOT;

    /**
     * 该短名底层监听的事件类。
     *
     * <p>用途有两个：一是把「用户直接写事件类名」和短名统一起来（写
     * {@code BlockBreakEvent} 等价于写 {@code MINE}），二是让事件桥知道要注册哪些事件。
     *
     * <p>ATTACK 与 DEFEND 共用 {@link EntityDamageByEntityEvent}，区别在于谁是受害者——
     * 这也是为什么「直接写事件类名」只能等价到 ATTACK：单个事件无法表达两种角色。
     */
    public Class<? extends Event> eventClass() {
        return switch (this) {
            case ATTACK, DEFEND -> EntityDamageByEntityEvent.class;
            case MINE -> BlockBreakEvent.class;
            case KILL -> EntityDeathEvent.class;
            case RIGHT_CLICK -> PlayerInteractEvent.class;
            case SHOOT -> EntityShootBowEvent.class;
        };
    }

    /**
     * 宽松解析，允许 {@code attack}、{@code ATTACK}、{@code right-click}、{@code right_click}。
     *
     * @return 无法识别时返回 {@code null}，由调用方决定是报错还是跳过
     */
    public static TriggerType parse(final String raw) {
        if (null == raw) {
            return null;
        }

        final String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');

        for (final TriggerType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }

        return null;
    }

    /**
     * 反过来：某个事件类对应哪个内置短名。
     *
     * <p>同一个事件类映射到多个短名时取声明在前的那个（目前只有伤害事件，
     * 返回 ATTACK——攻击者视角才是「用户写事件类名」时最可能的意思）。
     */
    public static TriggerType ofEventClass(final Class<? extends Event> eventClass) {
        if (null == eventClass) {
            return null;
        }

        for (final TriggerType type : values()) {
            if (type.eventClass().equals(eventClass)) {
                return type;
            }
        }

        return null;
    }
}
