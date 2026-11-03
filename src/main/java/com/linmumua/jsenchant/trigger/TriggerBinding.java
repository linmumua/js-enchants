package com.linmumua.jsenchant.trigger;

import org.bukkit.event.Event;

/**
 * 一个附魔绑定的「触发时机」。
 *
 * <p>两种形态：
 * <ul>
 *   <li><b>内置短名</b>（{@link #builtin} 非空）：ATTACK / MINE 等，走各自的特化派发路径，
 *       角色解析与过滤规则都是钉死的；</li>
 *   <li><b>自定义事件</b>（{@link #builtin} 为空）：用户直接写事件类名，走通用派发。</li>
 * </ul>
 *
 * <p>两者都带 {@link #eventClass()}：内置短名映射到它底层监听的事件类，
 * 自定义事件就是它本身。事件桥据此决定要注册哪些 HandlerList。
 *
 * <p>注意「用户写 {@code BlockBreakEvent}」会被归一到内置 MINE（见
 * {@link #of(Event)} 的调用方），因为两者底层是同一个事件，分成两条路径只会互相打架。
 */
public record TriggerBinding(TriggerType builtin, Class<? extends Event> eventClass) {

    /** 内置短名。 */
    public static TriggerBinding builtin(final TriggerType type) {
        return new TriggerBinding(type, type.eventClass());
    }

    /** 用户直接写的事件类。 */
    public static TriggerBinding custom(final Class<? extends Event> eventClass) {
        return new TriggerBinding(null, eventClass);
    }

    public boolean isBuiltin() {
        return null != this.builtin;
    }

    /** 用于日志与命令输出的名字。 */
    public String name() {
        return this.isBuiltin() ? this.builtin.name() : this.eventClass.getSimpleName();
    }

    /**
     * 附魔定义里写的事件类若是某个内置短名的底层事件，就归一成那个短名。
     *
     * <p>这样 {@code trigger: BlockBreakEvent} 与 {@code trigger: MINE} 行为完全一致，
     * 不会出现「同一个事件被注解监听和事件桥各触发一次」。
     */
    public static TriggerBinding of(final Class<? extends Event> eventClass) {
        final TriggerType known = TriggerType.ofEventClass(eventClass);

        return null == known ? custom(eventClass) : builtin(known);
    }
}
