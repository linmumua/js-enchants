package com.linmumua.jsenchant.display;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 挖前预览的显示层：把「会被连锁的方块」画成一组只对某一名玩家可见的 {@link BlockDisplay}。
 *
 * <h2>为什么用实体而不是发包</h2>
 *
 * <p>要让非准星指向的方块出现描边，只有两种办法：客户端 mod，或者真实的展示实体。
 * {@code sendBlockChange} 只能替换方块外观（把铁矿显示成别的材质），做不出「高亮」。
 * 所以这里用 BlockDisplay，靠 {@code setBrightness} 全亮 + {@code setGlowing} 描边
 * 制造高亮感。
 *
 * <h2>只对一个人可见</h2>
 *
 * <p>实体生成时置 {@code setVisibleByDefault(false)}，随后只对目标玩家
 * {@link Player#showEntity(Plugin, org.bukkit.entity.Entity)}。这样其他玩家从未收到过
 * 这个实体的生成包，不需要对全服逐一 hide，也不会因为别人后进视野而漏掉。
 *
 * <h2>线程边界</h2>
 *
 * <p>所有方法都必须在<b>目标玩家自己的区域线程</b>上调用：{@code spawn} 操作世界方块状态，
 * {@code showEntity} 操作玩家连接，两者都不能跨区。调用方（HoverWatcher）通过实体周期任务保证这点；
 * 方块本身的归属由 {@code ScriptApi#highlight} 在收进列表之前就已校验过——不在当前线程的方块
 * 会当场报错，不会走到这里来。
 *
 * <p>清理同理：{@link #clear(Player)} / {@link #clear(UUID)} 删除的是世界里的实体，
 * 必须与创建它们在同一个区域线程上调用。{@link #clearAll()} 用于停用收尾，
 * 它跑在全局线程上、无法保证与每个方块同区，跨区删除会被服务端拒绝（异常在此被吞掉）；
 * 这些实体是 {@code setPersistent(false)} 的，不会写盘，最坏情况只占实体额度直到区块卸载。
 */
public final class HighlightService {

    /** 满亮度：暗处也能看清高亮，避免玩家在矿洞里看不出范围。 */
    private static final int FULL_LIGHT = 15;

    /**
     * 略大于方块本体。
     *
     * <p>正好 1.0 会与原方块共面，客户端渲染出现 z-fighting（闪烁的格纹）。
     * 放大 1% 让它整块包住原方块，视觉上是「边缘亮了一圈」，不是覆盖纹理。
     */
    private static final float SCALE = 1.01F;

    /** 放大后要往回挪半个增量，否则方块整体会偏一个角。 */
    private static final float INSET = (SCALE - 1.0F) / 2.0F;

    private static final Transformation OVERLAY = new Transformation(
            new Vector3f(-INSET, -INSET, -INSET),
            new Quaternionf(),
            new Vector3f(SCALE, SCALE, SCALE),
            new Quaternionf()
    );

    /**
     * 单次预览的方块上限。
     *
     * <p>每个高亮都是一个真实实体，脚本若在 BFS 里写错上限就会生成上千个实体，
     * 直接在服务端层面兜一道，超出的部分静默丢弃。
     */
    private static final int MAX_BLOCKS = 64;

    private final Plugin plugin;

    /** 玩家 → 当前挂在他名下的展示实体。 */
    private final Map<UUID, List<BlockDisplay>> shown = new ConcurrentHashMap<>();

    public HighlightService(final Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 用这一批方块替换该玩家当前的高亮。
     *
     * <p>整批替换而不是增量更新：预览每次都是「重新算一遍会连锁哪些方块」，
     * 增量合并会让上一轮算错的残留一直留在画面上。
     *
     * @param blocks 要高亮的方块；空列表等价于清除
     */
    public void show(final Player player, final List<Block> blocks) {
        if (null == player) {
            return;
        }

        this.clear(player);

        if (null == blocks || blocks.isEmpty()) {
            return;
        }

        final List<BlockDisplay> created = new ArrayList<>(Math.min(blocks.size(), MAX_BLOCKS));
        final Set<String> seen = new HashSet<>();

        for (final Block block : blocks) {
            if (created.size() >= MAX_BLOCKS) {
                break;
            }

            if (null == block) {
                continue;
            }

            final Location location = block.getLocation();

            // 同一格被脚本重复登记时只画一个，否则几十个实体共面又会闪烁。
            if (!seen.add(key(location))) {
                continue;
            }

            final BlockDisplay display = this.spawn(player, block, location);

            if (null != display) {
                created.add(display);
            }
        }

        if (!created.isEmpty()) {
            this.shown.put(player.getUniqueId(), created);
        }
    }

    /** 清掉该玩家名下的全部高亮。可以在玩家已下线后调用。 */
    public void clear(final Player player) {
        if (null != player) {
            this.clear(player.getUniqueId());
        }
    }

    /** 清掉某个玩家名下的全部高亮；重复调用是空操作。 */
    public void clear(final UUID playerId) {
        if (null == playerId) {
            return;
        }

        final List<BlockDisplay> previous = this.shown.remove(playerId);

        if (null == previous) {
            return;
        }

        for (final BlockDisplay display : previous) {
            this.discard(display);
        }
    }

    /**
     * 清掉所有玩家的高亮。
     *
     * <p>插件停用时必须调：这些实体是 {@code setPersistent(false)} 的，服务端会自己回收，
     * 但保留到关服那一刻仍然会占用实体上限，也会在重载期间继续显示旧范围。
     */
    public void clearAll() {
        for (final UUID playerId : new ArrayList<>(this.shown.keySet())) {
            this.clear(playerId);
        }
    }

    private BlockDisplay spawn(final Player viewer, final Block block, final Location location) {
        final World world = location.getWorld();

        if (null == world) {
            return null;
        }

        try {
            final BlockDisplay display = world.spawn(location, BlockDisplay.class, entity -> {
                entity.setBlock(block.getBlockData());
                entity.setGlowing(true);

                // 必须在加入世界之前置为「默认不可见」，否则生成包已经发给周围所有人了。
                entity.setVisibleByDefault(false);

                // 不写盘：重载/重启不该把这些预览实体留在区块里。
                entity.setPersistent(false);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setBrightness(new Display.Brightness(FULL_LIGHT, FULL_LIGHT));
                entity.setInterpolationDuration(0);
                entity.setTeleportDuration(0);
                entity.setTransformation(OVERLAY);
            });

            if (null == display) {
                return null;
            }

            try {
                viewer.showEntity(this.plugin, display);
            } catch (final RuntimeException exception) {
                // 生成成功但没交出去（玩家正好离线/连接已断）：这个实体「默认不可见」，
                // 不会被任何人看到也不会自己消失，留在世界里的唯一后果是白占实体额度。
                // 立刻回收，不要等区块卸载。
                this.discard(display);
                this.plugin.getLogger().log(Level.WARNING, "[JsEnchants] 高亮方块无法交付给玩家，已回收", exception);

                return null;
            }

            return display;
        } catch (final RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "[JsEnchants] 生成高亮方块失败", exception);

            return null;
        }
    }

    private void discard(final BlockDisplay display) {
        try {
            display.remove();
        } catch (final RuntimeException ignored) {
            // 实体可能已经因为区块卸载被回收了，这时 remove() 会抛错，不需要额外处理。
        }
    }

    private static String key(final Location location) {
        return location.getWorld().getName() + ':' + location.getBlockX()
                + ':' + location.getBlockY() + ':' + location.getBlockZ();
    }
}
