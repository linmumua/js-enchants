package com.linmumua.jsenchant.enchant;

import java.util.List;

/**
 * 一次附魔定义重载的结果，供 {@code /jse reload} 与日志如实报告。
 *
 * <p>为什么要单独用结果对象而不是返回数量：只返回数量时，「读到 0 个」和
 * 「加载失败了、旧表还在用」在调用方看起来一模一样，命令只能假报成功。
 *
 * @param loaded         当前生效的定义数量（未发布时是保留下来的旧快照数量）
 * @param failures       失败明细，每条形如 {@code fireball.yml: 未知的 trigger 'x'}
 * @param directoryReady 目录可创建且可列举
 * @param published      本次是否真的发布了新表；{@code false} 表示旧快照原样保留
 *                       （目录不可用、或任意文件校验失败）
 */
public record EnchantLoadReport(int loaded, List<String> failures, boolean directoryReady, boolean published) {

    public EnchantLoadReport {
        failures = List.copyOf(failures);
    }

    public int failed() {
        return this.failures.size();
    }

    /**
     * 是否是一次干净的重载：没有失败并且新表已经生效。
     */
    public boolean success() {
        return this.published && this.failures.isEmpty();
    }

    /** 一行可读摘要，直接用于命令回显。 */
    public String summary() {
        if (!this.directoryReady) {
            return "附魔目录不可用，已保留上一份定义（" + this.loaded + " 个）";
        }

        if (this.published) {
            return "附魔 " + this.loaded + " 个";
        }

        final String reason = this.failed() + " 个文件校验失败: " + String.join("; ", this.failures);

        return 0 == this.loaded
                ? "附魔定义未发布（" + reason + "），当前没有任何可用定义"
                : "附魔定义未发布（" + reason + "），仍使用上一份定义（" + this.loaded + " 个）";
    }
}
