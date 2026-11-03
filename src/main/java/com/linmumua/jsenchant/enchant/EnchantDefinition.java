package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.trigger.TriggerBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 一个附魔的静态定义，来自 {@code enchants/*.yml}。
 *
 * @param id            唯一标识，只允许小写字母、数字、下划线和连字符（要进 PDC key）
 * @param display       展示名，支持 MiniMessage
 * @param maxLevel      最大等级
 * @param items         可附着的物品类型
 * @param slots         触发时读取哪些装备槽；默认只有主手
 * @param trigger       触发时机
 * @param cooldownMillis 冷却毫秒数，0 表示无冷却
 * @param chanceBase    基础触发概率（0~1）
 * @param chancePerLevel 每级增加的概率
 * @param script        对应脚本文件名，如 {@code fireball.js}
 * @param enabled       是否启用
 * @param rarity        稀有度，决定 Lore 配色；{@code yml} 未写时由 {@code max-level} 推导
 * @param options       附魔自定义选项，透传给脚本（{@code ctx.option(...)}）；
 *                      yml 未写时为空映射，键与值都不做框架侧解释
 */
public record EnchantDefinition(
        String id,
        String display,
        int maxLevel,
        Set<Material> items,
        Set<EquipmentSlot> slots,
        TriggerBinding trigger,
        long cooldownMillis,
        double chanceBase,
        double chancePerLevel,
        String script,
        boolean enabled,
        EnchantRarity rarity,
        Map<String, Object> options
) {

    /**
     * 规整 {@code options}：{@code null} 等价于空映射，并做一次不可变拷贝。
     *
     * <p>用 {@link LinkedHashMap} 保序拷贝而不是直接 {@code Map.copyOf}：
     * 服主排过的选项顺序在诊断输出里应当保持原样，也不必接受 {@code copyOf} 对 {@code null} 值的挑剔。
     */
    public EnchantDefinition {
        options = null == options ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * 不带自定义选项的构造重载：选项为空映射。
     *
     * <p>存量定义（含测试与脚本侧拼装）几乎都不写 {@code options}，
     * 保留这个重载可以让它们一行不改；语义等价于 yml 里没写 {@code options:}。
     */
    public EnchantDefinition(
            final String id,
            final String display,
            final int maxLevel,
            final Set<Material> items,
            final Set<EquipmentSlot> slots,
            final TriggerBinding trigger,
            final long cooldownMillis,
            final double chanceBase,
            final double chancePerLevel,
            final String script,
            final boolean enabled,
            final EnchantRarity rarity
    ) {
        this(
                id, display, maxLevel, items, slots, trigger, cooldownMillis,
                chanceBase, chancePerLevel, script, enabled, rarity, Map.of()
        );
    }

    /**
     * 不带稀有度的构造重载，稀有度按 {@code max-level} 推导。
     *
     * <p>等价于 {@link EnchantRarity#fromMaxLevel(int)}，也就是 yml 里没写 {@code rarity}
     * 时的解析结果；保留它是为了让「只关心等级」的调用方（测试构造、脚本侧拼装）
     * 不必重复写一遍推导，也避免两处推导规则写岔。
     */
    public EnchantDefinition(
            final String id,
            final String display,
            final int maxLevel,
            final Set<Material> items,
            final Set<EquipmentSlot> slots,
            final TriggerBinding trigger,
            final long cooldownMillis,
            final double chanceBase,
            final double chancePerLevel,
            final String script,
            final boolean enabled
    ) {
        this(
                id, display, maxLevel, items, slots, trigger, cooldownMillis,
                chanceBase, chancePerLevel, script, enabled, EnchantRarity.fromMaxLevel(maxLevel)
        );
    }

    /**
     * 某等级的触发概率，封顶 1。
     */
    public double chanceAt(final int level) {
        final double chance = this.chanceBase + this.chancePerLevel * Math.max(0, level - 1);

        return Math.min(1.0D, Math.max(0.0D, chance));
    }

    /** 该等级是否允许附着在该物品上。 */
    public boolean supports(final Material material) {
        return this.items.isEmpty() || this.items.contains(material);
    }

    /** 把等级夹到合法范围。 */
    public int clampLevel(final int level) {
        return Math.min(this.maxLevel, Math.max(1, level));
    }
}
