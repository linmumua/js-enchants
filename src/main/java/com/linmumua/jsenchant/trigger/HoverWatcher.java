package com.linmumua.jsenchant.trigger;

import com.linmumua.jsenchant.JsEnchantPlugin;
import com.linmumua.jsenchant.display.HighlightService;
import com.linmumua.jsenchant.enchant.EnchantApplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挖前预览的触发源：盯着每名玩家的准星，指向变了就问一次脚本要不要预览。
 *
 * <h2>为什么是轮询而不是事件</h2>
 *
 * <p>Bukkit 没有「准星指向变化」事件。{@code PlayerInteractEvent} 只在点击时触发，
 * 只能做「点了才亮」，做不出「对准就一直亮」。所以这里对每名玩家挂一条周期任务，
 * 在自己的区域线程上读 {@code getTargetBlockExact}——那是一次射线追踪，
 * 读的是玩家视角与方块状态，必须在本线程。
 *
 * <h2>为什么只在目标变化时派发</h2>
 *
 * <p>脚本每次派发都会重建一批展示实体。若每 4 tick 无条件重建，玩家站着不动也会
 * 持续生成/销毁实体，既浪费又没有视觉收益。因此这里记录上一次的「目标指纹」，
 * 只有指纹真的变了才派发；指纹为空（准星不再指向方块）则清掉旧高亮。
 *
 * <p>指纹必须同时覆盖<b>准星目标</b>与<b>主手物品</b>：预览的内容是「手上这件装备会连锁
 * 哪些方块」，而换主手、附魔等级变化时准星与方块数据一个都不变。只记方块就会让玩家
 * 换上一把带连锁的镐子、准星还停在同一格时永远看不到预览，切回空手也永远不清理。
 *
 * <p>还要覆盖<b>潜行状态</b>：脚本可以只在潜行时给预览，而按下/松开潜行时方块与主手都没变，
 * 漏掉这项就会让描边卡在屏幕上。
 *
 * <h2>生命周期</h2>
 *
 * <p>周期任务挂在玩家实体调度器上：玩家下线时任务自动退休，这里同时清掉高亮实体，
 * 避免玩家列表里留下永远不会被回收的展示实体。
 */
public final class HoverWatcher implements Listener {

    /** 检查间隔（tick）。4 tick ≈ 0.2 秒，肉眼感觉不到延迟，又不至于每 tick 做一次射线追踪。 */
    private static final long PERIOD_TICKS = 4L;

    /** 探测距离（方块）。6 格比玩家的 4.5 格交互距离略远，避免贴脸时反而看不到预览。 */
    private static final int REACH = 6;

    /** 主手读不出来时的稳定占位：不能是随机值，否则每拍都判定为「变化」。 */
    private static final String UNKNOWN_HOLD = "unknown";

    private final JsEnchantPlugin services;
    private final HighlightService highlights;
    private final TriggerDispatcher dispatcher;

    /** 玩家 → 上一次的目标指纹。 */
    private final Map<UUID, String> lastTarget = new ConcurrentHashMap<>();

    public HoverWatcher(
            final JsEnchantPlugin services,
            final HighlightService highlights,
            final TriggerDispatcher dispatcher
    ) {
        this.services = services;
        this.highlights = highlights;
        this.dispatcher = dispatcher;
    }

