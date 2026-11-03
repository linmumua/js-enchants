package com.linmumua.jsenchant.command;

import com.linmumua.jsenchant.JsEnchantPlugin;
import com.linmumua.jsenchant.config.MessageService;
import com.linmumua.jsenchant.enchant.EnchantDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /jse} 命令组：reload / list / give。
 *
 * <p>为什么仍用 Paper 的 {@link CommandExecutor} 而不是 TabooLib 命令 DSL：{@code /jse}
 * 已在 {@code plugin.yml} 里声明（含 usage 与权限节点），继续用 Bukkit 执行器只是把
 * executor 挂上去，不存在重复注册同一个 label 的风险，也不需要为了三个子命令引入
 * Kotlin DSL 与另一套参数解析；语义与旧版逐字一致。凡是有玩家可见输出的地方，
 * 文本一律取自 {@code messages.yml}，代码里不写死中文。
 */
public final class JsEnchantCommand implements CommandExecutor, TabCompleter {

    /** 与 {@code plugin.yml} 的 permissions 声明保持一致，属于对外接口，不可改名。 */
    private static final String PERMISSION = "jsenchants.admin";

    private static final List<String> SUB_COMMANDS = List.of("reload", "list", "give");

    private final JsEnchantPlugin plugin;

    public JsEnchantCommand(final JsEnchantPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull final CommandSender sender,
            @NotNull final Command command,
            @NotNull final String label,
            final String[] args
    ) {
        final MessageService messages = this.plugin.messages();

        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(messages.component("command.no-permission"));

            return true;
        }

        if (0 == args.length) {
            sender.sendMessage(messages.component("command.usage", "label", label));

            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> this.reload(sender);
            case "list" -> this.list(sender);
            case "give" -> this.give(sender, args, label);
            default -> sender.sendMessage(messages.component("command.unknown-subcommand", "input", args[0]));
        }

