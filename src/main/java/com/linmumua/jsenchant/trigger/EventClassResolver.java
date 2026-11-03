package com.linmumua.jsenchant.trigger;

import org.bukkit.event.Event;

/**
 * 把「事件名」解析成事件类，支持简名与全限定名。
 *
 * <p>简名靠<b>前缀探测</b>而不是维护一张映射表：Bukkit 的事件都落在固定的几个
 * {@code org.bukkit.event.*} 子包里，逐个 {@code Class.forName} 试一遍即可。
 * 这样新版本新增的事件不用改代码就能用，也不会因为表里写错一个字母而静默失效。
 *
 * <p>带 {@code .} 的名字按全限定名处理，因此第三方插件的事件也能直接写。
 *
 * <p>用 {@code Class.forName(name, false, loader)} 加载：不触发静态初始化。
 * 事件类一旦初始化就会去碰服务端注册表，在加载阶段没必要，也可能在错误的线程上。
 */
public final class EventClassResolver {

    /**
     * 简名探测顺序。
     *
     * <p>只覆盖 Bukkit 自带的事件包；第三方事件必须写全限定名。
     */
    private static final String[] PREFIXES = {
            "org.bukkit.event.player.",
            "org.bukkit.event.entity.",
            "org.bukkit.event.block.",
            "org.bukkit.event.inventory.",
            "org.bukkit.event.world.",
            "org.bukkit.event.vehicle.",
            "org.bukkit.event.hanging.",
            "org.bukkit.event.server.",
            "org.bukkit.event.weather.",
            "org.bukkit.event.enchantment.",
            "org.bukkit.event.raid.",
            "org.bukkit.event.",
    };

    private EventClassResolver() {
    }

    /**
     * @return 解析不到、或找到的类不是 {@link Event} 子类时返回 {@code null}
     */
    public static Class<? extends Event> resolve(final String raw) {
        if (null == raw || raw.isBlank()) {
            return null;
        }

        final String name = raw.trim();

        if (name.indexOf('.') >= 0) {
            return load(name);
        }

        for (final String prefix : PREFIXES) {
            final Class<? extends Event> found = load(prefix + name);

            if (null != found) {
                return found;
            }
        }

        return null;
    }

    private static Class<? extends Event> load(final String className) {
        try {
            final Class<?> type = Class.forName(className, false, EventClassResolver.class.getClassLoader());

            return Event.class.isAssignableFrom(type) ? type.asSubclass(Event.class) : null;
        } catch (final ClassNotFoundException | LinkageError ignored) {
            // 探测过程中「这个前缀下没有这个类」是最常见的正常情况，不打日志。
            return null;
        }
    }
}
