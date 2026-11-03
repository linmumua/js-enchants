package com.linmumua.jsenchant.platform;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * 区域化调度契约：本插件所有「在某个线程上做点事」的诉求都必须从这里走。
 *
 * <p>为什么不再有「主线程」这个概念：Folia 没有全局主线程，取而代之的是若干区域线程
 * 加一条全局线程。{@link #global(Runnable)} <b>不是</b>全能的「主线程替代品」——
 * 全局线程碰实体/世界方块状态会直接抛错，实体与方块状态的读写必须走
 * {@link #entity(Entity, Runnable, Runnable)} 或 {@link #region(Location, Runnable)}。
 *
 * <p>三条通用约定：
 * <ul>
 *   <li><b>不阻塞</b>：任何实现都不得跨区等待、{@code Future#get} 或 {@code join}。</li>
 *   <li><b>可退休</b>：停用后排队中的任务不再执行；实现必须自行取消已登记的句柄。</li>
 *   <li><b>归属只在所属区域读</b>：{@link #owns(Entity)} 之类的判断不得为了判归属
 *       去读取他区的实体/方块状态。</li>
 * </ul>
 *
 * <p>Paper 上这几个调度器同样存在（内部退化成主线程调度），因此本插件不需要
 * 「Folia 一套、Paper 另一套」的双实现。
 */
public interface RegionTasks {

    /**
     * 把任务排到全局区域线程（全局数据、配置文件、非区域绑定的服务状态）。
     *
     * @param task 任务体；插件停用后不会执行
     */
    void global(Runnable task);

    /**
     * 在实体所属区域执行任务。
     *
     * <p>当前线程已经拥有该实体时同步执行，否则交给实体调度器排队。
     *
     * @param entity  目标实体
     * @param task    任务体
     * @param retired 任务<b>未执行</b>时的回调（实体退休/被移除/调度被拒），可为 {@code null}
     * @return {@code true} 表示已同步执行或已被实体调度器接受；
     *         {@code false} 表示没有执行，此情况下若 {@code retired} 非空则它已被调用
     *         （且只会被调用一次）。调用方可以只依赖 {@code retired} 做提示，
     *         不必再为 {@code false} 单独发消息。
     */
    boolean entity(Entity entity, Runnable task, Runnable retired);

    /**
     * 在给定坐标所属的区域执行任务。
     *
     * @param location 目标坐标，需带世界
     * @param task     任务体
     */
    void region(Location location, Runnable task);

    /** 当前线程是否拥有该实体（不读取他区实体状态）。 */
    boolean owns(Entity entity);

    /** 当前线程是否拥有以该坐标为中心、半径 {@code squareRadiusChunks} 个区块的方形区域。 */
    boolean owns(Location location, int squareRadiusChunks);

    /** 调度器是否仍可用（插件未停用、未关闭）。 */
    boolean active();

    /**
     * 在实体所属区域按固定频率反复执行任务。
     *
     * <p>这是「持续观察某个实体」的唯一入口：准星悬停检测、高亮刷新这类工作必须挂在
     * 实体自己的区域线程上，不能借全局线程去读别人的位置——那在 Folia 上是越界读。
     *
     * <p>与 {@link #entity(Entity, Runnable, Runnable)} 的区别：那个是一次性的，
     * 这里返回后任务仍在按周期继续跑，直到插件停用、实体退休或调用方自行取消。
     *
     * @param entity      任务归属实体（通常是玩家）
     * @param delayTicks  首次执行前的延迟（tick）。{@code 0} 或负数按 {@code 1} 处理：
     *                    26.x 的实体调度器拒绝 {@code initialDelay <= 0}（直接抛
     *                    {@link IllegalArgumentException}，任务不会注册），而「0 = 下一 tick 就跑」
     *                    的原意由 1 tick 表达即可；调用方不需要自己兜这个下限
     * @param periodTicks 间隔（tick），小于 1 时按 1 处理
     * @param task        任务体
     * @param retired     未能执行或之后被取消时的回调，可为 {@code null}
     * @return 是否已受理；{@code false} 表示没有执行（会先调用 {@code retired}）
     */
    boolean repeat(Entity entity, long delayTicks, long periodTicks, Runnable task, Runnable retired);

    /**
     * 在实体所属区域延迟执行一次任务。
     *
     * <p>与 {@link #repeat} 的区别是它只跑一次：脚本要「20 tick 后爆炸」时用这条，
     * 而不是拿周期任务自己数次数——后者在字面意义上永远停不下来。
     *
     * @param entity     任务归属实体（通常是触发者）
     * @param delayTicks 延迟（tick），小于 1 时按 1 处理，理由同 {@link #repeat}
     * @param task       任务体
     * @param retired    未能执行时的回调，可为 {@code null}
     * @return 是否已受理；{@code false} 表示没有执行（会先调用 {@code retired}）
     */
    boolean later(Entity entity, long delayTicks, Runnable task, Runnable retired);

    /**
     * 关闭：取消所有排队任务，此后提交的任务一律不执行。可重复调用。
     *
     * <p>调用方必须先关闭自己的监听器与线程，再关闭这里——顺序反了，被取消的任务
     * 可能还没来得及把引用清干净。
     */
    void close();
}
