package com.linmumua.jsenchant.api;

import com.linmumua.jsenchant.platform.RegionTasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 注入给脚本的宿主对象。
 *
 * <p>这是脚本与服务器之间唯一的通道：脚本拿不到 {@code java.*}（ClassShutter 已拦截类可见性），
 * 只能用这里暴露的方法。所以本类的每个 public 方法都等同于一个安全边界，
 * 新增方法时必须假设它会被恶意脚本调用。
 *
 * <p>注意这<b>不是绝对沙箱</b>：真正的信任边界是脚本目录的写权限，
 * 不要对外宣称脚本已被完全隔离。
 *
 * <p>本类不持有插件状态，只包装一次触发所需的上下文；一次触发一个实例，
 * 且只在本次脚本调用内使用，不跨线程复用。
 *
 * <h2>Folia 下的硬约束（脚本作者必读）</h2>
 *
 * <p>服务端可能把不同实体放在不同区域线程上，而<b>实体/方块/世界状态只能在自己的区域线程里碰</b>。
 * 因此：
 * <ul>
 *   <li>{@link #player()}、{@link #victim()}、{@link #entity()}、{@link #projectile()}、
 *       {@link #block()} 交出的是<b>原生 Bukkit 引用</b>。取之前框架会问一次归属，
 *       不属于当前线程时<b>直接抛 {@link IllegalStateException}</b>——不返回 null、
 *       不返回一个「看起来能用」的代理、也不悄悄降级。<b>绝不静默忽略</b>：静默忽略会让脚本
 *       以为自己改了世界，实际什么都没发生。</li>
 *   <li>跨区触发（例如击杀发生在死者的区域线程、攻击者的区域线程稍后才轮到）时，
 *       本对象代表的是<b>已经过去的那一次触发</b>：没有可写事件
 *       （{@link #event()} 会明确报错，{@link #eventWritable()} 为 {@code false}），
 *       也不能借原生引用去改原事件。框架<b>不会</b>声称能异步跨区修改原事件。</li>
 *   <li>要在别的实体身上产生效果，请用会自动路由到目标区域线程的受控 API：
 *       {@link #explodeAtTarget(double)}、{@link #playSound(String)}、{@link #message(String)}、
 *       {@link #giveExp(int)}。它们内部会把动作投递到目标自己的调度器上执行。</li>
 *   <li>第三方脚本若把原生实体引用存到脚本全局变量里、留到下一次触发再用，
 *       框架<b>无法</b>自动让它变安全；这属于脚本自己的线程责任，越界使用会在归属检查处报错。</li>
 * </ul>
 *
 * <p><b>概率语义</b>：一次触发只掷一次，且由框架强制。0 从不、1 必然、其余按抽签决定；
 * 未命中时脚本<b>根本不会被调用</b>，因此派发路径上的 {@link #rollChance()} 恒为
 * {@code true}——保留它只是为了让旧脚本里的 {@code if (!ctx.rollChance()) return;}
 * 继续可用，而不是让脚本自己决定概率。
 *
 * <p><b>冷却语义</b>：冷却未就绪时框架不会调用脚本，因此脚本里看到的
 * {@link #cooldownReady()} 恒为 {@code true}；它只作为旧脚本的兼容入口保留。
 */
public final class ScriptApi {

    private final Player player;
    private final LivingEntity victim;
    private final Block block;
    private final LivingEntity entity;
    private final Projectile projectile;
    private final int level;
    private final String enchantId;
    private final boolean chanceHit;
    private final boolean cooldownReady;
    private final RegionTasks tasks;
    private final Event event;
    private final boolean eventWritable;
    private final double damage;

    /**
     * 准星指向的方块（仅 {@link #hover} 路径有值）。
     *
     * <p>悬停检测由插件在玩家自己的区域线程上发起，因此它天然属于当前线程；
     * 仍然通过 {@link #targetBlock()} 的归属检查再确认一次，避免调用方误用。
     */
    private final Block target;

    /**
     * 高亮收集器：脚本每 {@link #highlight(Block)} 一次就登记一个方块，
     * 派发结束后由插件统一生成只对该玩家可见的展示实体。
     *
     * <p>只拿方块本身、不当场生成实体，是因为脚本可能在一次调用里收集几十个方块：
     * 边收集边生成会让没有最终被采纳的高亮实体短暂出现，也很难在异常路径上清理干净。
     */
    private final Consumer<Block> highlighter;

    /** 触发物品所在的装备槽；非槽位触发（悬停预览）时为主手。 */
    private final EquipmentSlot slot;

    /**
     * 该附魔定义里的自定义选项（{@code yml} 的 {@code options:} 段），只读。
     *
     * <p>框架不解释这些内容，脚本才是唯一的读者；这里是「把配置送到脚本手边」的通道。
     * 值域被 {@code EnchantParser} 限制成 字符串/数字/布尔 与其字符串列表，
     * 所以 {@link #option(String)} 不需要在脚本侧再判类型。
     */
    private final Map<String, Object> options;

    /**
     * 插件运行环境（持久化键、延迟回调）。
     *
     * <p>可以为 {@code null}：单元测试与旧构造路径没有插件环境，
     * 依赖它的方法会明确降级（返回 null / false）而不是抛错。
     */
    private final ScriptHost host;

    private ScriptApi(
            final Player player,
            final LivingEntity victim,
            final Block block,
            final LivingEntity entity,
            final Projectile projectile,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady,
            final RegionTasks tasks,
            final Event event,
            final boolean eventWritable,
            final double damage,
            final Block target,
            final Consumer<Block> highlighter,
            final EquipmentSlot slot,
            final Map<String, Object> options,
            final ScriptHost host
    ) {
        this.player = player;
        this.victim = victim;
        this.block = block;
        this.entity = entity;
        this.projectile = projectile;
        this.level = level;
        this.enchantId = enchantId;
        this.chanceHit = chanceHit;
        this.cooldownReady = cooldownReady;
        this.tasks = tasks;
        this.event = event;
        this.eventWritable = eventWritable;
        this.damage = damage;
        this.target = target;
        this.highlighter = highlighter;
        this.slot = null == slot ? EquipmentSlot.HAND : slot;
        this.options = null == options ? Map.of() : options;
        this.host = host;
    }

    /**
     * 攻击触发：攻击者 + 被攻击者。
     *
     * @param chanceHit 框架本次触发已经掷出的命中结果
     */
    public static ScriptApi attack(
            final Player player,
            final LivingEntity victim,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady
    ) {
        return base(player, victim, null, victim, null, level, enchantId, chanceHit, cooldownReady);
    }

    /**
     * 旧签名：直接传概率。
     *
     * <p>概率在这里就固化成结果（只抽一次），之后脚本怎么调 {@link #rollChance()} 都是同一个值。
     * 框架自身的派发路径不会走这个重载——它已经在策略层抽过了——保留它是为了兼容既有调用方。
     */
    public static ScriptApi attack(
            final Player player,
            final LivingEntity victim,
            final int level,
            final String enchantId,
            final double chance,
            final boolean cooldownReady
    ) {
        return attack(player, victim, level, enchantId, rollOnce(chance), cooldownReady);
    }

    /** 挖掘触发。 */
    public static ScriptApi mine(
            final Player player,
            final Block block,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady
    ) {
        return base(player, null, block, null, null, level, enchantId, chanceHit, cooldownReady);
    }

    /** 旧签名：直接传概率，语义同 {@link #attack(Player, LivingEntity, int, String, double, boolean)}。 */
    public static ScriptApi mine(
            final Player player,
            final Block block,
            final int level,
            final String enchantId,
            final double chance,
            final boolean cooldownReady
    ) {
        return mine(player, block, level, enchantId, rollOnce(chance), cooldownReady);
    }

    /** 右键触发。 */
    public static ScriptApi interact(
            final Player player,
            final Block block,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady
    ) {
        return base(player, null, block, null, null, level, enchantId, chanceHit, cooldownReady);
    }

    /** 旧签名：直接传概率，语义同 {@link #attack(Player, LivingEntity, int, String, double, boolean)}。 */
    public static ScriptApi interact(
            final Player player,
            final Block block,
            final int level,
            final String enchantId,
            final double chance,
            final boolean cooldownReady
    ) {
        return interact(player, block, level, enchantId, rollOnce(chance), cooldownReady);
    }

    /** 击杀触发。 */
    public static ScriptApi kill(
            final Player player,
            final LivingEntity victim,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady
    ) {
        return base(player, victim, null, victim, null, level, enchantId, chanceHit, cooldownReady);
    }

    /** 旧签名：直接传概率，语义同 {@link #attack(Player, LivingEntity, int, String, double, boolean)}。 */
    public static ScriptApi kill(
            final Player player,
            final LivingEntity victim,
            final int level,
            final String enchantId,
            final double chance,
            final boolean cooldownReady
    ) {
        return kill(player, victim, level, enchantId, rollOnce(chance), cooldownReady);
    }

    /**
     * 射箭触发。
     *
     * <p>射手仍然由 {@link #player()} 表示，{@link #victim()} 保持 {@code null}：
     * 投射物是新增的独立字段，不会挤占原来的「射手 / 目标」语义。
     *
     * @param projectile 出膛的投射物；服务端给的实体不是投射物时为 {@code null}
     */
    public static ScriptApi shoot(
            final Player player,
            final Projectile projectile,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady
    ) {
        return base(player, null, null, null, projectile, level, enchantId, chanceHit, cooldownReady);
    }

    /** 旧签名：直接传概率，语义同 {@link #attack(Player, LivingEntity, int, String, double, boolean)}。 */
    public static ScriptApi shoot(
            final Player player,
            final Projectile projectile,
            final int level,
            final String enchantId,
            final double chance,
            final boolean cooldownReady
    ) {
        return shoot(player, projectile, level, enchantId, rollOnce(chance), cooldownReady);
    }

    /**
     * 按概率掷一次：0 从不、1 必然，介于两者之间才消耗一次随机数。
     *
     * <p>框架与旧签名重载共用这条规则，避免两处各写一套概率语义。
     */
    private static boolean rollOnce(final double chance) {
        if (0.0D >= chance) {
            return false;
        }

        if (1.0D <= chance) {
            return true;
        }

        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    /**
     * 旧工厂的公共底座：没有调度服务、没有事件。
     *
     * <p>「没有调度服务」等价于单线程语义：效果就地执行、原生 getter 不做归属检查。
     * 这条路径专供旧调用方与无服务端的单测使用，生产派发一律通过
     * {@link #withScheduler(RegionTasks)} 注入调度服务。
     */
    private static ScriptApi base(
            final Player player,
            final LivingEntity victim,
            final Block block,
            final LivingEntity entity,
            final Projectile projectile,
            final int level,
            final String enchantId,
            final boolean chanceHit,
            final boolean cooldownReady
    ) {
        return new ScriptApi(player, victim, block, entity, projectile, level, enchantId,
                chanceHit, cooldownReady, null, null, false, 0.0D, null, null, EquipmentSlot.HAND, Map.of(), null);
    }

    // --- 派发侧装配 ---

    /**
     * 注入区域调度服务（生产路径）。
     *
     * <p>{@code null} 表示退回单线程语义，仅供旧调用方与单测；生产传
     * {@code JsEnchantPlugin#tasks()}。
     */
    public ScriptApi withScheduler(final RegionTasks scheduler) {
        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                scheduler, this.event, this.eventWritable, this.damage, this.target, this.highlighter, this.slot,
                this.options, this.host);
    }

    /**
     * 绑定本次触发的事件（同区同步路径）。
     *
     * <p>只有「事件确实发生在当前区域线程」时才允许调用：事件对象本身可改，
     * 但只能被它自己的区域线程改。跨区排队时<b>不要</b>用这个，改用
     * {@link #crossRegion(double)}，否则就是把活事件交给了别的线程。
     */
    public ScriptApi withEvent(final Event event) {
        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                this.tasks, event, null != event, damageOf(event), this.target, this.highlighter, this.slot,
                this.options, this.host);
    }

    /**
     * 跨区变体：没有可写事件，只带一份冻结的事件数值。
     *
     * <p>排队任务里绝不能出现活的 Bukkit 事件——它只能在触发它的那个区域线程上用。
     * 这里冻结的只有数字，脚本仍可通过受控 API 在目标区域线程产生效果，
     * 但<b>不能</b>回头改原事件（{@link #event()} 会明确报错）。
     *
     * @param frozenDamage 触发瞬间的伤害值；不适用时为 0
     */
    public ScriptApi crossRegion(final double frozenDamage) {
        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                this.tasks, null, false, frozenDamage, this.target, this.highlighter, this.slot, this.options,
                this.host);
    }

    /**
     * 悬停路径的上下文：玩家准星指向了一个方块。
     *
     * <p>没有事件、没有受害者，也不需要概率与冷却——悬停是「观察」不是「生效」，
     * 如果这里消费冷却，玩家对着矿脉看两秒就把挖掘的连锁次数用光了。
     *
     * @param target 准星指向的方块；由插件在玩家自己的区域线程上读出
     */
    public static ScriptApi hover(
            final Player player,
            final Block target,
            final int level,
            final String enchantId
    ) {
        return new ScriptApi(player, null, null, null, null, level, enchantId,
                true, true, null, null, false, 0.0D, target, null, EquipmentSlot.HAND, Map.of(), null);
    }

    /**
     * 通用路径（自定义事件）的上下文。
     *
     * <p>角色是派发层用 {@link #victim()} / {@link #block()} / {@link #projectile()} 的语义
     * 统一填进来的：能确定目标就填目标，能确定方块就填方块，都没有就留空。
     * 脚本用之前仍要判空——不是每个自定义事件都带齐这三样。
     *
     * <p>与内置路径一样，拿到的都是原生引用；跨区访问会在 getter 处直接报错，
     * 而不是悄悄返回一个不可用的对象。
     */
    public static ScriptApi generic(
            final Player player,
            final LivingEntity target,
            final Block block,
            final Projectile projectile,
            final int level,
            final String enchantId
    ) {
        return new ScriptApi(player, target, block, target, projectile, level, enchantId,
                true, true, null, null, false, 0.0D, null, null, EquipmentSlot.HAND, Map.of(), null);
    }

    /** 挂上插件运行环境（持久化键与延迟回调）；派发层在派发前注入一次。 */
    public ScriptApi withHost(final ScriptHost host) {
        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                this.tasks, this.event, this.eventWritable, this.damage, this.target, this.highlighter, this.slot,
                this.options, host);
    }

    /** 挂上高亮收集器；派发层用它把脚本登记的方块换成只对该玩家可见的展示实体。 */
    public ScriptApi withHighlighter(final Consumer<Block> sink) {        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                this.tasks, this.event, this.eventWritable, this.damage, this.target, sink, this.slot,
                this.options, this.host);
    }

    /** 标注触发物品所在的装备槽；派发层按定义声明的槽位逐个派发时使用。 */
    public ScriptApi withSlot(final EquipmentSlot slot) {
        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                this.tasks, this.event, this.eventWritable, this.damage, this.target, this.highlighter, slot,
                this.options, this.host);
    }

    /**
     * 挂上该附魔定义里的自定义选项（{@code yml} 的 {@code options:} 段）。
     *
     * <p>值由 {@code EnchantParser} 保证是标量或字符串列表，本类不再解释它们的含义——
     * 只有脚本知道 {@code blocks} 该怎么用。
     *
     * @param options 定义里的选项；{@code null} 等价于空映射
     */
    public ScriptApi withOptions(final Map<String, Object> options) {
        return new ScriptApi(this.player, this.victim, this.block, this.entity, this.projectile,
                this.level, this.enchantId, this.chanceHit, this.cooldownReady,
                this.tasks, this.event, this.eventWritable, this.damage, this.target, this.highlighter,
                this.slot, options, this.host);
    }

    private static double damageOf(final Event event) {
        return event instanceof EntityDamageEvent damageEvent ? damageEvent.getDamage() : 0.0D;
    }

    // --- 给脚本用的只读信息 ---

    public int level() {
        return this.level;
    }

    /**
     * 读取本附魔定义里的一个自定义选项（{@code yml} 的 {@code options:} 段）。
     *
     * <p>返回值只有三种形态：
     * <ul>
     *   <li>yml 里写的是标量 → {@link String} / {@link Number} / {@link Boolean} 原样交出；</li>
     *   <li>yml 里写的是列表 → 只读字符串列表，脚本里可 {@code list.length} / {@code list[i]}；</li>
     *   <li>没写这个键 → {@code null}。</li>
     * </ul>
     *
     * <p>要「一定有值」的场景用 {@link #optionList(String)}，省掉脚本里判空。
     *
     * @param key 选项键，{@code null} 或不存在时返回 {@code null}
     */
    public Object option(final String key) {
        final Object value = this.rawOption(key);

        return value instanceof List<?> ? OptionValues.of(value) : value;
    }

    /**
     * 读取一个选项并规整成字符串列表：不存在时是空列表，单个标量当成只有一项的列表。
     *
     * <p>这是「白名单」这类选项最省事的读法——脚本不必区分「没配」「配了一个」「配了一串」。
     *
     * <p>返回的容器是 {@link OptionValues} 而不是 {@code List.of(...)}/数组，原因见那个类的注释：
     * 这不是随意选的实现类，换了脚本就读不出来。
     *
     * @param key 选项键，{@code null} 或不存在时返回空列表（不是 {@code null}）
     */
    public List<String> optionList(final String key) {
        return OptionValues.of(this.rawOption(key));
    }

    private Object rawOption(final String key) {
        return null == key ? null : this.options.get(key);
    }

    /**
     * 交给脚本的只读字符串列表。
     *
     * <p>刻意让容器是<b>本插件包下的具名类</b>，而不是 {@code String[]} 或 {@code List.of(...)}：
     * <ul>
     *   <li>Java 数组会被 Rhino 包成 {@code NativeJavaArray}，它要给类 {@code [Ljava.lang.String;}
     *       建成员表，而脚本沙箱的 {@code ClassShutter} 拒绝这个类名——脚本一读就抛
     *       "禁止访问 Java 类"，白名单会静默失效；</li>
     *   <li>{@code java.util.List} 的返回类型虽然放行，但 Rhino 建成员表时用的是
     *       <b>运行时具体类</b>（{@code ImmutableCollections$ListN} 这类 JDK 内部类），
     *       照样被拒绝；</li>
     *   <li>{@code com.linmumua.jsenchant.} 前缀本来就在放行名单里，于是 Rhino 走
     *       {@code NativeJavaList}，脚本里 {@code list.length} 与 {@code list[i]} 都可用。</li>
     * </ul>
     * 证据见 {@code ScriptApiOptionRhinoTest}。改动这里之前请先看那条用例。
     */
    /**
     * 交给脚本的实体列表。
     *
     * <p>与 {@link OptionValues} 同一个理由：容器必须是本插件包下的具名类，
     * 否则 Rhino 建成员表时会去访问 {@code java.util.ImmutableCollections$ListN}
     * 这类 JDK 内部类，被脚本沙箱拒绝，脚本一读就抛错。
     */
    private static final class EntityValues extends AbstractList<Entity> {

        private final List<Entity> values;

        private EntityValues(final List<Entity> values) {
            this.values = values;
        }

        @Override
        public Entity get(final int index) {
            return this.values.get(index);
        }

        @Override
        public int size() {
            return this.values.size();
        }
    }

    /** 交给脚本的方块列表，容器理由同 {@link EntityValues}。 */
    private static final class BlockValues extends AbstractList<Block> {

        private final List<Block> values;

        private BlockValues(final List<Block> values) {
            this.values = values;
        }

        @Override
        public Block get(final int index) {
            return this.values.get(index);
        }

        @Override
        public int size() {
            return this.values.size();
        }
    }

    private static final class OptionValues extends AbstractList<String> {

        /** 空列表共用一个实例：{@code optionList} 在缺省路径上会被频繁调用。 */
        private static final OptionValues EMPTY = new OptionValues(List.of());

        private final List<String> values;

        private OptionValues(final List<String> values) {
            this.values = values;
        }

        /**
         * 把选项值规整成列表：列表逐个转字符串，标量当成一项，缺省为空。
         *
         * <p>复制一份而不是直接返回定义里的 {@link List}：定义会被反复派发，
         * 脚本侧无论怎么用都不该碰到它。
         */
        private static OptionValues of(final Object value) {
            if (value instanceof List<?> list) {
                final List<String> strings = list.stream().map(String::valueOf).toList();

                return strings.isEmpty() ? EMPTY : new OptionValues(strings);
            }

            return null == value ? EMPTY : new OptionValues(List.of(String.valueOf(value)));
        }

        @Override
        public String get(final int index) {
            return this.values.get(index);
        }

        @Override
        public int size() {
            return this.values.size();
        }
    }

    public String enchantId() {
        return this.enchantId;
    }

    /**
     * 触发物品所在的装备槽，形如 {@code HAND}、{@code OFF_HAND}、{@code CHEST}、{@code FEET}。
     *
     * <p>一个附魔可以声明多个槽位（例如闪避只挂护腿、动能缓冲只挂鞘翅），
     * 脚本据此分支即可，不必给每种装备写一个附魔。
     */
    public String slot() {
        return this.slot.name();
    }

    /**
     * 触发者（射手/攻击者/挖掘者）。
     *
     * @throws IllegalStateException 该实体不属于当前区域线程
     */
    public Player player() {
        return this.own(this.player, "触发者");
    }

    /**
     * 被攻击/被击杀的目标，没有则返回 null。
     *
     * @throws IllegalStateException 目标存在但不属于当前区域线程——此时请改用
     *         {@link #explodeAtTarget(double)} 这类会自动路由的受控 API
     */
    public LivingEntity victim() {
        return this.own(this.victim, "目标");
    }

    /**
     * 被挖掘/被右键的方块，没有则返回 null。
     *
     * @throws IllegalStateException 方块不属于当前区域线程
     */
    public Block block() {
        if (null == this.block || null == this.tasks) {
            return this.block;
        }

        final Location center = this.block.getLocation();

        if (null != center && this.tasks.owns(center, 0)) {
            return this.block;
        }

        throw new IllegalStateException(this.crossRegionMessage("方块"));
    }

    /**
     * 准星当前指向的方块（仅悬停路径有值），没有则返回 {@code null}。
     *
     * <p>这是「挖前预览」的起点：脚本据此算出会被连锁的方块，再用
     * {@link #highlight(Block)} 把它们登记成高亮。它不代表这次真的会挖掉，
     * 只是一次观察。
     *
     * @throws IllegalStateException 该方块不属于当前区域线程
     */
    public Block targetBlock() {
        if (null == this.target || null == this.tasks) {
            return this.target;
        }

        if (this.tasks.owns(this.target.getLocation(), 0)) {
            return this.target;
        }

        throw new IllegalStateException(this.crossRegionMessage("准星方块"));
    }

    /**
     * 登记一个要高亮的方块，只有触发玩家自己能看到。
     *
     * <p>只是登记，不是立刻发实体：派发层会在脚本返回后统一生成、统一替换上一批，
     * 这样脚本中途抛错也不会留下半套高亮。重复登记同一格不会叠加。
     *
     * <p>不在本线程的方块直接报错而不是跳过——静默跳过会让脚本以为自己标好了范围，
     * 玩家却看不到，比报错更难排查。
     *
     * @param block 要高亮的方块，可以为 {@code null}（忽略）
     * @throws IllegalStateException 该方块不属于当前区域线程
     */
    public void highlight(final Block block) {
        if (null == block || null == this.highlighter) {
            return;
        }

        if (null != this.tasks && !this.tasks.owns(block.getLocation(), 0)) {
            throw new IllegalStateException(this.crossRegionMessage("高亮方块"));
        }

        this.highlighter.accept(block);
    }

    /**
     * 相关实体，没有则返回 null。
     *
     * @throws IllegalStateException 该实体不属于当前区域线程
     */
    public LivingEntity entity() {
        return this.own(this.entity, "相关实体");
    }

    /**
     * 射出的投射物（仅射箭触发有值），没有则返回 null。
     *
     * @throws IllegalStateException 投射物不属于当前区域线程
     */
    public Projectile projectile() {
        return this.own(this.projectile, "射弹");
    }

    public World world() {
        return null == this.player ? null : this.player.getWorld();
    }

    public Location location() {
        return null == this.player ? null : this.player.getLocation();
    }

    /**
     * 本次触发是否命中概率。
     *
     * <p>命中与否由框架判定：未命中时脚本不会被调用，所以派发路径上这里恒为 {@code true}；
     * 只有走旧 {@code double chance} 工厂的调用方才可能拿到 {@code false}。
     * 同一次触发内多次调用返回同一个值——不会重复赌博。
     */
    public boolean rollChance() {
        return this.chanceHit;
    }

    /**
     * 冷却是否已就绪。
     *
     * <p>冷却未就绪时框架不会调用脚本，所以正常路径下这里恒为 {@code true}；
     * 保留它只为兼容仍在读这个值的脚本。
     */
    public boolean cooldownReady() {
        return this.cooldownReady;
    }

    /** 这次触发是否带了一个<b>当前线程可以改</b>的事件。 */
    public boolean eventWritable() {
        return this.eventWritable;
    }

    /**
     * 本次事件的简名，例如 {@code PlayerToggleSneakEvent}；没有事件时返回 {@code null}。
     *
     * <p>用途是让一个脚本服务多个自定义事件：同一个 {@code onTrigger} 里按名字分支即可，
     * 不必为每个事件复制一份脚本。
     *
     * <p>跨区排队触发时事件不可写，这里同样返回 {@code null}——不要用它判断「是哪次触发」，
     * 那种场景应该用 {@link #enchantId()}。
     */
    public String eventName() {
        return null == this.event ? null : this.event.getClass().getSimpleName();
    }

    /**
     * 本次触发对应的事件，可读也可改（同区同步触发）。
     *
     * @throws IllegalStateException 本次触发没有可写事件——跨区排队、或旧工厂路径。
     *         这不是「返回 null 让你猜」，而是明确告诉你：原事件已经不在本线程手上了。
     */
    public Event event() {
        if (null == this.event || !this.eventWritable) {
            throw new IllegalStateException(
                    "本次触发没有可写事件（附魔 " + this.enchantId + "）：跨区触发的事件只属于"
                            + "它自己的区域线程，框架不会把它交给排队任务；同区触发才会带上事件。"
                            + "请不要依赖 event() 做跨区修改，改用 explodeAtTarget(...) 等受控 API。"
            );
        }

        return this.event;
    }

    /** 触发瞬间的伤害值（攻击/击杀有意义），不适用时为 0。跨区时是冻结的快照。 */
    public double damage() {
        return this.damage;
    }

    // --- 给脚本用的行为 ---

    /**
     * 在目标身上产生一次受控爆炸。
     *
     * <p>这是替代「脚本自己拿 {@code victim.getWorld().createExplosion(...)}」的入口：
     * 位置在<b>目标自己的区域线程</b>上读取，爆炸范围若跨出该线程拥有的区块则保守拒绝并限流告警，
     * 威力也会被钳到上限。脚本拿不到世界对象，也就没有绕过检查的路径。
     *
     * @param power 期望威力；会被钳到 {@link ControlledExplosion#MAX_POWER}
     * @return 是否已受理（已同步执行或已交给目标实体的调度器）
     */
    public boolean explodeAtTarget(final double power) {
        return ControlledExplosion.request(this.tasks, this.victim, power, this.enchantId);
    }

    /**
     * 播放音效。
     *
     * <p>解析（{@code Sound.valueOf} → 注册表回落）与播放<b>都发生在玩家自己的区域线程上</b>：
     * 音效常量在现代服务端是注册表条目，解析本身就是在读服务端状态，放到别的线程上就是越界读。
     * 名字非法时按「没有这个音效」处理，不抛错。
     */
    public void playSound(final String soundName) {
        if (null == this.player || null == soundName) {
            return;
        }

        this.onEntity(this.player, () -> {
            final Sound sound = this.resolveSound(soundName);

            if (null == sound) {
                return;
            }

            this.player.playSound(this.player.getLocation(), sound, 1.0F, 1.0F);
        });
    }

    public void message(final String text) {
        if (null != this.player && null != text) {
            this.onEntity(this.player, () -> this.player.sendMessage(text));
        }
    }

    /** 给玩家经验。 */
    public void giveExp(final int amount) {
        if (null != this.player && 0 < amount) {
            this.onEntity(this.player, () -> this.player.giveExp(amount));
        }
    }

    /**
     * 以控制台身份执行一条命令。
     *
     * <p>命令影响的是整个服务端状态，不归属任何区域，因此固定投递到全局区域线程；
     * 不要指望它在当前线程同步完成。
     */
    public void runCommand(final String command) {
        if (null == command || command.isBlank()) {
            return;
        }

        final Runnable task = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

        if (null == this.tasks) {
            task.run();

            return;
        }

        this.tasks.global(task);
    }

    // --- 延迟回调 ---

    /**
     * 过 {@code ticks} 之后执行脚本里的一个全局函数。
     *
     * <p>写的是函数名而不是匿名函数，例如：
     * <pre>{@code
     * ctx.delay(40, "explode");            // 2 秒后调用 function explode() {}
     * ctx.delay(20, "wither", 3, "SLOW");  // 1 秒后调用 function wither(3, "SLOW") {}
     * }</pre>
     *
     * <p>原因见 {@link ScriptHost#later}：Rhino 的函数对象属于创建它的线程，而延迟任务
     * 可能在另一个区域线程上跑（玩家走过了区域边界）。传名字才能在执行线程自己的作用域里
     * 重新查一次，否则就是跨线程使用可变对象。
     *
     * <p>参数只应传数字/字符串/布尔这类不可变值：实体与方块的引用带不过去，
     * 也不该带过去（那是旧的区域状态）。
     *
     * @return 是否已受理；没有插件环境或没有可用的锚点实体时为 {@code false}
     */
    public boolean delay(final int ticks, final String function, final Object... args) {
        final Entity anchor = this.anchor();

        if (null == this.host || null == function || null == anchor) {
            return false;
        }

        return this.host.later(anchor, ticks, function, args);
    }

    /**
     * 每隔 {@code periodTicks} 执行一次脚本里的全局函数。
     *
     * @param times 执行次数；{@code <= 0} 表示一直重复，直到实体退休或插件停用
     * @see #delay(int, String, Object...)
     */
    public boolean repeat(final int periodTicks, final int times, final String function, final Object... args) {
        final Entity anchor = this.anchor();

        if (null == this.host || null == function || null == anchor) {
            return false;
        }

        return this.host.repeat(anchor, periodTicks, periodTicks, times, function, args);
    }

    /** 延迟任务挂在谁身上：优先触发者，其次受击者。都没有就不排任务。 */
    private Entity anchor() {
        return null != this.player ? this.player : this.victim;
    }

    // --- 状态效果 ---

    /**
     * 给目标加一层药水效果。
     *
     * <p>为什么不交给脚本 {@code new PotionEffect(...)}：构造器对时长与等级的取值区间有硬校验，
     * 越界会抛 {@link IllegalArgumentException}，而脚本里一个拼错的名字会让整次触发失败。
     * 这里把名字解析与区间钳制都收在 Java 侧，非法名字按「没有这个效果」返回 {@code false}。
     *
     * @param effectName 效果名，如 {@code SLOWNESS}（大小写不敏感）
     * @param ticks      持续 tick，小于 1 时按 1
     * @param amplifier  等级，0 为 I 级；会被钳到 0..255
     * @return 是否已受理（已执行或已交给目标实体的调度器）
     */
    public boolean potion(final Entity target, final String effectName, final int ticks, final int amplifier) {
        if (null == target || null == effectName || !(target instanceof LivingEntity living)) {
            return false;
        }

        final PotionEffectType type = resolveEffect(effectName);

        if (null == type) {
            return false;
        }

        final int duration = Math.max(1, ticks);
        final int level = Math.max(0, Math.min(amplifier, 255));

        this.onEntity(target, () -> living.addPotionEffect(new PotionEffect(type, duration, level)));

        return true;
    }

    /**
     * 点燃目标；{@code ticks <= 0} 时熄灭。
     *
     * <p>这是「燃烧」唯一安全的入口：火焰 tick 是实体状态，只能在目标自己的区域线程上写。
     */
    public boolean ignite(final Entity target, final int ticks) {
        if (null == target) {
            return false;
        }

        this.onEntity(target, () -> target.setFireTicks(Math.max(0, ticks)));

        return true;
    }

    /**
     * 从触发者指向目标把人推开。
     *
     * <p>水平方向归一化后乘以 {@code power}，再补一个固定抬升，避免只把人往后蹭地板。
     * 没有触发者（自定义事件猜不到玩家）时沿目标自身朝向的反方向推。
     */
    public boolean knockback(final Entity target, final double power) {
        if (null == target || power <= 0.0D) {
            return false;
        }

        final double strength = Math.min(power, 8.0D);

        this.onEntity(target, () -> {
            Vector direction;

            if (null != this.player && this.player != target) {
                direction = target.getLocation().toVector().subtract(this.player.getLocation().toVector());
            } else {
                direction = target.getLocation().getDirection().multiply(-1.0D);
            }

            direction.setY(0.0D);

            if (direction.lengthSquared() < 1.0E-4D) {
                direction = new Vector(0.0D, 0.0D, 1.0D);
            }

            target.setVelocity(direction.normalize().multiply(strength).setY(0.35D));
        });

        return true;
    }

    // --- 周围查询 ---

    /**
     * 触发者附近的实体（不含触发者自己）。
     *
     * <p>这是<b>同步</b>查询也是同步返回：读世界实体列表必须在区域线程上做，
     * 当前线程不拥有触发者时返回空列表而不是排队——调用方要的是「这一瞬间周围有谁」，
     * 稍后再填一份列表对它没有意义。
     *
     * <p>返回的是本插件包下的具名列表（同 {@link #optionList(String)} 的理由：
     * JDK 内部集合类会被脚本沙箱拒绝，脚本一读就抛错）。
     *
     * @param radius 半径，会被钳到 0.5..16
     */
    public List<Entity> nearby(final double radius) {
        final double box = Math.max(0.5D, Math.min(radius, 16.0D));

        return this.atPlayer(List.of(), () -> {
            final List<Entity> found = new ArrayList<>();

            for (final Entity candidate : this.player.getWorld()
                    .getNearbyEntities(this.player.getLocation(), box, box, box)) {
                if (candidate != this.player) {
                    found.add(candidate);
                }
            }

            return new EntityValues(found);
        });
    }

    /**
     * 触发者附近的方块（以触发者所在方块为中心的正方体）。
     *
     * <p>同样同步返回。半径按方块数算，钳到 1..6：再大就不是「周围」而是「扫描半径」，
     * 每 tick 触发一次的附魔会把区域线程拖死。
     */
    public List<Block> blocksAround(final int radius) {
        final int box = Math.max(1, Math.min(radius, 6));

        return this.atPlayer(List.of(), () -> {
            final List<Block> found = new ArrayList<>();
            final Location center = this.player.getLocation();
            final World world = center.getWorld();

            if (null == world) {
                return new BlockValues(found);
            }

            for (int dx = -box; dx <= box; dx++) {
                for (int dy = -box; dy <= box; dy++) {
                    for (int dz = -box; dz <= box; dz++) {
                        found.add(world.getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz));
                    }
                }
            }

            return new BlockValues(found);
        });
    }

    // --- 持久状态 ---

    /**
     * 读本附魔存在触发者身上的字符串状态。
     *
     * <p>键自动带上插件命名空间与附魔 id，两个附魔写同名键不会互相覆盖；
     * 状态落在玩家 PDC 上，重启与热重载后仍在。
     *
     * @return 没有记录、或拿不到插件环境时返回 {@code null}
     */
    public String state(final String key) {
        final NamespacedKey namespaced = this.stateKeyOf(key);

        if (null == namespaced) {
            return null;
        }

        return this.atPlayer(null, () -> this.player.getPersistentDataContainer()
                .get(namespaced, PersistentDataType.STRING));
    }

    /** 写字符串状态；{@code value} 为 {@code null} 时删除该键。 */
    public boolean setState(final String key, final String value) {
        final NamespacedKey namespaced = this.stateKeyOf(key);

        if (null == namespaced) {
            return false;
        }

        return this.atPlayer(Boolean.FALSE, () -> {
            if (null == value) {
                this.player.getPersistentDataContainer().remove(namespaced);
            } else {
                this.player.getPersistentDataContainer().set(namespaced, PersistentDataType.STRING, value);
            }

            return Boolean.TRUE;
        });
    }

    /**
     * 整数状态自增并返回新值（没有记录时从 0 开始）。
     *
     * <p>「叠到 5 层就爆发」「今天第几次触发」这类效果靠它，不必再自己读-改-写。
     * 拿不到玩家时返回 0。
     */
    public int addState(final String key, final int delta) {
        final NamespacedKey namespaced = this.stateKeyOf(key);

        if (null == namespaced) {
            return 0;
        }

        return this.atPlayer(0, () -> {
            final Integer current = this.player.getPersistentDataContainer()
                    .get(namespaced, PersistentDataType.INTEGER);
            final int next = (null == current ? 0 : current) + delta;

            this.player.getPersistentDataContainer().set(namespaced, PersistentDataType.INTEGER, next);

            return next;
        });
    }

    // --- 物品 ---

    /** 给触发者物品；超出背包的会掉在脚下。 */
    public boolean give(final String material, final int amount) {
        final Material type = resolveMaterial(material);

        if (null == type || amount <= 0) {
            return false;
        }

        return this.atPlayer(Boolean.FALSE, () -> {
            int left = amount;

            while (left > 0) {
                final int size = Math.min(left, type.getMaxStackSize());
                final Map<Integer, ItemStack> overflow =
                        this.player.getInventory().addItem(new ItemStack(type, size));

                for (final ItemStack rest : overflow.values()) {
                    this.player.getWorld().dropItemNaturally(this.player.getLocation(), rest);
                }

                left -= size;
            }

            return Boolean.TRUE;
        });
    }

    /** 从触发者背包扣物品；不够时<b>一件都不扣</b>并返回 {@code false}。 */
    public boolean take(final String material, final int amount) {
        final Material type = resolveMaterial(material);

        if (null == type || amount <= 0) {
            return false;
        }

        return this.atPlayer(Boolean.FALSE, () -> {
            int have = 0;

            for (final ItemStack stack : this.player.getInventory().getContents()) {
                if (null != stack && type == stack.getType()) {
                    have += stack.getAmount();
                }
            }

            if (have < amount) {
                return Boolean.FALSE;
            }

            this.player.getInventory().removeItem(new ItemStack(type, amount));

            return Boolean.TRUE;
        });
    }

    /** 在触发者脚下掉落物品（不先进背包）。 */
    public boolean drop(final String material, final int amount) {
        final Material type = resolveMaterial(material);

        if (null == type || amount <= 0) {
            return false;
        }

        return this.atPlayer(Boolean.FALSE, () -> {
            int left = amount;

            while (left > 0) {
                final int size = Math.min(left, type.getMaxStackSize());
                this.player.getWorld().dropItemNaturally(this.player.getLocation(), new ItemStack(type, size));
                left -= size;
            }

            return Boolean.TRUE;
        });
    }

    // --- 显示通道 ---

    /** 在触发者的动作栏上显示一行字。 */
    public void actionBar(final String text) {
        if (null != this.player && null != text) {
            this.onEntity(this.player, () -> this.player.sendActionBar(text));
        }
    }

    /** 在触发者的屏幕上显示标题与副标题。 */
    public void title(final String main, final String subtitle) {
        if (null != this.player) {
            this.onEntity(this.player, () -> this.player.sendTitle(
                    null == main ? "" : main,
                    null == subtitle ? "" : subtitle
            ));
        }
    }

    // --- 配置取值 ---

    /**
     * 读一个数字选项。
     *
     * <p>脚本侧不必再 {@code parseFloat("" + ctx.option(...))}：类型转换与非法值回落都在这里。
     */
    public double optionNumber(final String key, final double fallback) {
        final Object raw = this.rawOption(key);

        if (null == raw) {
            return fallback;
        }

        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (final NumberFormatException exception) {
            return fallback;
        }
    }

    /** 读一个布尔选项；非 {@code true}/{@code false} 的内容按 {@code fallback} 处理。 */
    public boolean optionFlag(final String key, final boolean fallback) {
        final Object raw = this.rawOption(key);

        if (null == raw) {
            return fallback;
        }

        final String text = String.valueOf(raw).trim();

        if ("true".equalsIgnoreCase(text)) {
            return true;
        }

        if ("false".equalsIgnoreCase(text)) {
            return false;
        }

        return fallback;
    }

    /** 读一个字符串选项；缺省时返回 {@code fallback}。 */
    public String optionText(final String key, final String fallback) {
        final Object raw = this.rawOption(key);

        return null == raw ? fallback : String.valueOf(raw);
    }

    /**
     * 按附魔等级从选项列表里取第 N 项的数字。
     *
     * <p>{@code byLevel("damage", 5)} 配 {@code damage: [5, 10, 20]}：I 级取 5、II 级取 10、
     * III 级取 20，等级超过列表长度时取最后一项。「每级不同数值」这类配置因此不用写 if。
     */
    public double byLevel(final String key, final double fallback) {
        final List<String> values = this.optionList(key);

        if (values.isEmpty()) {
            return fallback;
        }

        final int index = Math.min(Math.max(this.level, 1), values.size()) - 1;

        try {
            return Double.parseDouble(values.get(index).trim());
        } catch (final NumberFormatException exception) {
            return fallback;
        }
    }

    // --- 方块写入 ---

    /**
     * 改触发者附近的方块。
     *
     * <p>坐标是相对触发者所在方块的偏移。写入前会确认当前线程拥有那个位置：
     * 不拥有时直接拒绝（不排队、不静默忽略）——方块写入与实体动作不同，
     * 延迟到别的线程执行会让脚本以为自己改成功了。
     */
    public boolean setBlock(final int dx, final int dy, final int dz, final String material) {
        final Material type = resolveMaterial(material);

        if (null == type) {
            return false;
        }

        return this.atPlayer(Boolean.FALSE, () -> {
            final Block targetBlock = this.relativeBlock(dx, dy, dz);

            if (null == targetBlock || !this.ownsBlock(targetBlock)) {
                return Boolean.FALSE;
            }

            targetBlock.setType(type, true);

            return Boolean.TRUE;
        });
    }

    /** 挖掉触发者附近的方块（掉落按空手算，不给经验）。 */
    public boolean breakBlock(final int dx, final int dy, final int dz) {
        return this.atPlayer(Boolean.FALSE, () -> {
            final Block targetBlock = this.relativeBlock(dx, dy, dz);

            if (null == targetBlock || !this.ownsBlock(targetBlock)) {
                return Boolean.FALSE;
            }

            targetBlock.breakNaturally();

            return Boolean.TRUE;
        });
    }

    // --- 派发 ---

    /**
     * 在触发者身上同步执行一次读取。
     *
     * <p>用于「必须有返回值」的动作（查周围、读状态、改背包）：这类动作不能异步排队，
     * 因为调用方拿到的返回值会用在一行后面的判断里。当前线程不拥有触发者时直接返回
     * {@code fallback}——那是一次真实的越界读，宁可不做。
     */
    private <T> T atPlayer(final T fallback, final java.util.function.Supplier<T> action) {
        if (null == this.player) {
            return fallback;
        }

        if (null != this.tasks && !this.tasks.owns(this.player)) {
            return fallback;
        }

        return action.get();
    }

    private NamespacedKey stateKeyOf(final String key) {
        return null == this.host ? null : this.host.stateKey(key);
    }

    private Block relativeBlock(final int dx, final int dy, final int dz) {
        final Location base = this.player.getLocation();
        final World world = base.getWorld();

        if (null == world) {
            return null;
        }

        return world.getBlockAt(base.getBlockX() + dx, base.getBlockY() + dy, base.getBlockZ() + dz);
    }

    private boolean ownsBlock(final Block block) {
        if (null == this.tasks) {
            return true;
        }

        return this.tasks.owns(block.getLocation(), 0);
    }

    private static Material resolveMaterial(final String raw) {
        if (null == raw || raw.isBlank()) {
            return null;
        }

        return Material.matchMaterial(raw.trim());
    }

    private static PotionEffectType resolveEffect(final String raw) {
        final String normalized = raw.trim().toLowerCase(Locale.ROOT);

        try {
            final NamespacedKey key = NamespacedKey.fromString(
                    normalized.indexOf(':') >= 0 ? normalized : "minecraft:" + normalized
            );

            return null == key ? null : org.bukkit.Registry.EFFECT.get(key);
        } catch (final RuntimeException exception) {
            return null;
        }
    }

    // --- 线程归属 ---

    /**
     * 交出原生实体引用前先问归属。
     *
     * <p>没有调度服务时（旧签名、单测）不做检查：那套语义是「单线程」，检查只会去碰真实 Bukkit。
     */
    private <T extends Entity> T own(final T target, final String role) {
        if (null == target || null == this.tasks) {
            return target;
        }

        // 只需归属判断，不读该实体的位置/背包——那正是跨区最危险的读。
        if (this.tasks.owns(target)) {
            return target;
        }

        throw new IllegalStateException(this.crossRegionMessage(role));
    }

    /**
     * 把动作投递到目标实体自己的区域线程。
     *
     * <p>已经在该线程上时就地执行，避免每次触发都多排一个任务；实体已退休/调度被拒时
     * 放弃即可——玩家已经不在了，没有需要补偿的状态。
     */
    private void onEntity(final Entity target, final Runnable action) {
        if (null == this.tasks || this.tasks.owns(target)) {
            action.run();

            return;
        }

        this.tasks.entity(target, action, null);
    }

    private String crossRegionMessage(final String role) {
        return "跨区访问被拒绝：" + role + " 不属于当前区域线程（附魔 " + this.enchantId + "）。"
                + "Folia 下原生实体/方块引用只能在它自己的区域线程使用；"
                + "请在别的实体身上产生效果时改用受控 API（explodeAtTarget(...) 等），"
                + "不要把这些引用存进脚本全局变量留到别的线程再用。";
    }

    private Sound resolveSound(final String raw) {
        final String normalized = raw.trim().toUpperCase(Locale.ROOT);

        try {
            return Sound.valueOf(normalized);
        } catch (final IllegalArgumentException exception) {
            // 26.x 的 Sound 也可能是 Registry 化的，退化为按 key 查
            final org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                    normalized.toLowerCase(Locale.ROOT).replace('_', '.')
            );

            return null == key ? null : org.bukkit.Registry.SOUND_EVENT.get(key);
        }
    }
}
