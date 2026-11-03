package com.linmumua.jsenchant;

import com.linmumua.jsenchant.command.JsEnchantCommand;
import com.linmumua.jsenchant.config.ConfigService;
import com.linmumua.jsenchant.config.MessageService;
import com.linmumua.jsenchant.display.HighlightService;
import com.linmumua.jsenchant.enchant.BookAnvilListener;
import com.linmumua.jsenchant.enchant.EnchantApplier;
import com.linmumua.jsenchant.enchant.EnchantDatapackWriter;
import com.linmumua.jsenchant.enchant.EnchantLoadReport;
import com.linmumua.jsenchant.enchant.EnchantRegistry;
import com.linmumua.jsenchant.enchant.EnchantWatcher;
import com.linmumua.jsenchant.enchant.EnchantedBookService;
import com.linmumua.jsenchant.enchant.LootBookInjector;
import com.linmumua.jsenchant.platform.BukkitRegionTasks;
import com.linmumua.jsenchant.platform.LifecycleCoordinator;
import com.linmumua.jsenchant.platform.RegionTasks;
import com.linmumua.jsenchant.script.ScriptEnginePool;
import com.linmumua.jsenchant.script.ScriptLoadReport;
import com.linmumua.jsenchant.script.ScriptLoader;
import com.linmumua.jsenchant.script.ScriptRegistry;
import com.linmumua.jsenchant.script.ScriptSandbox;
import com.linmumua.jsenchant.script.ScriptWatcher;
import com.linmumua.jsenchant.trigger.EventBridge;
import com.linmumua.jsenchant.trigger.HoverWatcher;
import com.linmumua.jsenchant.trigger.TriggerDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 业务服务：装配脚本层、附魔层、配置层与命令，并持有真正的 {@link JavaPlugin} owner。
 *
 * <p>为什么不再继承 {@code JavaPlugin}：本插件由 TabooLib 的平台装载器
 * （{@code com.linmumua.jsenchants.taboolib.platform.BukkitPlugin}）作为唯一的
 * {@code JavaPlugin} 注册到服务端，业务类若再继承一次就会变成第二个插件实例——
 * 事件归谁、数据目录归谁都会含糊。因此本类只做组合：owner 只用于
 * 事件注册、主线程调度、{@code NamespacedKey}、数据目录与日志。
 *
 * <p>初始化顺序与旧 {@code onEnable} 完全一致：释放默认文件 → 读配置/文案 →
 * 脚本层（编译失败保留上一版）→ 脚本监听 → 附魔层 → 附魔监听 → 读写层 →
 * 命令 → 事件。
 *
 * <p><b>线程模型</b>：Folia 没有全局主线程，所有「在某个线程上做事」的诉求一律走
 * {@link RegionTasks}（见 {@link #tasks()}）。初始化与重载都整段序列化到全局区域线程，
 * 提交即返回、从不阻塞等待；实体与背包状态必须在实体所属区域线程上读写，由调用方
 * 自己通过 {@link #tasks()} 进入。
 */
public final class JsEnchantPlugin {

    /** 随插件分发的默认资源，格式为「相对数据目录的路径」。只在目标文件不存在时释放，不覆盖服主改动。 */
    private static final List<String> BUNDLED_RESOURCES = List.of(
            "scripts/example.js",
            "scripts/fireball.js",
            "scripts/lifesteal.js",
            "scripts/vein_miner.js",
            "scripts/auto_smelt.js",
            "scripts/execute.js",
            "scripts/lost_blade.js",
            "scripts/frostbite.js",
            "scripts/disarm.js",
            "scripts/multishot.js",
            "scripts/packet_notice.js",
            "enchants/fireball.yml",
            "enchants/lifesteal.yml",
            "enchants/vein_miner.yml",
            "enchants/auto_smelt.yml",
            "enchants/execute.yml",
            "enchants/lost_blade.yml",
            "enchants/frostbite.yml",
            "enchants/disarm.yml",
            "enchants/multishot.yml",
            "enchants/packet_notice.yml"
    );

    private static final String CONFIG_FILE = "config.yml";
    private static final String MESSAGES_FILE = "messages.yml";

    private final JavaPlugin owner;

    /** 区域化调度契约；本类绝不自己调 Bukkit 调度器（那在 Folia 上会直接不支持）。 */
    private final RegionTasks tasks;

    private final ConfigService config;
    private final MessageService messages;

    /*
     * 装配期写入、运行期从事件/命令线程读取，所以全部 volatile：装配在全局区域线程完成，
     * 事件监听在区域线程跑，没有 volatile 就没有安全的发布语义。
     */

    private volatile ScriptEnginePool enginePool;
    private volatile ScriptRegistry scriptRegistry;
    private volatile ScriptLoader loader;
    private volatile ScriptWatcher watcher;

    private volatile EnchantRegistry enchantRegistry;
    private volatile EnchantWatcher enchantWatcher;

    /**
     * 原版附魔数据包生成器。
     *
     * <p>无生命周期资源（只写文件），构造时就建好：{@code /jse reload} 与首次装配共用同一个实例，
     * 重载后不必重建。
     */
    private final EnchantDatapackWriter datapackWriter;

    /** 读写层在配置重载后整体替换，读取侧（事件线程）只做 volatile 读取。 */
    private volatile EnchantApplier applier;

    /**
     * 事件派发器与悬停预览。两者在装配末尾创建一次，重载不重建：
     * 派发器只持有本服务对象的引用，重载替换的 applier / registry 都是每次现取。
     */
    private volatile TriggerDispatcher dispatcher;
    private volatile HighlightService highlights;
    private volatile HoverWatcher hoverWatcher;

    /** 自定义事件的手动注册桥；重载时按最新定义集合增量调整。 */
    private volatile EventBridge eventBridge;

    /**
     * 启停协调：装配与停用共用同一条资源清单。
     *
     * <p>停用可能落在装配的任意两步之间，靠它保证「装配后新建的资源」也会被就地关闭，
     * 并且防止停用之后还在注册命令与事件。
     */
    private final LifecycleCoordinator lifecycle = new LifecycleCoordinator();

    public JsEnchantPlugin(final JavaPlugin owner) {
        this(owner, new BukkitRegionTasks(owner));
    }

    /**
     * @param owner 真正注册到服务端的插件实例（事件注册、数据目录、日志都取自它）
     * @param tasks 调度契约；传假实现即可在测试里驱动整条初始化/重载链路
     */
    public JsEnchantPlugin(final JavaPlugin owner, final RegionTasks tasks) {
        this.owner = owner;
        this.tasks = tasks;

        final Path dataFolder = owner.getDataFolder().toPath();

        this.config = new ConfigService(owner.getLogger(), dataFolder.resolve(CONFIG_FILE));
        this.messages = new MessageService(owner.getLogger(), dataFolder.resolve(MESSAGES_FILE));
        this.datapackWriter = new EnchantDatapackWriter(owner.getLogger());
    }

    /**
     * 对应旧 {@code JavaPlugin#onEnable}。
     *
     * <p>装配整段排到全局区域线程：提交即返回（不阻塞调用方），并且与 {@link #reloadAsync}
     * 天然串行——两者在同一条全局队列上，不会出现「初始化做到一半被重载插进来」。
     */
    public void enable() {
        this.tasks.global(this::initialize);
    }

    /**
     * 真正的装配步骤，只在全局区域线程执行。
     *
     * <p>装配期间任何一步都可能与停用交错，所以每建一个资源就交给
     * {@link LifecycleCoordinator#adopt(Runnable)} 登记；登记发现「已停用」时，装配立刻收尾，
     * 该资源当场关闭。失败路径与停用路径共用同一份清单，不会出现「关了一半又建一个」的遗留。
     */
    private void initialize() {
        if (!this.lifecycle.begin()) {
            return;
        }

        try {
            this.startServices();
        } catch (final RuntimeException exception) {
            this.getLogger().log(Level.SEVERE, "JsEnchants 启用失败，已清理半初始化资源。", exception);

            this.lifecycle.stop(this.getLogger());
        } finally {
            if (this.lifecycle.stopped()) {
                // 装配期间被停用（或装配失败）：调度器也要停掉，之后不再接受新任务。
                this.tasks.close();
            }
        }
    }

    /**
     * 按旧 {@code onEnable} 的顺序装配；只在全局区域线程调用。
     *
     * <p>每个 {@code adopt} 返回 {@code false} 都意味着「此刻已停用」：就地返回，不再继续
     * 创建资源、更不注册命令与事件。
     */
    private void startServices() {
        this.ensureResource(CONFIG_FILE);
        this.ensureResource(MESSAGES_FILE);

        this.config.load();
        this.messages.load();

        for (final String resource : BUNDLED_RESOURCES) {
            this.ensureResource(resource);
        }

        // 沙箱放行前缀来自 config.yml：脚本能调什么由配置决定，而不是改代码重打包。
        // 硬拒绝清单（反射/IO/Runtime）仍钉死在 ScriptSandbox 里，配置改不动它。
        this.enginePool = new ScriptEnginePool(
                new ScriptSandbox(this.config.current().allowedPackages())
        );

        if (!this.lifecycle.adopt(this.enginePool::close)) {
            return;
        }

        this.scriptRegistry = new ScriptRegistry();
        this.loader = new ScriptLoader(this.enginePool, this.scriptRegistry, this.getLogger(), this.scriptDir());

        final ScriptLoadReport scripts = this.loader.reloadAll();

        this.watcher = new ScriptWatcher(
                this.getLogger(),
                this.loader,
                this.scriptRegistry,
                this.scriptDir(),
                this::runOnMainThread
        );

        if (!this.lifecycle.adopt(this.watcher::close)) {
            return;
        }

        this.watcher.start();

        this.enchantRegistry = new EnchantRegistry(this.getLogger());

        final EnchantLoadReport enchants = this.enchantRegistry.reload(
                this.enchantDir(), this.config.current().blockedEvents()
        );

        this.enchantWatcher = new EnchantWatcher(
                this.getLogger(),
                this.enchantRegistry,
                this.enchantDir(),
                this::runOnMainThread
        );

        if (!this.lifecycle.adopt(this.enchantWatcher::close)) {
            return;
        }

        this.enchantWatcher.start();

        this.applier = this.newApplier();

        if (this.lifecycle.stopped()) {
            // 注册命令与事件是最后一步副作用：停用之后再做，只会留下无法撤掉的残留。
            return;
        }

        // 附魔台维度：把启用附魔写成原版数据包（world/datapacks/jsenchants）。
        // 失败只告警、不阻断启用——数据包没写上最多是「附魔台刷不到」，
        // 触发、Lore 与铁砧都仍按 PDC 正常工作。
        this.writeDatapack();

        this.registerCommand();

        // 附魔书服务与铁砧共用同一个实例；两个监听器在这里一次注册。
        // startServices 只跑一次（重载走 reloadNow，不重建监听器），所以 reload 不会重复注册。
        // 监听器随插件停用由服务端注销，和下面的派发器、悬停预览一致。
        // 调色板随快照传入：附魔书与装备 Lore 必须用同一套按稀有度取色的模板。
        final EnchantedBookService books = new EnchantedBookService(this);

        Bukkit.getPluginManager().registerEvents(new LootBookInjector(this, books), this.owner);
        Bukkit.getPluginManager().registerEvents(new BookAnvilListener(this, books), this.owner);

        this.dispatcher = new TriggerDispatcher(this);
        Bukkit.getPluginManager().registerEvents(this.dispatcher, this.owner);

        // 自定义事件走手动注册：注解式监听器无法接收「任意事件类」。
        // 内置短名的监听器仍是注解式的，两者的事件集合不重叠。
        this.eventBridge = new EventBridge(this.owner, this.dispatcher);
        this.eventBridge.reload(this.enchantRegistry.customEvents());

        if (!this.lifecycle.adopt(this.eventBridge::close)) {
            return;
        }

        // 悬停预览：每个玩家一条周期任务，只在准星目标变化时派发。
        // 展示实体是真实实体，停用必须清掉，所以登记进生命周期清单。
        this.highlights = new HighlightService(this.owner);

        if (!this.lifecycle.adopt(this.highlights::clearAll)) {
            return;
        }

        this.hoverWatcher = new HoverWatcher(this, this.highlights, this.dispatcher);
        Bukkit.getPluginManager().registerEvents(this.hoverWatcher, this.owner);
        this.hoverWatcher.start();

        this.logLoadResult(scripts, enchants);
    }

    /**
     * 对应旧 {@code JavaPlugin#onDisable}。
     *
     * <p>顺序固定：先关自己的监听线程与脚本运行态，再关调度器。反过来做的话，被取消的排队
     * 任务可能还没来得及把监听器引用清干净就被丢掉。
     *
     * <p>本方法可重复调用；若装配还在进行中，协调器会让后续每一步都当场收尾。
     */
    public void disable() {
        if (!this.lifecycle.stop(this.getLogger())) {
            return;
        }

        this.tasks.close();

        this.getLogger().info("JsEnchants 已卸载。");
    }

    /**
     * 全量重载：配置、文案、脚本、附魔定义。
     *
     * <p>整段序列化到全局区域线程执行，完成后把报告交回调用方所在线程——报告原样带回失败
     * 信息，调用方（{@code /jse reload}）据此决定是否可以说「成功」，任何一步失败都必须
     * 让玩家看到失败，而不是统一报成功。
     *
     * @param returnTo 需要在完成后回到其实体线程的接收者（玩家发送者）；{@code null} 表示
     *                 报告就在全局区域线程上交付（控制台、命令方块等）
     * @param then     接收报告的回调；参数为本轮结果
     * @return 是否已排入全局队列。插件已停用/已关闭时返回 {@code false} 且<b>不会</b>调用
     *         回调，调用方需要自己提示「未执行」
     */
    public boolean reloadAsync(final Entity returnTo, final Consumer<ReloadReport> then) {
        if (this.lifecycle.stopped() || !this.tasks.active()) {
            return false;
        }

        this.tasks.global(() -> {
            if (!this.tasks.active()) {
                // 排队期间被停用：不再执行整段重载，也不再回执（服务端正在关，发送者已不可达）。
                return;
            }

            final ReloadReport report = this.reloadNow();

            if (null == returnTo) {
                then.accept(report);

                return;
            }

            // 玩家发送者的回执必须回到它自己的区域线程；实体已退休时自然丢弃。
            this.tasks.entity(returnTo, () -> then.accept(report), null);
        });

        return true;
    }

    /** 重载主体；只在全局区域线程调用。 */
    private ReloadReport reloadNow() {
        final boolean configLoaded = this.config.load();
        final boolean messagesLoaded = this.messages.load();

        if (configLoaded) {
            // 只有配置真的换了才重建读写层，否则没必要让在途的物品操作读到新实例。
            this.applier = this.newApplier();
        }

        final ScriptLoadReport scripts = this.loader.reloadAll();
        final EnchantLoadReport enchants = this.enchantRegistry.reload(
                this.enchantDir(), this.config.current().blockedEvents()
        );

        // 定义可能新增/删除自定义事件：事件桥必须跟着增减监听器，
        // 否则旧事件会继续派发给已经不存在的定义，新事件则彻底不触发。
        final EventBridge bridge = this.eventBridge;

        if (null != bridge) {
            bridge.reload(this.enchantRegistry.customEvents());
        }

        // 数据包内容是定义的投影，重载后必须重写文件，否则附魔台看到的还是上一版定义。
        this.writeDatapack();

        return new ReloadReport(configLoaded, messagesLoaded, scripts, enchants);
    }

    /**
     * 重写原版附魔数据包（附魔台维度）。
     *
     * <p>三个必须守住的边界：
     * <ul>
     *   <li>{@code datapack.enabled=false} 时不动世界目录——服主关掉功能后，已生成的文件
     *       由他自己删，插件不去猜他想不想保留；</li>
     *   <li>没有可用世界（控制台早期、测试替身）时只告警；</li>
     *   <li>任何异常都只告警：数据包是「锦上添花」，写不进去不能变成插件起不来的原因。</li>
     * </ul>
     *
     * <p>注意：这里只更新文件，不改变已加载的注册表——新增/修改附魔要让附魔台看到，
     * 仍然需要 {@code /reload} 或重启，提示由命令侧负责。
     */
    private void writeDatapack() {
        final ConfigService.Datapack datapack = this.config.current().datapack();

        if (!datapack.enabled()) {
            return;
        }

        final Path worldFolder = this.worldFolder();

        if (null == worldFolder) {
            this.getLogger().warning("没有可用世界，已跳过附魔数据包生成（附魔台维度不可用）。");

            return;
        }

        try {
            this.datapackWriter.write(this.enchantRegistry.all().values(), datapack, worldFolder);
        } catch (final RuntimeException exception) {
            this.getLogger().log(
                    Level.WARNING,
                    "生成附魔数据包失败（附魔台维度不可用，触发与 Lore 不受影响）。",
                    exception
            );
        }
    }

    /**
     * 数据包要写进哪个世界：主世界（{@code Bukkit.getWorlds()} 的第一个）。
     *
     * <p>不写死 {@code world} 目录名：{@code level-name} 可以被改，取当前实际加载的世界更稳。
     *
     * @return 世界文件夹；没有世界或取不到（测试替身）时返回 {@code null}
     */
    private Path worldFolder() {
        try {
            final List<World> worlds = Bukkit.getWorlds();

            if (worlds.isEmpty()) {
                return null;
            }

            return worlds.get(0).getWorldFolder().toPath();
        } catch (final RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** 脚本目录：{@code plugins/JsEnchants/scripts} */
    public Path scriptDir() {
        return this.owner.getDataFolder().toPath().resolve("scripts");
    }

    /** 附魔定义目录：{@code plugins/JsEnchants/enchants} */
    public Path enchantDir() {
        return this.owner.getDataFolder().toPath().resolve("enchants");
    }

    /** 真正的 Bukkit 插件实例，事件注册/调度/NamespacedKey 都必须用它。 */
    public JavaPlugin owner() {
        return this.owner;
    }

    /**
     * 区域化调度契约：其他组件要走线程时必须从这里取，不要自己调 Bukkit 调度器。
     *
     * <p>构造时即注入，生产路径永不为 {@code null}。
     */
    public RegionTasks tasks() {
        return this.tasks;
    }

    public Logger getLogger() {
        return this.owner.getLogger();
    }

    public ConfigService config() {
        return this.config;
    }

    public MessageService messages() {
        return this.messages;
    }

    public ScriptLoader getLoader() {
        return this.loader;
    }

    public ScriptEnginePool getEnginePool() {
        return this.enginePool;
    }

    public ScriptRegistry getScriptRegistry() {
        return this.scriptRegistry;
    }

    public EnchantRegistry getEnchantRegistry() {
        return this.enchantRegistry;
    }

    public EnchantApplier getApplier() {
        return this.applier;
    }

    private EnchantApplier newApplier() {
        final ConfigService.Snapshot snapshot = this.config.current();

        return new EnchantApplier(
                this.owner,
                this.enchantRegistry,
                snapshot.loreFormat(),
                snapshot.loreEnabled(),
                snapshot.lorePalette()
        );
    }

    /**
     * 脚本/附魔的热重载必须在全局区域线程执行（顶层 exec 与整表替换都会触碰服务端状态）。
     *
     * <p>这是个 {@code Consumer<Runnable>} 形态的薄壳，只为不改动两个 watcher 的构造签名；
     * 真正的调度一律走 {@link #tasks()}，因为 Folia 上没有「主线程」可以借。
     */
    private void runOnMainThread(final Runnable task) {
        this.tasks.global(task);
    }

    private void registerCommand() {
        final PluginCommand command = this.owner.getCommand("jse");

        if (null == command) {
            this.getLogger().warning("plugin.yml 未声明 /jse 命令，命令功能不可用。");

            return;
        }

        final JsEnchantCommand executor = new JsEnchantCommand(this);

        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * 启动日志必须区分「附魔表已发布」与「整批被拒、沿用上一版（首次启动则是空表）」：
     * 附魔重载是整批原子发布，未发布时 {@code loaded()} 是旧快照数量，
     * 直接把数字印成「附魔 N 个」会让「一个定义都没加载成功」看起来像一切正常。
     */
    private void logLoadResult(final ScriptLoadReport scripts, final EnchantLoadReport enchants) {
        if (enchants.published()) {
            // 脚本数量取注册表里的生效总数：reloadAll() 的 loaded() 只是本轮成功加载数。
            this.getLogger().info(
                    "JsEnchants 已启用：脚本 " + this.scriptRegistry.size() + " 个，附魔 " + enchants.loaded() + " 个。"
            );
        } else {
            this.getLogger().severe(
                    "JsEnchants 已启用，但附魔定义整批未发布："
                            + (0 < enchants.loaded() ? "仍在用上一版 " + enchants.loaded() + " 个" : "当前没有任何可用定义")
                            + "。"
            );
        }

        this.logLoadFailures("脚本", scripts.directoryReady(), scripts.failed(), scripts.failures());

        if (!enchants.directoryReady()) {
            this.getLogger().severe("附魔目录不可用，已保留上一份定义（" + enchants.loaded() + " 个）。");
        } else if (!enchants.published() && !enchants.failures().isEmpty()) {
            this.getLogger().severe(
                    "附魔定义失败明细（" + enchants.failed() + " 个）: " + String.join("; ", enchants.failures())
            );
        }
    }

    private void logLoadFailures(
            final String kind,
            final boolean directoryReady,
            final int failed,
            final List<String> failures
    ) {
        if (!directoryReady) {
            this.getLogger().severe(kind + "目录不可用，已保留上一版内容。");
        }

        if (0 < failed) {
            this.getLogger().severe(kind + "加载失败 " + failed + " 个，已保留上一版: " + String.join("; ", failures));
        }
    }

    /** 释放随插件分发的默认文件；已存在时不覆盖服主改动。 */
    private void ensureResource(final String resourcePath) {
        final Path target = this.owner.getDataFolder().toPath().resolve(resourcePath);

        if (Files.exists(target)) {
            return;
        }

        try (final InputStream stream = this.owner.getResource(resourcePath)) {
            if (null == stream) {
                this.getLogger().warning("插件内缺少默认文件: " + resourcePath);

                return;
            }

            Files.createDirectories(target.getParent());
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException exception) {
            this.getLogger().log(Level.WARNING, "释放默认文件失败: " + resourcePath, exception);
        }
    }

    /**
     * 重载结果。
     *
     * @param configLoaded    {@code config.yml} 是否成功替换
     * @param messagesLoaded  {@code messages.yml} 是否成功替换
     * @param scripts         脚本层报告
     * @param enchants        附魔层报告
     */
    public record ReloadReport(
            boolean configLoaded,
            boolean messagesLoaded,
            ScriptLoadReport scripts,
            EnchantLoadReport enchants
    ) {

        /**
         * 全部步骤都成功时才算真的重载成功。
         *
         * <p>附魔侧是整批原子发布：{@code published()==false} 表示这批定义被整体拒绝、
         * 旧快照仍在生效，这种情况一律不算成功，哪怕失败明细恰好为空。
         */
        public boolean fullSuccess() {
            return this.configLoaded
                    && this.messagesLoaded
                    && this.scripts.directoryReady()
                    && this.enchants.published()
                    && 0 == this.scripts.failed()
                    && 0 == this.enchants.failed();
        }
    }
}
