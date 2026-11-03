package com.linmumua.jsenchant.script;

import java.util.List;

/**
 * 一次脚本全量加载的结果，供 {@code /jse reload} 与日志如实报告。
 *
 * <p>只返回数量时，「没有脚本」和「脚本全编译失败了」在调用方看来完全一样，
 * 命令就会把失败报成成功。失败明细必须一路带到命令输出里。
 *
 * @param loaded         本次成功加载的脚本数量
 * @param failures       失败明细，每条形如 {@code fireball.js: 第 3 行: ...}
 * @param directoryReady 目录可创建且可列举；为 {@code false} 时本次未做任何替换
 */
public record ScriptLoadReport(int loaded, List<String> failures, boolean directoryReady) {

    public ScriptLoadReport {
        failures = List.copyOf(failures);
    }

    public int failed() {
        return this.failures.size();
    }

    public boolean success() {
        return this.directoryReady && this.failures.isEmpty();
    }

    /** 一行可读摘要，直接用于命令回显。 */
    public String summary() {
        if (!this.directoryReady) {
            return "脚本目录不可用，本次未加载任何脚本";
        }

        if (this.failures.isEmpty()) {
            return "脚本 " + this.loaded + " 个";
        }

        return "脚本 " + this.loaded + " 个，" + this.failed() + " 个失败（旧版本仍生效）: "
                + String.join("; ", this.failures);
    }
}
