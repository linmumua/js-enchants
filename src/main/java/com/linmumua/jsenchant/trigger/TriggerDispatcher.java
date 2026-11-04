package com.linmumua.jsenchant.trigger;

import com.linmumua.jsenchant.JsEnchantPlugin;
import com.linmumua.jsenchant.api.ScriptApi;
import com.linmumua.jsenchant.api.ScriptHost;
import com.linmumua.jsenchant.enchant.EnchantDefinition;
import com.linmumua.jsenchant.enchant.EnchantRegistry;
import com.linmumua.jsenchant.platform.RegionTasks;
import com.linmumua.jsenchant.script.CompiledScript;
import com.linmumua.jsenchant.script.CallbackBudget;
import com.linmumua.jsenchant.script.ScriptRuntimeHost;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 把 Bukkit 事件派发到脚本。
 *
 * <p>性能策略：所有事件先做「玩家手持物是否有本框架附魔」这一 O(1) 检查，
 * 未命中直接 return，不进 JS。高频事件（受击）上这一层是主要开销控制点。
 *
 * <p><b>取消语义</b>：可取消的事件挂在 {@link EventPriority#HIGHEST} 并带
 * {@code ignoreCancelled = true}，因此别的插件在更早的优先级上取消事件时本框架不会生效。
 * 这<b>不承诺</b>处理「在 MONITOR 上取消事件」这种用法——那时本监听器已经跑完，
 * 效果无法撤回；MONITOR 只应该被用来观察最终结果。
 * {@link EntityDeathEvent} 本身不可取消，所以击杀触发挂在 MONITOR 上读最终状态。
 *
 * <p><b>副手过滤</b>：触发只看主手。右键与射箭事件会为双手各发一次，
 * 不过滤就是同一附魔触发两次；近战与挖掘本身就只走主手。
 *
 * <p>概率与冷却由 {@link TriggerPolicy} 统一判定：未命中或冷却未就绪的触发在这里就被拦下，
 * 脚本完全不会被执行，也不产生任何记账；判定与记账是同一次原子 {@code claim}。
 *
 * <h2>线程语义（Folia）</h2>
 *
 * <p>事件在哪个区域线程触发，就只属于那个线程。因此每次派发先问一次
 * {@link TriggerRouting#decide(boolean, TriggerType)}：
 * <ul>
 *   <li><b>同区</b>：当前线程拥有触发者 → 同步执行，脚本拿到的是<b>可改的活事件</b>；</li>
 *   <li><b>跨区</b>（攻击/被攻击/击杀）：排队到<b>触发者自己的实体调度器</b>，
 *       背包与脚本参数都在那边重新读。活事件<b>不会</b>进排队任务——它只能在原线程上用——
 *       排队前也<b>不读</b>外区的背包或位置。跨区脚本拿到的 {@link ScriptApi}
 *       明确是「事件不可写」：{@code event()} 会报错，而不是返回 null 或假装成功；</li>
 *   <li><b>无法重放</b>（挖掘/右键/射箭跨区）：方块与投射物只在原线程有效，
 *       直接放弃并限流告警，不越界改世界。</li>
 * </ul>
 *
 * <p>跨区不承诺「异步改回原事件」：原事件的结算早在另一条线程上继续走了。
 */
public final class TriggerDispatcher implements Listener {

    /**
     * 业务服务本身不是插件实例（owner 由平台装载器持有），
     * 这里只通过它取日志器与几个运行期组件；{@code applier} 在重载后会被替换，
     * 所以每次派发都现取，不能缓存。
     */
    private final JsEnchantPlugin services;

    private final TriggerPolicy policy = new TriggerPolicy();

    /** 脚本延迟/周期回调的存活名额，跨触发共享：上限按「玩家 × 附魔」计。 */
    private final CallbackBudget callbacks = new CallbackBudget();

    public TriggerDispatcher(final JsEnchantPlugin services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        this.deliver(player, TriggerType.ATTACK, victim, null, null, event);

        if (victim instanceof Player defender) {
            this.deliver(defender, TriggerType.DEFEND, player, null, null, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMine(final BlockBreakEvent event) {
        // 挖掘只走主手，副手不参与。
        this.deliver(event.getPlayer(), TriggerType.MINE, null, event.getBlock(), null, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (Action.RIGHT_CLICK_BLOCK != event.getAction() && Action.RIGHT_CLICK_AIR != event.getAction()) {
            return;
        }

        // 副手过滤：右键对双手各发一次事件，不过滤会双份触发。
        if (EquipmentSlot.OFF_HAND == event.getHand()) {
            return;
        }

        this.deliver(event.getPlayer(), TriggerType.RIGHT_CLICK, null, event.getClickedBlock(), null, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();

        if (null == killer) {
            return;
        }

        // 死亡事件在死者的区域线程上触发，凶手通常不在同一区域：这一条几乎总是走跨区排队。
        this.deliver(killer, TriggerType.KILL, event.getEntity(), null, null, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(final EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // 副手过滤：只认主手那张弓/弩。
        if (EquipmentSlot.OFF_HAND == event.getHand()) {
            return;
        }

        // 服务端给的是 Entity，实际应为投射物；不是的话按「没有投射物」处理，不猜。
        final Projectile projectile = event.getProjectile() instanceof Projectile typed ? typed : null;

        this.deliver(player, TriggerType.SHOOT, null, null, projectile, event);
    }

    /**
     * 按线程归属决定这次触发怎么投递。
     *
     * <p>注意这里的顺序：先判归属、再决定读不读背包。归属不是自己线程的时候，
     * <b>背包与位置一个都不碰</b>（那正是跨区最危险的读）；事件本身是本线程的，
     * 只从中取一个冻结的伤害值，绝不把它带进排队任务。
     *
     * <p>触发物品一律从主手现读：这是跨区路径唯一能在正确线程上拿到的读法，
     * 同区路径也用它，避免两条路径对「哪个手算触发物品」有两套定义。
     */
    private void deliver(
            final Player actor,
            final TriggerType trigger,
            final LivingEntity target,
            final Block block,
            final Projectile projectile,
            final Event event
    ) {
        switch (TriggerRouting.decide(this.owns(actor), trigger)) {
            case SYNC -> this.fire(
                    new Firing(actor, trigger, target, block, projectile, event, true, 0.0D)
            );
            case QUEUED -> this.queue(new Firing(
                    actor, trigger, target, null, null, null, false, damageOf(event)
            ));
            case UNSUPPORTED -> this.warnUnsupported(trigger);
        }
    }

    /**
     * 跨区：把这一次触发交回触发者自己的实体调度器。
     *
     * <p>进队列的只有<b>不可变信息</b>（触发类型、冻结的伤害值）与两个调度句柄
     * （触发者、目标），没有活事件，也没有任何已读出的外区状态；背包要等真的跑在
     * 触发者线程上时才读。
     */
    private void queue(final Firing firing) {
        final RegionTasks tasks = this.services.tasks();

        final Runnable task = () -> this.fire(firing);

        if (null == tasks) {
            // 没有调度服务 = 单线程语义，就地执行（无服务端的单测会走到这里）。
            task.run();

            return;
        }

        // 返回 false 表示实体已退休 / 调度被拒：玩家已经不在，没有需要补偿的状态。
        tasks.entity(firing.actor(), task, null);
    }

    /**
     * 对触发者身上所有可能带附魔的槽位依次执行。
     *
     * <p>槽位集合来自 {@code EnchantRegistry#usedSlots()}：全部附魔都用主手时它只含
     * {@code HAND}，热路径与引入槽位前完全一样；只有真的声明了护甲槽，才会多读几格。
     *
     * <p>调用方保证：这里跑在触发者自己的区域线程上，所以背包读取是安全的。
     */
    private void fire(final Firing firing) {
        final Player actor = firing.actor();
        final EnchantRegistry registry = this.services.getEnchantRegistry();

        for (final EquipmentSlot slot : registry.usedSlots()) {
            final ItemStack item;

            try {
                item = actor.getInventory().getItem(slot);
            } catch (final RuntimeException exception) {
                // 某些槽位对特定实体无意义（鞍/躯体），取不到就当没有。
                continue;
            }

            if (null == item || item.getType().isAir()) {
                continue;
            }

            // 开销控制点：没有本框架附魔的物品直接跳过，不进 JS。
            final Map<String, Integer> levels = this.services.getApplier().getLevels(item);

            if (levels.isEmpty()) {
                continue;
            }

            for (final Map.Entry<String, Integer> entry : levels.entrySet()) {
                final EnchantDefinition definition = registry.get(entry.getKey());

                if (null == definition
                        || !definition.trigger().isBuiltin()
                        || definition.trigger().builtin() != firing.trigger()) {
                    continue;
                }

                // 定义没声明这个槽位就不该由这件装备触发：
                // 手上那把剑的附魔不能因为写在胸甲上也生效。
                if (!definition.slots().contains(slot)) {
                    continue;
                }

                this.runOne(firing, definition, entry.getValue(), slot);
            }
        }
    }

    private void runOne(
            final Firing firing,
            final EnchantDefinition definition,
            final int level,
            final EquipmentSlot slot
    ) {
        final Player actor = firing.actor();

        // 一次原子判定：概率、冷却、预占都在里面。未授予时连脚本都不取。
        final TriggerPolicy.Claim claim = this.policy.claim(actor.getUniqueId(), definition, level);

        if (!claim.granted()) {
            return;
        }

        final CompiledScript script = this.services.getScriptRegistry()
                .get(stripExtension(definition.script()));

        if (null == script) {
            // 预占必须回滚：脚本缺失不是「执行成功」，不能把玩家钉进冷却。
            claim.release();

            if (this.policy.shouldWarnMissingScript(definition.id())) {
                this.services.getLogger().warning("附魔 " + definition.id() + " 引用的脚本不存在: " + definition.script());
            }

            return;
        }

        // 只有真正执行成功才 commit：缺 handler、抛异常都回滚，玩家不该莫名进冷却。
        if (this.invoke(script, "onTrigger",
                this.apiFor(firing, definition, level, slot).withHost(this.hostFor(script, definition)))) {
            claim.commit();
        } else {
            claim.release();
        }
    }

    /**
     * 本次派发的插件运行环境（持久化键 + 延迟回调）。
     *
     * <p>绑定到具体脚本：延迟回调要在执行线程自己的作用域里重查函数，所以这里必须带上
     * {@link CompiledScript}，而不是只看附魔 id。
     */
    private ScriptHost hostFor(final CompiledScript script, final EnchantDefinition definition) {
        // 传脚本 id 与注册表查找，而不是 script 本身：到期的回调要执行热重载后的新版本。
        return new ScriptRuntimeHost(
                this.services.owner(),
                definition.id(),
                script.id(),
                this.services.getScriptRegistry()::get,
                this.services.getEnginePool(),
                this.services.tasks(),
                this.callbacks,
                this.services.getLogger()
        );
    }

    /**
     * 自定义事件的派发入口，由 {@link EventBridge} 调用。
     *
     * <p>与内置短名的区别只有三点：
     * <ul>
     *   <li>匹配方式是「定义的 trigger 绑定了这个事件类（或其父类）」；</li>
     *   <li>角色靠 {@link EventActorResolver} 猜——猜不到玩家就跳过，不猜；</li>
     *   <li>跨区一律放弃并限流告警：自定义事件没有「可以安全重放的实体句柄」这层保证，
     *       框架不为它们做排队重放。</li>
     * </ul>
     */
    public void fireGeneric(final Event event) {
        if (null == event) {
            return;
        }

        final EventActorResolver.Actors actors = EventActorResolver.resolve(event);
        final String name = event.getClass().getSimpleName();

        if (!actors.hasPlayer()) {
            if (this.policy.shouldWarn("no-actor:" + name)) {
                this.services.getLogger().warning(
                        "事件 " + name + " 上找不到触发玩家，已跳过。"
                                + "如果这个事件确实该由玩家触发，请改用内置短名或全限定名重试。"
                );
            }

            return;
        }

        final Player player = actors.player();

        // 自定义事件不做跨区重放：没有实体句柄可用，硬来就是越界读。
        if (!this.owns(player)) {
            if (this.policy.shouldWarn("cross-region-custom:" + name)) {
                this.services.getLogger().warning(
                        "自定义事件 " + name + " 发生在不属于触发者自己的区域线程上，已跳过本次派发。"
                );
            }

            return;
        }

        final EnchantRegistry registry = this.services.getEnchantRegistry();

        for (final EquipmentSlot slot : registry.usedSlots()) {
            final ItemStack item;

            try {
                item = player.getInventory().getItem(slot);
            } catch (final RuntimeException exception) {
                continue;
            }

            if (null == item || item.getType().isAir()) {
                continue;
            }

            final Map<String, Integer> levels = this.services.getApplier().getLevels(item);

            if (levels.isEmpty()) {
                continue;
            }

            for (final Map.Entry<String, Integer> entry : levels.entrySet()) {
                final EnchantDefinition definition = registry.get(entry.getKey());

                if (null == definition || definition.trigger().isBuiltin()) {
                    continue;
                }

                if (!definition.trigger().eventClass().isAssignableFrom(event.getClass())) {
                    continue;
                }

                if (!definition.slots().contains(slot)) {
                    continue;
                }

                this.runOneGeneric(actors, definition, entry.getValue(), slot, event);
            }
        }
    }

    private void runOneGeneric(
            final EventActorResolver.Actors actors,
            final EnchantDefinition definition,
            final int level,
            final EquipmentSlot slot,
            final Event event
    ) {
        final Player player = actors.player();
        final TriggerPolicy.Claim claim = this.policy.claim(player.getUniqueId(), definition, level);

        if (!claim.granted()) {
            return;
        }

        final CompiledScript script = this.services.getScriptRegistry()
                .get(stripExtension(definition.script()));

        if (null == script) {
            claim.release();

            if (this.policy.shouldWarnMissingScript(definition.id())) {
                this.services.getLogger().warning("附魔 " + definition.id() + " 引用的脚本不存在: " + definition.script());
            }

            return;
        }

        final ScriptApi api = ScriptApi.generic(
                        player,
                        actors.target(),
                        actors.block(),
                        actors.projectile(),
                        level,
                        definition.id()
                )
                .withScheduler(this.services.tasks())
                .withSlot(slot)
                .withOptions(definition.options())
                .withEvent(event)
                .withHost(this.hostFor(script, definition));

        if (this.invoke(script, "onTrigger", api)) {
            claim.commit();
        } else {
            claim.release();
        }
    }

    /**
     * 悬停派发：玩家准星指向了一个方块，问每个脚本「要不要预览」。
     *
     * <p>与事件派发的三点不同，都是刻意的：
     * <ul>
     *   <li><b>不看 trigger</b>：悬停不是一种效果时机，而是「观察」时机。挖掘类附魔的
     *       trigger 是 MINE，但预告连锁范围的入口也该是它，所以这里遍历主手上所有附魔，
     *       由脚本自己决定有没有 onHover 可做；</li>
     *   <li><b>不掷概率、不吃冷却</b>：全程不经过 {@link TriggerPolicy}。对着矿脉看两秒
     *       就把挖掘的冷却用光，等于把预览做成了惩罚；</li>
     *   <li><b>不提交任何账</b>：脚本缺 onHover 就是静默跳过，不告警——大多数附魔本来
     *       就没有预览，逐次告警只会把日志刷满。</li>
     * </ul>
     *
     * <p>调用方必须已经在玩家自己的区域线程上（{@code HoverWatcher} 通过实体周期任务保证）。
     *
     * @param sink 脚本登记的方块收集到这里；调用方读完负责生成/清除展示实体
     */
    public void fireHover(final Player player, final Block target, final Consumer<Block> sink) {
        if (null == player || null == target || null == sink) {
            return;
        }

        final ItemStack item = player.getInventory().getItemInMainHand();

        if (null == item || item.getType().isAir()) {
            return;
        }

        final Map<String, Integer> levels = this.services.getApplier().getLevels(item);

        if (levels.isEmpty()) {
            return;
        }

        for (final Map.Entry<String, Integer> entry : levels.entrySet()) {
            final EnchantDefinition definition = this.services.getEnchantRegistry().get(entry.getKey());

            if (null == definition || !definition.enabled()) {
                continue;
            }

            final CompiledScript script = this.services.getScriptRegistry()
                    .get(stripExtension(definition.script()));

            if (null == script) {
                continue;
            }

            final ScriptApi api = ScriptApi.hover(player, target, entry.getValue(), definition.id())
                    .withScheduler(this.services.tasks())
                    .withOptions(definition.options())
                    .withHighlighter(sink)
                    .withHost(this.hostFor(script, definition));

            this.invoke(script, "onHover", api);
        }
    }

    /**
     * 组装这次触发交给脚本的上下文。
     *
     * <p>同区带活事件（{@link ScriptApi#withEvent(Event)}），跨区只带冻结数值
     * （{@link ScriptApi#crossRegion(double)}）——后者明确「事件不可写」，
     * 脚本若试图拿事件会得到异常而不是一个安静的空值。
     */
    private ScriptApi apiFor(
            final Firing firing,
            final EnchantDefinition definition,
            final int level,
            final EquipmentSlot slot
    ) {
        // 走到这里说明冷却就绪且已命中，所以如实传 true（rollChance/cooldownReady 供旧脚本兼容）。
        final ScriptApi base = switch (definition.trigger().builtin()) {
            case ATTACK, DEFEND -> ScriptApi.attack(firing.actor(), firing.target(), level, definition.id(), true, true);
            case MINE -> ScriptApi.mine(firing.actor(), firing.block(), level, definition.id(), true, true);
            case RIGHT_CLICK -> ScriptApi.interact(firing.actor(), firing.block(), level, definition.id(), true, true);
            case KILL -> ScriptApi.kill(firing.actor(), firing.target(), level, definition.id(), true, true);
            case SHOOT -> ScriptApi.shoot(firing.actor(), firing.projectile(), level, definition.id(), true, true);
        };

        final ScriptApi scheduled = base.withScheduler(this.services.tasks())
                .withSlot(slot)
                .withOptions(definition.options());

        return firing.writable() && null != firing.event()
                ? scheduled.withEvent(firing.event())
                : scheduled.crossRegion(firing.frozenDamage());
    }

    /**
     * 在受控上下文里调用脚本的 onTrigger。
     *
     * <p>脚本池只负责「按当前线程取运行态并调用」；异常、缺 handler 的处置在派发层决定，
     * 因为要不要回滚冷却只有这里知道。
     *
     * @return 脚本是否成功执行（缺 handler 或异常都视为未执行，避免污染冷却）
     */
    private boolean invoke(final CompiledScript script, final String function, final ScriptApi api) {
        try {
            return this.services.getEnginePool().invoke(script, function, api);
        } catch (final RuntimeException exception) {
            this.services.getLogger().log(Level.WARNING, "脚本执行出错: " + script.id(), exception);

            return false;
        }
    }

    /**
     * 当前线程是否拥有触发者。
     *
     * <p>没有调度服务时按单线程语义视为「拥有」：否则无服务端的单测会去调真实 Bukkit。
     */
    private boolean owns(final Player actor) {
        final RegionTasks tasks = this.services.tasks();

        return null == tasks || tasks.owns(actor);
    }

    private void warnUnsupported(final TriggerType trigger) {
        if (!this.policy.shouldWarn("cross-region:" + trigger)) {
            return;
        }

        this.services.getLogger().warning(
                "触发 " + trigger + " 发生在不属于触发者自己的区域线程上："
                        + "方块/投射物只在原线程有效，无法安全重放，已跳过本次触发。"
        );
    }

    private static double damageOf(final Event event) {
        return event instanceof EntityDamageEvent damageEvent ? damageEvent.getDamage() : 0.0D;
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');

        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * 一次触发的投递载荷。
     *
     * <p>{@code writable=true} 时 {@code event} 必然是当前线程的活事件；
     * 跨区时 {@code event} 为 {@code null}、{@code frozenDamage} 是排队前冻结的数值。
     *
     * @param target  目标实体；跨区时只作为调度句柄，不提前读它的任何状态
     * @param block   方块；跨区投递一律为 {@code null}（方块触发不支持跨区重放）
     * @param projectile 投射物；同上
     */
    private record Firing(
            Player actor,
            TriggerType trigger,
            LivingEntity target,
            Block block,
            Projectile projectile,
            Event event,
            boolean writable,
            double frozenDamage
    ) {
    }
}
