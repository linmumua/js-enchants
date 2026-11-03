package com.linmumua.jsenchant.enchant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 运行时附魔定义表。整体重建而非逐条替换——定义很少，重建更不容易出状态不一致。
 *
 * <p><b>重载是原子的</b>：先在本地建好新表，全部文件都通过校验后才一次性换成生效快照。
 * 旧实现先 {@code clear()} 再逐条填充，重载期间（以及重载失败之后）表是空的，
 * 而读取侧是事件线程——玩家会看到附魔「凭空失效」。因此：
 * <ul>
 *   <li>读取侧 {@link #get(String)} / {@link #all()} 只读 volatile 快照，不加锁；</li>
 *   <li>目录创建/列举失败、或任意一个 yml 校验失败（含两个文件写同一个 id）时，整批不发布，
 *       旧快照原样保留——不做「跳过坏文件发布剩下的部分」，那会悄悄下掉其余附魔；</li>
 *   <li>对外快照不可变，调用方无法绕过注册表改动生效状态。</li>
 * </ul>
 */
public final class EnchantRegistry {

    private final Logger logger;

    /** 当前生效快照。只整体替换，绝不原地修改。 */
    private volatile Map<String, EnchantDefinition> definitions = Map.of();

    /**
     * 当前全部定义用到的装备槽并集。
     *
     * <p>派发层每次触发只遍历这些槽位，而不是固定扫八格：全部附魔都用主手时，
     * 这里只有 {@code HAND}，热路径开销与引入槽位前完全一样。
     */
    private volatile Set<EquipmentSlot> usedSlots = Set.of(EquipmentSlot.HAND);

    /**
     * 用户自定义事件用到的类集合；事件桥据此注册 {@code HandlerList}。
     *
     * <p>只含自定义事件——内置短名的监听器是注解式的，由服务端按插件管理，
     * 两者不重叠，避免同一个事件被派发两次。
     */
    private volatile Set<Class<? extends Event>> customEvents = Set.of();

    public EnchantRegistry(final Logger logger) {
        this.logger = logger;
    }

    /**
     * 重新读取整个目录。目录不存在时视为空表。
     *
     * <p>加载失败（目录无法创建/列举）时保留上一份定义不动。
     *
     * @return 当前生效的附魔数量
     */
    public synchronized int loadAll(final Path directory) {
        return this.reload(directory, List.of()).loaded();
    }

    /** 兼容入口：不限制任何自定义事件。 */
    public synchronized EnchantLoadReport reload(final Path directory) {
        return this.reload(directory, List.of());
    }

    /**
     * 重新读取整个目录，并如实报告失败情况。
     *
     * <p><b>整批原子发布</b>：只要有任意一个 yml 解析/校验失败，就<b>不发布</b>新表，
     * 旧快照原样继续生效。不做「跳过坏文件、发布剩下的部分」——那会让一次手误悄悄
     * 下掉其余附魔，而玩家只会看到「附魔全都时灵时不灵」。
     *
     * <p>首次启动没有旧表时，失败会明确报告（{@code published()==false}）并记 SEVERE 日志，
     * 绝不静默变成「附魔 0 个」。
     */
    public synchronized EnchantLoadReport reload(final Path directory, final List<String> blockedEvents) {
        final EnchantParser parser = new EnchantParser(this.logger, blockedEvents);

        try {
            Files.createDirectories(directory);
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "无法创建附魔目录，已保留上一份定义: " + directory, exception);

            return new EnchantLoadReport(
                    this.definitions.size(),
                    List.of("无法创建目录: " + exception.getMessage()),
                    false,
                    false
            );
        }

        final List<Path> files = this.listYaml(directory);

        if (null == files) {
            this.logger.warning("无法列举附魔目录，已保留上一份定义: " + directory);

            return new EnchantLoadReport(this.definitions.size(), List.of("无法列举目录"), false, false);
        }

        // 新表在完全建好并且全部文件都通过校验之前，对外不可见。
        final Map<String, EnchantDefinition> built = new LinkedHashMap<>();
        final Map<String, String> sourceOf = new LinkedHashMap<>();
        final List<String> failures = new ArrayList<>();

        for (final Path file : files) {
            final EnchantParser.ParseOutcome outcome = parser.parseOutcome(file);

            if (!outcome.success()) {
                failures.add(file.getFileName() + ": " + outcome.error());

                continue;
            }

            final EnchantDefinition definition = outcome.definition();

            if (!definition.enabled()) {
                this.logger.info("附魔已禁用，跳过: " + definition.id());

                continue;
            }

            // 重复 id 是定义错误而不是「后者优先」：两个文件抢同一个 PDC 键，
            // 附魔会随文件列举顺序变化，这是必须整批拒绝的不确定状态。
            final String previous = sourceOf.put(definition.id(), file.getFileName().toString());

            if (null != previous) {
                failures.add(file.getFileName() + ": 附魔 id '" + definition.id()
                        + "' 与 " + previous + " 重复");

                continue;
            }

            built.put(definition.id(), definition);
        }

        if (!failures.isEmpty()) {
            this.logger.severe(
                    "附魔定义有 " + failures.size() + " 个文件校验失败，本次整批不发布"
                            + (this.definitions.isEmpty()
                                    ? "（当前没有任何可用定义）: "
                                    : "，仍使用上一份定义（" + this.definitions.size() + " 个）: ")
                            + String.join("; ", failures)
            );

            return new EnchantLoadReport(this.definitions.size(), failures, true, false);
        }

        this.definitions = Collections.unmodifiableMap(built);
        this.usedSlots = slotsOf(built);
        this.customEvents = customEventsOf(built);

        return new EnchantLoadReport(built.size(), failures, true, true);
    }

    /**
     * 收集「用户自定义事件」用到的类。
     *
     * <p>内置短名不在此列：它们的监听器是注解式的，若再把同一个事件注册进事件桥，
     * 每次触发都会走两遍派发。
     */
    private static Set<Class<? extends Event>> customEventsOf(final Map<String, EnchantDefinition> built) {
        final Set<Class<? extends Event>> custom = new LinkedHashSet<>();

        for (final EnchantDefinition definition : built.values()) {
            if (!definition.trigger().isBuiltin()) {
                custom.add(definition.trigger().eventClass());
            }
        }

        return custom.isEmpty() ? Set.of() : Collections.unmodifiableSet(custom);
    }

    /**
     * 当前生效定义里用户自定义的事件类，不可变。
     *
     * <p>事件桥按这份集合注册与注销 {@code HandlerList}；重载后数量变化即意味着
     * 需要重新平衡监听器。
     */
    public Set<Class<? extends Event>> customEvents() {
        return this.customEvents;
    }

    /**
     * 算出全部定义用到的槽位并集；空表时退回主手，保证派发路径永远有槽可读。
     */
    private static Set<EquipmentSlot> slotsOf(final Map<String, EnchantDefinition> built) {
        final Set<EquipmentSlot> union = EnumSet.noneOf(EquipmentSlot.class);

        for (final EnchantDefinition definition : built.values()) {
            union.addAll(definition.slots());
        }

        return union.isEmpty()
                ? Set.of(EquipmentSlot.HAND)
                : Collections.unmodifiableSet(union);
    }

    /**
     * 当前生效定义用到的装备槽，不可变。
     *
     * <p>派发层据此决定读哪些槽位的物品，不需要每次触发都遍历所有定义。
     */
    public Set<EquipmentSlot> usedSlots() {
        return this.usedSlots;
    }

    public EnchantDefinition get(final String id) {
        return null == id ? null : this.definitions.get(id);
    }

    /**
     * 当前生效定义，不可变快照。
     */
    public Map<String, EnchantDefinition> all() {
        return this.definitions;
    }

    public int size() {
        return this.definitions.size();
    }

    /**
     * @return 目录里的 yml 文件；列举失败时返回 {@code null}（与「目录为空」区分开）
     */
    private List<Path> listYaml(final Path directory) {
        try (final Stream<Path> stream = Files.list(directory)) {
            final List<Path> files = new ArrayList<>();

            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        final String name = path.getFileName().toString();

                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    // 固定按文件名排序：失败明细与「谁和谁重复」的诊断顺序才是确定的。
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);

            return files;
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "列举附魔目录失败: " + directory, exception);

            return null;
        }
    }
}
