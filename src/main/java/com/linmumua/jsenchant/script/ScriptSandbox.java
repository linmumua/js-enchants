package com.linmumua.jsenchant.script;

import org.mozilla.javascript.ClassShutter;

import java.util.Collection;
import java.util.List;

/**
 * 脚本沙箱的放行策略。
 *
 * <p>两个必须知道的事实（都经过实测）：
 * <ol>
 *   <li>{@link ClassShutter} 不只拦截脚本里写的 {@code java.lang.System}，也拦截 Rhino
 *       给「注入对象」建立成员表（{@code JavaMembers}）的过程。所以「全拒绝」会导致
 *       连我们注入的 {@code ScriptApi} 都调不动。</li>
 *   <li>Rhino 处理方法签名时会解析 {@code java.lang.String}、{@code java.lang.Integer}
 *       这类基础类型。把它们拉黑会让所有带参数的方法直接失效。</li>
 * </ol>
 *
 * <p>策略分两层：
 * <ul>
 *   <li><b>硬拒绝</b>（{@link #DENIED}，不可配置）：反射、类加载、进程执行、文件与网络 IO、
 *       JNDI、脚本引擎自身。这些一放开就等于把宿主进程交出去，所以永远不开放给配置。</li>
 *   <li><b>放行前缀</b>（{@code allowedPackages}，来自 {@code config.yml}）：
 *       默认只有本框架与 Bukkit / Adventure / Paper 的 API。服主可以按需追加第三方插件包
 *       （例如 CraftEngine、PacketEvents），把「脚本能调什么」变成配置而不是改代码。</li>
 * </ul>
 *
 * <p><b>顺序是刻意的</b>：基础类型 → 硬拒绝 → 放行前缀。硬拒绝必须排在放行之前，
 * 否则配置里写一个 {@code java.} 就能把 {@code java.lang.Runtime} 一起放进来。
 *
 * <p><b>这不是绝对隔离。</b>脚本拿到 Bukkit 对象后仍可能做到危险操作（例如经由命令派发）。
 * 真正的信任边界是脚本目录的写权限，部署时应限制为管理员可写。
 */
public final class ScriptSandbox implements ClassShutter {

    /** 精确黑名单：这些包/类一旦可达就等于拿到宿主进程控制权。不开放给配置。 */
    private static final String[] DENIED = {
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.lang.ClassLoader",
            "java.lang.System",
            "java.lang.Thread",
            "java.lang.Class",
            "java.io.",
            "java.nio.",
            "java.net.",
            "java.util.concurrent.",
            "javax.naming.",
            "javax.script.",
            "jdk.internal.",
            "jdk.jfr.",
            "sun.misc.",
            "com.sun.",
            "org.mozilla.javascript.",
    };

    /** 精确白名单：Rhino 解析方法签名时会用到，必须放行。 */
    private static final String[] ALLOWED_EXACT = {
            "java.lang.String",
            "java.lang.CharSequence",
            "java.lang.Number",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Boolean",
            "java.lang.Character",
            "java.lang.Object",
            "java.lang.Enum",
            "java.lang.Iterable",
            "java.lang.Comparable",
            "java.util.List",
            "java.util.Map",
            "java.util.Set",
            "java.util.Collection",
            "java.util.UUID",
            "java.util.Optional",
    };

    /**
     * 默认放行前缀，与引入配置前的行为完全等价。
     *
     * <p>配置里写 {@code scripts.allowed-packages} 时是<b>整体替换</b>而不是追加：
     * 这样「我到底放开了什么」永远是配置文件上那几行，不必再去猜底层默认值。
     */
    public static final List<String> DEFAULT_PACKAGES = List.of(
            "com.linmumua.jsenchant.",
            "org.bukkit.",
            "net.kyori.",
            "io.papermc.paper."
    );

    private final String[] allowed;

    public ScriptSandbox() {
        this(DEFAULT_PACKAGES);
    }

    /**
     * @param packages 放行的包前缀（以 {@code .} 结尾更精确）；
     *                 {@code null} 或空集合时退回 {@link #DEFAULT_PACKAGES}
     */
    public ScriptSandbox(final Collection<String> packages) {
        final Collection<String> source =
                (null == packages || packages.isEmpty()) ? DEFAULT_PACKAGES : packages;

        this.allowed = source.toArray(new String[0]);
    }

    @Override
    public boolean visibleToScripts(final String fullClassName) {
        for (final String allowed : ALLOWED_EXACT) {
            if (allowed.equals(fullClassName)) {
                return true;
            }
        }

        for (final String denied : DENIED) {
            if (fullClassName.startsWith(denied)) {
                return false;
            }
        }

        for (final String prefix : this.allowed) {
            if (fullClassName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }
}
