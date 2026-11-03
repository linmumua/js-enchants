package com.linmumua.jsenchant.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;

/**
 * 脚本需要「本插件运行环境」才能完成的那部分能力。
 *
 * <p>分成接口而不是让 {@link ScriptApi} 直接持有插件实例，是为了守住那条边界：
 * 脚本能做的事必须在接口里被逐个列出来，而不是拿到 {@code Plugin} 之后自己去注册监听器、
 * 读配置文件或改别人的状态。
 *
 * <p>两项能力各自解决一个当前做不到的事：
 * <ul>
 *   <li>{@link #stateKey(String)}：脚本需要「记住上一次」的东西（层数、计数、今天是否触发过）。
 *       键必须落在本插件自己的命名空间下，且带上附魔 id，否则两个附魔会互相踩。</li>
 *   <li>{@link #later}/{@link #repeat}：脚本需要「过一会儿再做」。回调按<b>函数名</b>而不是
 *       JS 函数对象传递，原因见方法注释。</li>
 * </ul>
 */
public interface ScriptHost {

    /**
     * 本插件命名空间下、本附魔专用的持久化键。
     *
     * @param suffix 脚本自己的名字；为空时返回 {@code null}
     */
    NamespacedKey stateKey(String suffix);

    /**
     * 在锚点实体自己的区域线程上，延迟执行一次脚本里的全局函数。
     *
     * <p>为什么传函数名而不是 JS 函数对象：Rhino 的 {@code Function}/{@code Scriptable}
     * 属于创建它的那个线程，而延迟任务可能在另一个区域线程上执行（玩家走过了区域边界）。
     * 把函数对象带过去就是跨线程使用可变对象，那正是脚本池一直在避免的事。
     * 传名字则由脚本池在<b>执行线程自己的作用域</b>里重新查一次同名函数，天然安全。
     *
     * <p>代价是回调必须写成脚本顶层的具名函数（{@code function onBurnTick() {}}），
     * 不能是匿名闭包——闭包里捕获的变量本来就无法跨线程存活。
     *
     * @param anchor     任务归属实体（通常是触发者）
     * @param delayTicks 延迟（tick），小于 1 时按 1 处理
     * @param function   脚本里的全局函数名
     * @param args       传给该函数的参数；只应是数字/字符串/布尔这类不可变值
     * @return 是否已受理
     */
    boolean later(Entity anchor, long delayTicks, String function, Object... args);

    /**
     * 在锚点实体自己的区域线程上按周期执行脚本里的全局函数。
     *
     * @param times 执行次数；{@code <= 0} 表示一直重复到实体退休或插件停用
     * @see #later(Entity, long, String, Object...)
     */
    boolean repeat(Entity anchor, long delayTicks, long periodTicks, int times, String function, Object... args);
}
