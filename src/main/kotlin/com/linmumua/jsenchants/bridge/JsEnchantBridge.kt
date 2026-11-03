package com.linmumua.jsenchants.bridge

import com.linmumua.jsenchant.JsEnchantPlugin
import com.linmumua.jsenchant.platform.BukkitRegionTasks
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.platform.BukkitPlugin
import java.util.logging.Level

/**
 * TabooLib 生命周期薄桥。
 *
 * 唯一职责：在正确的生命周期里创建/关闭 Java 业务服务 [JsEnchantPlugin]。
 * 业务逻辑一律不写在这里，避免出现「两套入口都能改状态」的分裂。
 *
 * owner 取自 [BukkitPlugin.getInstance]（其构造函数第一件事就是给静态字段赋值），
 * 而不是 `taboolib.common.platform.Plugin.getInstance()`：后者只有在业务侧继承了
 * TabooLib 的 Plugin 类时才非空，本项目是纯组合式服务，依赖它会在运行期拿到 null。
 *
 * <p>调度契约在这里注入：业务服务自己不再碰 Bukkit 调度器，初始化与重载都由
 * [BukkitRegionTasks] 排到全局区域线程（Folia 上没有全局主线程可借）。
 */
object JsEnchantBridge {

    /**
     * 状态锁：ENABLE/DISABLE 可能被 TabooLib 重复或以交错顺序触发，
     * `service`/`owner` 必须成对切换，否则第二次 ENABLE 会装配出第二套监听器。
     */
    private val lock = Any()

    /** 真正的 Bukkit 插件实例，由 LOAD 阶段解析，ENABLE 阶段使用。 */
    private var owner: JavaPlugin? = null

    /** 已启用的业务服务；DISABLE 时关闭并清空。 */
    private var service: JsEnchantPlugin? = null

    /**
     * LOAD 阶段只解析 owner：此时的世界/玩家相关初始化还不该做，
     * 与旧 JavaPlugin 的 onLoad/onEnable 分工保持一致。
     */
    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        val plugin = resolveOwner()

        synchronized(lock) {
            owner = plugin
        }

        if (plugin == null) {
            Bukkit.getLogger().severe("[JsEnchants] 无法取得 BukkitPlugin 实例，后续启用阶段将无法初始化。")
        }
    }

    /**
     * ENABLE 阶段：装配业务服务并注入调度契约。
     *
     * 真正的初始化由 [JsEnchantPlugin.enable] 排到全局区域线程执行，这里只保证「只装配一次」；
     * 如果连调度都失败（服务端已经开始卸载），就地回滚，绝不留下面向玩家的半初始化实例。
     */
    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        synchronized(lock) {
            // 防重复：已启用（或上一轮还没停用）时不再装配第二套监听器。
            if (null != service) {
                return
            }

            val plugin = owner ?: resolveOwner()

            if (plugin == null) {
                Bukkit.getLogger().severe("[JsEnchants] 缺少 JavaPlugin owner，功能未启用。")
                return
            }

            owner = plugin

            val created = JsEnchantPlugin(plugin, BukkitRegionTasks(plugin))

            service = created

            try {
                created.enable()
            } catch (throwable: Throwable) {
                // 启动失败必须大声报错：半初始化状态下继续对外提供命令只会更难排查。
                service = null
                Bukkit.getLogger().log(Level.SEVERE, "[JsEnchants] 启用失败，插件功能不完整。", throwable)
                rollback(created)
            }
        }
    }

    /**
     * DISABLE 阶段：关闭监听与调度器。先清引用，避免关闭途中的异常让它残留。
     *
     * 若初始化还排在全局队列里没执行，[JsEnchantPlugin.disable] 的停用标记会让它直接放弃，
     * 因此这里不需要等待任何排队任务。
     */
    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        val created = synchronized(lock) {
            val current = service

            service = null
            owner = null

            current
        }

        if (created == null) {
            return
        }

        try {
            created.disable()
        } catch (throwable: Throwable) {
            Bukkit.getLogger().log(Level.WARNING, "[JsEnchants] 卸载过程出错。", throwable)
        }
    }

    /**
     * 真正注册到服务端的 JavaPlugin 就是 TabooLib 的平台装载器，它的构造函数第一件事
     * 就是给 `getInstance()` 背后的静态字段赋值，所以在 LOAD/ENABLE 阶段一定可用。
     * 返回类型显式写成可空：万一将来装载器换了实现，这里会退化成一条明确的告警，
     * 而不是让 Kotlin 把判空优化掉（平台类型上的判空会被当成恒假条件）。
     */
    private fun resolveOwner(): JavaPlugin? = BukkitPlugin.getInstance()

    /** 失败回滚：关闭可能已经建好的监听线程与调度器，可重复调用。 */
    private fun rollback(created: JsEnchantPlugin) {
        try {
            created.disable()
        } catch (throwable: Throwable) {
            Bukkit.getLogger().log(Level.WARNING, "[JsEnchants] 回滚半初始化资源时出错。", throwable)
        }
    }
}
