package com.linmumua.jsenchant.trigger;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * 自定义事件的注册桥。
 *
 * <h2>为什么不能靠 {@code @EventHandler}</h2>
 *
 * <p>{@code PluginManager#registerEvents} 是反射扫描注解，且方法参数必须是<b>具体</b>事件类型。
 * 写成 {@code @EventHandler void on(Event e)} 只会收到直接派发的 {@code Event}，
 * 收不到 {@code PlayerJumpEvent} 这类子类——那就等于只能覆盖 Bukkit 自己调用
 * {@code callEvent(new Event(...))} 的极端情况。
 *
 * <p>要覆盖任意事件，只有手动注册这条路：
 * <ol>
 *   <li>反射取该事件类自己的静态 {@code getHandlerList()}；</li>
 *   <li>自建 {@link EventExecutor}，直接收 {@link Event}，转给派发器；</li>
 *   <li>{@link HandlerList#register(RegisteredListener)}。</li>
 * </ol>
 *
 * <h2>语义等价性</h2>
 *
 * <ul>
 *   <li><b>优先级</b>：{@link EventPriority#HIGHEST}，与注解式监听一致；</li>
 *   <li><b>ignoreCancelled</b>：由 Bukkit 在派发时对 {@code Cancellable} 生效，
 *       不可取消的事件不受影响——与注解式行为相同；</li>
 *   <li><b>线程</b>：在事件派发线程上被调用，与注解式没有区别，跨区判断仍由派发层负责。</li>
 * </ul>
 *
 * <h2>注销责任在我们</h2>
 *
 * <p>注解式监听器由服务端按插件清理，手动注册的不会。所以每次重载都要先撤掉不再需要的，
 * 停用时全部撤销——否则重载几次之后同一个事件会被派发多次。
 */
public final class EventBridge {

    private final Plugin plugin;
    private final TriggerDispatcher dispatcher;

    /**
     * 注册时用的 Listener 实例。
     *
     * <p>它不需要有任何方法：真正干活的是 {@link EventExecutor}。但 {@link RegisteredListener}
     * 要求一个 Listener 身份，而且精确注销时用的正是这个对象。
     */
    private final Listener marker = new Listener() {
    };

    /** 事件类 → 已注册的监听器；用它精确撤销，不能用 unregisterAll(plugin)。 */
    private final Map<Class<? extends Event>, RegisteredListener> registered = new LinkedHashMap<>();

    public EventBridge(final Plugin plugin, final TriggerDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
    }

    /**
     * 把注册集合调整成 {@code eventClasses}：撤掉多余的、补上缺的。
     *
     * <p>只做增量，不整体重建——整体重建会让正在派发的事件短暂失去监听器，
     * 热重载期间表现为「偶尔不触发」。
     */
    public synchronized void reload(final Set<Class<? extends Event>> eventClasses) {
        this.registered.entrySet().removeIf(entry -> {
            if (eventClasses.contains(entry.getKey())) {
                return false;
            }

            this.unregister(entry.getKey(), entry.getValue());

            return true;
        });

        for (final Class<? extends Event> eventClass : eventClasses) {
            if (!this.registered.containsKey(eventClass)) {
                this.register(eventClass);
            }
        }
    }

    /** 停用：全部撤销。可重复调用。 */
    public synchronized void close() {
        for (final Map.Entry<Class<? extends Event>, RegisteredListener> entry : this.registered.entrySet()) {
            this.unregister(entry.getKey(), entry.getValue());
        }

        this.registered.clear();
    }

    /** 当前已注册的事件数量（诊断/测试用）。 */
    public synchronized int size() {
        return this.registered.size();
    }

    private void register(final Class<? extends Event> eventClass) {
        try {
            final Method accessor = eventClass.getMethod("getHandlerList");
            final Object value = accessor.invoke(null);

            if (!(value instanceof HandlerList list)) {
                this.warn(eventClass, "getHandlerList() 没有返回 HandlerList");

                return;
            }

            final EventExecutor executor = (listener, event) -> this.dispatcher.fireGeneric(event);
            final RegisteredListener registration = new RegisteredListener(
                    this.marker,
                    executor,
                    EventPriority.HIGHEST,
                    this.plugin,
                    true
            );

            list.register(registration);
            this.registered.put(eventClass, registration);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            // 类没有静态 getHandlerList()、或注册被拒绝：该事件这次用不了，但别拖垮其它事件。
            this.warn(eventClass, exception.toString());
        }
    }

    private void unregister(final Class<? extends Event> eventClass, final RegisteredListener registration) {
        try {
            final Method accessor = eventClass.getMethod("getHandlerList");
            final Object value = accessor.invoke(null);

            if (value instanceof HandlerList list) {
                list.unregister(registration);
            }
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // 已经撤过、或类被卸载：没有残留需要处理。
        }
    }

    private void warn(final Class<? extends Event> eventClass, final String reason) {
        this.plugin.getLogger().log(
                Level.WARNING,
                "[JsEnchants] 无法为事件 " + eventClass.getName() + " 注册监听器: " + reason
        );
    }
}