        return true;
    }

    /**
     * 全量重载，并把每一步的失败如实说出来。
     *
     * <p>重载整段排在全局区域线程上跑，这里只负责「提交 + 提示提交失败」，结果由
     * {@link #reportReload} 在发送者所在线程发出。
     */
    private void reload(final CommandSender sender) {
        final Entity returnTo = sender instanceof Entity entity ? entity : null;
        final boolean queued = this.plugin.reloadAsync(returnTo, report -> this.reportReload(sender, report));

        if (!queued) {
            // 插件正在停用：不排队也不回调，必须明说，否则玩家会以为命令没反应。
            this.reply(sender, this.plugin.messages().component("command.reload.unavailable"));
        }
    }

    /**
     * 报告重载结果；运行在发送者所在线程（玩家实体线程或全局线程）。
     *
     * <p>注意文案取自重载之后的服务：如果这次刚换了 {@code messages.yml}，玩家看到的
     * 就是新文案；换失败时服务保留旧表，提示仍能正常发出。
     */
    private void reportReload(final CommandSender sender, final JsEnchantPlugin.ReloadReport report) {
        final MessageService messages = this.plugin.messages();

        if (!report.configLoaded()) {
            sender.sendMessage(messages.component("command.reload.config-failed"));
        }

        if (!report.messagesLoaded()) {
            sender.sendMessage(messages.component("command.reload.messages-failed"));
        }

        if (!report.scripts().directoryReady()) {
            sender.sendMessage(messages.component("command.reload.script-directory-missing"));
        }

        if (!report.enchants().directoryReady()) {
            sender.sendMessage(messages.component("command.reload.enchant-directory-missing"));
        } else if (!report.enchants().published()) {
            // 附魔重载是整批原子发布：被拒时新旧表泾渭分明，必须说清是「沿用上一版」
            // 还是「一个可用定义都没有」，不能只报一个数字。
            final int kept = report.enchants().loaded();

            sender.sendMessage(messages.component(
                    0 < kept ? "command.reload.enchant-rejected" : "command.reload.enchant-rejected-empty",
                    "kept", String.valueOf(kept),
                    "details", String.join("; ", report.enchants().failures())
            ));
        } else if (0 < report.enchants().failed()) {
            // 按当前契约不会走到这里（已发布即失败明细为空），留作契约漂移时的兜底。
            this.reportFailures(
                    sender,
                    "command.reload.enchant-failures",
                    report.enchants().failed(),
                    report.enchants().failures()
            );
        }

        this.reportFailures(
                sender,
                "command.reload.script-failures",
                report.scripts().failed(),
                report.scripts().failures()
        );

        if (report.fullSuccess()) {
            sender.sendMessage(messages.component(
                    "command.reload.done",
                    // 脚本用注册表里的生效总数：reloadAll() 的 loaded() 只是「本轮成功加载数」，
                    // 会被同时清理掉的已删除脚本拉低，报给管理员看会显得数量对不上。
                    "scripts", String.valueOf(this.plugin.getScriptRegistry().size()),
                    "enchants", String.valueOf(report.enchants().loaded())
            ));

            // 附魔定义重载只更新插件自己的判定与数据包文件；原版注册表要等服务端重新加载
            // 世界（/reload 或重启）才会重读数据包。这句不说，附魔台「点了没反应」会被当成 bug。
            sender.sendMessage(messages.component("command.reload.datapack-hint"));

            return;
        }

        sender.sendMessage(messages.component(
                "command.reload.partial",
                "scripts_loaded", String.valueOf(report.scripts().loaded()),
                "scripts_failed", String.valueOf(report.scripts().failed())
        ));
    }

    private void reportFailures(
            final CommandSender sender,
            final String messageKey,
            final int failed,
            final List<String> failures
    ) {
        if (0 >= failed) {
            return;
        }

        sender.sendMessage(this.plugin.messages().component(
                messageKey,
                "failed", String.valueOf(failed),
                "details", String.join("; ", failures)
        ));
    }

    private void list(final CommandSender sender) {
        final MessageService messages = this.plugin.messages();
        final var scripts = this.plugin.getScriptRegistry().ids();
        final var enchants = this.plugin.getEnchantRegistry().all();
        final String empty = messages.raw("command.list.none");

        sender.sendMessage(messages.component(
                "command.list.scripts",
                "count", String.valueOf(scripts.size()),
                "ids", scripts.isEmpty() ? empty : String.join(", ", scripts)
        ));

        final List<String> entries = new ArrayList<>();

        enchants.forEach((id, definition) -> entries.add(messages.raw(
                "command.list.enchant-entry",
                "id", id,
                "trigger", definition.trigger().name(),
                "max", String.valueOf(definition.maxLevel())
        )));

        sender.sendMessage(messages.component(
                "command.list.enchants",
                "count", String.valueOf(entries.size()),
                "entries", entries.isEmpty() ? empty : String.join(", ", entries)
        ));
    }

    /**
     * 给目标玩家的主手物品附加附魔。
     *
     * <p>「读主手 → 写入附魔 → 放回主手」整段都必须在目标实体自己的区域线程上完成：
     * 在别的区域读背包在 Folia 上是越界访问。因此参数解析完就交给
     * {@code tasks().entity(...)}——当前线程已经拥有该实体时它会就近同步执行。
     *
     * <p>返回值故意不看：{@code retired} 回调是「没执行」的唯一通知出口（实体退休、调度被拒
     * 都会走它，且只走一次），这里再判一次就会给玩家发两条提示。
     */
    private void give(final CommandSender sender, final String[] args, final String label) {
        final MessageService messages = this.plugin.messages();

        if (args.length < 3) {
            this.reply(sender, messages.component("command.give.usage", "label", label));

            return;
        }

        final Player target = Bukkit.getPlayerExact(args[1]);

        if (null == target) {
            this.reply(sender, messages.component("command.give.player-missing", "player", args[1]));

            return;
        }

        final EnchantDefinition definition = this.plugin.getEnchantRegistry().get(args[2].toLowerCase(Locale.ROOT));

        if (null == definition) {
            this.reply(sender, messages.component("command.give.enchant-missing", "id", args[2]));

            return;
        }

        int level = definition.maxLevel();

        if (args.length >= 4) {
            try {
                level = Integer.parseInt(args[3]);
            } catch (final NumberFormatException exception) {
                this.reply(sender, messages.component("command.give.level-invalid", "input", args[3]));

                return;
            }
        }

        // 提示里只用输入的名字：从外区读实体状态（哪怕只是名字）都不该发生。
        final String targetName = args[1];
        final int requestedLevel = level;

        this.plugin.tasks().entity(
                target,
                () -> this.applyGive(sender, target, targetName, definition, requestedLevel),
                () -> this.reply(
                        sender,
                        this.plugin.messages().component("command.give.not-executed", "player", targetName)
                )
        );
    }

    /** 在目标实体所在区域真正落地附魔；提示一律交回发送者线程。 */
    private void applyGive(
            final CommandSender sender,
            final Player target,
            final String targetName,
            final EnchantDefinition definition,
            final int level
    ) {
        final MessageService messages = this.plugin.messages();
        final ItemStack item = target.getInventory().getItemInMainHand();

        if (null == item || item.getType().isAir()) {
            this.reply(sender, messages.component("command.give.no-item", "player", targetName));

            return;
        }

        final int applied = this.plugin.getApplier().apply(item, definition, level);

        if (0 >= applied) {
            this.reply(sender, messages.component("command.give.unsupported", "id", definition.id()));

            return;
        }

        target.getInventory().setItemInMainHand(item);

        this.reply(sender, messages.component(
                "command.give.done",
                "player", targetName,
                "item", item.getType().name(),
                "id", definition.id(),
                "level", String.valueOf(applied)
        ));
    }

    /**
     * 把一条提示送回发送者所在线程：玩家走它自己的实体线程，控制台走全局线程。
     *
     * <p>Folia 上「在 A 区给 B 区的玩家发消息」不是安全的随手操作，所以所有回执都收敛到
     * 这一个出口，而不是让每个分支各自 {@code sendMessage}。
     *
     * <p>调度器已关闭（停用/关服途中）时不能再排队——那样提示会静默消失。这时只在
     * 「当前线程本来就安全」的情况下就地发送，见 {@link #replyOnCurrentThread}。
     */
    private void reply(final CommandSender sender, final Component message) {
        if (!this.plugin.tasks().active()) {
            this.replyOnCurrentThread(sender, message);

            return;
        }

        if (sender instanceof Entity entity) {
            this.plugin.tasks().entity(entity, () -> sender.sendMessage(message), null);

            return;
        }

        this.plugin.tasks().global(() -> sender.sendMessage(message));
    }

    /**
     * 停用边界上的回执：不排队，只在当前线程本来就能安全写的时候直接发。
     *
     * <p>控制台/命令方块与线程无关，直接写；玩家则先确认本线程拥有该实体（命令本身就是
     * 在发送者线程上执行的，通常成立）。两者都不成立时宁可放弃并留日志，也不能跨区读状态
     * 或者拿已关闭的调度器重试。
     */
    private void replyOnCurrentThread(final CommandSender sender, final Component message) {
        if (!(sender instanceof Entity entity)) {
            sender.sendMessage(message);

            return;
        }

        if (this.plugin.tasks().owns(entity)) {
            sender.sendMessage(message);

            return;
        }

        this.plugin.getLogger().warning(
                "插件停用中，无法把提示发回 " + sender.getName() + "（当前线程不拥有该玩家），已放弃发送。"
        );
    }

    @Override
    public List<String> onTabComplete(
            @NotNull final CommandSender sender,
            @NotNull final Command command,
            @NotNull final String alias,
            final String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (1 == args.length) {
            return filter(SUB_COMMANDS, args[0]);
        }

        if (2 == args.length && "give".equalsIgnoreCase(args[0])) {
            final List<String> names = new ArrayList<>();

            for (final Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }

            return filter(names, args[1]);
        }

        if (3 == args.length && "give".equalsIgnoreCase(args[0])) {
            return filter(new ArrayList<>(this.plugin.getEnchantRegistry().all().keySet()), args[2]);
        }

        return List.of();
    }

    private static List<String> filter(final List<String> candidates, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();

        for (final String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(candidate);
            }
        }

        return matches;
    }
}