    /** 给当前在线的每名玩家挂上周期任务；插件启用时调用一次。 */
    public void start() {
        for (final Player player : this.services.owner().getServer().getOnlinePlayers()) {
            this.watch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.watch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();

        this.lastTarget.remove(player.getUniqueId());
        this.highlights.clear(player);
    }

    private void watch(final Player player) {
        // 首次延迟写 0 是「下一个 tick 就开始观察」的意思，由 RegionTasks 实现统一规范化成
        // 服务端能接受的最小值（26.x 拒绝 initialDelay<=0）。下限只在那里兜一次，
        // 这里不再重复写 1，避免两处各有一套「最快」的定义。
        this.services.tasks().repeat(
                player,
                0L,
                PERIOD_TICKS,
                () -> this.tick(player),
                () -> this.forget(player)
        );
    }

    /** 每 PERIOD_TICKS 在玩家自己的区域线程上跑一次。 */
    private void tick(final Player player) {
        if (!player.isOnline()) {
            this.forget(player);

            return;
        }

        final Block target = targetOf(player);
        final String fingerprint = null == target ? null : this.fingerprintOf(player, target);

        if (Objects.equals(fingerprint, this.lastTarget.get(player.getUniqueId()))) {
            return;
        }

        if (null == fingerprint) {
            this.lastTarget.remove(player.getUniqueId());
            this.highlights.clear(player);

            return;
        }

        this.lastTarget.put(player.getUniqueId(), fingerprint);

        // 先清掉上一批：脚本若没有登记任何方块（比如这个附魔不支持预览），
        // show() 拿到空列表就等于清空，不会留下上一轮的高亮。
        final List<Block> buffer = new ArrayList<>();
        this.dispatcher.fireHover(player, target, buffer::add);
        this.highlights.show(player, buffer);
    }

    private void forget(final Player player) {
        this.lastTarget.remove(player.getUniqueId());
        this.highlights.clear(player);
    }

    /**
     * 本次派发的指纹：方块 + 主手。
     *
     * <p>两部分缺一不可。只比方块，换主手/换附魔等级时指纹不变，预览永远停在上一件物品的
     * 结果上；只比主手，准星移到相邻同材质方块时指纹不变，高亮会留在原格。
     */
    private String fingerprintOf(final Player player, final Block block) {
        return hoverFingerprint(blockFingerprint(block), this.heldFingerprint(player), player.isSneaking());
    }

    /**
     * 悬停签名 = 方块 + 主手 + 潜行状态。
     *
     * <p>潜行状态必须算进来：脚本可以只在潜行时给预览（{@code vein_miner} 就是这样），
     * 而玩家站在原地按下/松开潜行时，方块与主手一个都不变。少了这一项，
     * 「指纹没变」就会直接 return，松开潜行后旧描边永远清不掉。
     */
    static String hoverFingerprint(final String blockPart, final String heldPart, final boolean sneaking) {
        return blockPart + "||" + heldPart + (sneaking ? "||sneak" : "||stand");
    }

    /**
     * 主手签名：物品类型 + 本框架记录在其上的附魔与等级。
     *
     * <p>这些正是 {@code TriggerDispatcher#fireHover} 决定「预览画什么」的全部输入，
     * 因此它们一变就必须重新派发。
     */
    private String heldFingerprint(final Player player) {
        try {
            final ItemStack item = player.getInventory().getItemInMainHand();
            final EnchantApplier applier = this.services.getApplier();
            final Map<String, Integer> levels = null == applier ? Map.of() : applier.getLevels(item);

            return itemFingerprint(null == item ? null : item.getType(), levels);
        } catch (final RuntimeException exception) {
            // 背包正被别的线程改写/玩家正被移除：给一个稳定取值，不要每拍都变——
            // 那等于每 4 tick 重建一套展示实体，正是这里要避免的开销。
            return UNKNOWN_HOLD;
        }
    }

    /**
     * 纯函数版本的主手签名，便于单测；{@code null}/空手单独成一种取值。
     *
     * <p>这里用 {@code Material.AIR == type} 而不是 {@code type.isAir()}：后者要查方块注册表，
     * 在没有服务端的环境里会直接抛错，把这条逻辑变成不可测；而主手空着的取值只可能是
     * {@code AIR}，{@code CAVE_AIR}/{@code VOID_AIR} 这类变体不会出现在物品栏里。
     *
     * <p>等级条目排序后再拼，避免 {@code PersistentDataContainer} 的迭代顺序变化
     * 让同一件物品算出两个指纹。
     */
    static String itemFingerprint(final Material type, final Map<String, Integer> levels) {
        if (null == type || Material.AIR == type) {
            return "empty";
        }

        if (null == levels || levels.isEmpty()) {
            return type.name();
        }

        final List<String> parts = new ArrayList<>(levels.size());

        for (final Map.Entry<String, Integer> entry : levels.entrySet()) {
            parts.add(entry.getKey() + '=' + entry.getValue());
        }

        Collections.sort(parts);

        return type.name() + '{' + String.join(",", parts) + '}';
    }

    private static Block targetOf(final Player player) {
        try {
            return player.getTargetBlockExact(REACH);
        } catch (final RuntimeException exception) {
            // 玩家在别的线程上被移除、或世界正在卸载：本轮跳过，下个周期自然恢复。
            return null;
        }
    }

    /**
     * 方块指纹：坐标 + 方块数据。
     *
     * <p>只比坐标不够——同一格被换成别的方块（自定义方块切换、方块更新）时范围会变；
     * 只比材质也不够——相邻两格同材质时准星移过去就检测不到变化。
     */
    static String blockFingerprint(final Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ()
                + '|' + block.getBlockData().getAsString();
    }
}
