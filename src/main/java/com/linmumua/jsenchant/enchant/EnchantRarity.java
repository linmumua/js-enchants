package com.linmumua.jsenchant.enchant;

import java.util.Locale;

/**
 * 附魔稀有度，决定 Lore 行的配色分档。
 *
 * <p>顺序即「从低到高」，配色表、数据包与展示都按这个顺序取用，不要调整声明顺序。
 *
 * <p>稀有度有两套来源：{@code enchants/*.yml} 里显式写的 {@code rarity}，
 * 或 {@link #fromMaxLevel(int)} 按最大等级推导。推导是缺省路径而不是兜底——
 * 存量配置都没有该字段，若缺省统一取 {@link #COMMON}，按稀有度分色会退化成单色。
 */
public enum EnchantRarity {

    COMMON("common"),
    UNCOMMON("uncommon"),
    RARE("rare"),
    EPIC("epic");

    private final String id;

    EnchantRarity(final String id) {
        this.id = id;
    }

    /** 小写 id，用于配置键（如 {@code lore.rarity.<id>}）与数据包。 */
    public String id() {
        return this.id;
    }

    /**
     * 按小写 id 查稀有度，大小写不敏感、两端空白容忍。
     *
     * @return 未知 id 或 {@code null} 时返回 {@code null}，不抛异常——
     *         调用方（解析器）要拿这个结果决定是否拒绝整条定义，异常会丢失「哪个字段错了」的上下文
     */
    public static EnchantRarity byId(final String id) {
        if (null == id) {
            return null;
        }

        final String normalized = id.trim().toLowerCase(Locale.ROOT);

        for (final EnchantRarity rarity : values()) {
            if (rarity.id.equals(normalized)) {
                return rarity;
            }
        }

        return null;
    }

    /**
     * 按最大等级推导稀有度：{@code 1 → common}、{@code 2 → uncommon}、
     * {@code 3 → rare}、{@code ≥4 → epic}。
     *
     * <p>{@code ≤0} 视为 1 级（调用方通常已经用过 {@code Math.max(1, ...)}，
     * 这里再收一次口，避免非法输入产生「没有稀有度」的状态）。
     */
    public static EnchantRarity fromMaxLevel(final int maxLevel) {
        if (maxLevel <= 1) {
            return COMMON;
        }

        if (2 == maxLevel) {
            return UNCOMMON;
        }

        if (3 == maxLevel) {
            return RARE;
        }

        return EPIC;
    }
}
