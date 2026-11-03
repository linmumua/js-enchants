package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.config.ConfigService;
import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据包生成的守门测试。
 *
 * <p>重点不在「能写出文件」，而在那些「写错不报错、只是刷不出来」的地方：
 * 字段名拼错、tag 没带上、{@code replace} 写成 {@code true}（会把原版附魔挤出附魔台）、
 * {@code items} 为空被写成空列表（该附魔永远刷不到）。
 */
class EnchantDatapackWriterTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final ConfigService.Datapack DATAPACK = new ConfigService.Datapack(true, 10, 1, 11, 21, 11, 1);

    private static final String ENCHANT_DIR = "data/jsenchants/enchantment";

    private static final String TAG = "data/minecraft/tags/enchantment/in_enchanting_table.json";

    private static EnchantDefinition definition(
            final String id,
            final String display,
            final int maxLevel,
            final Set<Material> items,
            final Set<EquipmentSlot> slots
    ) {
        return new EnchantDefinition(
                id,
                display,
                maxLevel,
                items,
                slots,
                TriggerBinding.builtin(TriggerType.ATTACK),
                0L,
                0.1D,
                0.0D,
                id + ".js",
                true
        );
    }

    private static EnchantDefinition sword(final String id, final int maxLevel) {
        return definition(id, "<gold>" + id, maxLevel, Set.of(Material.DIAMOND_SWORD), Set.of(EquipmentSlot.HAND));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(final String json) {
        final Object loaded = new Yaml().load(json);

        assertNotNull(loaded, "生成的 JSON 必须可解析");

        return (Map<String, Object>) loaded;
    }

    @Test
    void 附魔JSON字段齐全且键名精确() {
        final EnchantDatapackWriter.Plan plan = EnchantDatapackWriter.render(List.of(sword("fireball", 3)), DATAPACK);

        final Map<String, Object> json = parse(plan.files().get(ENCHANT_DIR + "/fireball.json"));

        // 字段名对着原版 Enchantment / EnchantmentDefinition 的编解码器：拼错一个整个文件不加载。
        // exclusive_set 与 effects 是可选项，我们刻意不写（与原版 looting.json 一致），
        // 所以这里断言的是"它们不存在"，而不是存在但为空——写空值会让解析多一处歧义，
        // 而这个 JSON 一旦失败，整个数据包连带所有附魔一起消失。
        assertEquals(
                Set.of(
                        "description", "supported_items", "primary_items", "weight", "max_level",
                        "min_cost", "max_cost", "anvil_cost", "slots"
                ),
                json.keySet()
        );

        assertEquals(Map.of("text", "fireball"), json.get("description"), "description 用纯文本，不依赖语言文件");
        assertEquals(List.of("minecraft:diamond_sword"), json.get("supported_items"));
        assertEquals(List.of("minecraft:diamond_sword"), json.get("primary_items"));
        assertEquals(10, json.get("weight"));
        assertEquals(3, json.get("max_level"));
        assertEquals(Map.of("base", 1, "per_level_above_first", 11), json.get("min_cost"));
        assertEquals(Map.of("base", 21, "per_level_above_first", 11), json.get("max_cost"));
        assertEquals(1, json.get("anvil_cost"));
        assertEquals(List.of("mainhand"), json.get("slots"));
        assertFalse(json.containsKey("exclusive_set"), "空的可选字段应当省略，而不是写成空数组");
        assertFalse(json.containsKey("effects"), "原版侧无行为，效果由 JS 提供；该可选字段直接省略");
    }

    @Test
    void tag文件含全部启用id且replace为false() {
        final EnchantDatapackWriter.Plan plan = EnchantDatapackWriter.render(
                List.of(sword("fireball", 3), sword("lifesteal", 2), sword("execute", 1)),
                DATAPACK
        );

        final Map<String, Object> tag = parse(plan.files().get(TAG));

        assertEquals(false, tag.get("replace"), "replace 必须是 false，否则原版附魔会被挤出附魔台");
        assertEquals(
                List.of("jsenchants:execute", "jsenchants:fireball", "jsenchants:lifesteal"),
                tag.get("values")
        );
    }

    @Test
    void items为空的附魔被跳过并告警且不进tag() {
        final EnchantDefinition unrestricted = definition(
                "packet_notice", "<aqua>以太回声", 2, Set.of(), Set.of(EquipmentSlot.HAND)
        );

        final EnchantDatapackWriter.Plan plan = EnchantDatapackWriter.render(
                List.of(unrestricted, sword("fireball", 3)),
                DATAPACK
        );

        assertNull(plan.files().get(ENCHANT_DIR + "/packet_notice.json"), "空 items 会变成「所有物品都能刷到」，必须跳过");
        assertTrue(
                plan.warnings().stream().anyMatch(warning -> warning.contains("packet_notice") && warning.contains("items")),
                "跳过的附魔必须逐条告警，不能静默"
        );
        assertEquals(List.of("jsenchants:fireball"), parse(plan.files().get(TAG)).get("values"));
    }

    @Test
    void maxLevel超过10照常写入并逐条告警() {
        final EnchantDatapackWriter.Plan plan = EnchantDatapackWriter.render(List.of(sword("overkill", 12)), DATAPACK);

        final Map<String, Object> json = parse(plan.files().get(ENCHANT_DIR + "/overkill.json"));

        assertEquals(12, json.get("max_level"), "等级越界不能跳过注册——跳过等于该附魔彻底消失");
        assertEquals(
                1,
                plan.warnings().stream().filter(warning -> warning.contains("enchantment.level")).count(),
                "越界必须逐条告警，并点明客户端会显示原文"
        );
    }

    @Test
    void pack_mcmeta的pack_format为121() {
        final EnchantDatapackWriter.Plan plan = EnchantDatapackWriter.render(List.of(sword("fireball", 3)), DATAPACK);

        @SuppressWarnings("unchecked")
        final Map<String, Object> pack = (Map<String, Object>) parse(plan.files().get("pack.mcmeta")).get("pack");

        // 121 来自 26.3 服务端 version.json 的 pack_version.data_major；写错整个数据包被判为不兼容。
        assertEquals(121, pack.get("pack_format"));
    }

    @Test
    void 物品与槽位按原版id映射() {
        final EnchantDefinition definition = definition(
                "multi",
                "<red>多槽",
                2,
                Set.of(Material.NETHERITE_AXE, Material.DIAMOND_SWORD),
                Set.of(EquipmentSlot.HEAD, EquipmentSlot.OFF_HAND)
        );

        final Map<String, Object> json = parse(
                EnchantDatapackWriter.render(List.of(definition), DATAPACK).files().get(ENCHANT_DIR + "/multi.json")
        );

        assertEquals(List.of("minecraft:diamond_sword", "minecraft:netherite_axe"), json.get("supported_items"));
        assertEquals(List.of("head", "offhand"), json.get("slots"), "原版要的是槽位组 key，不是 Bukkit 枚举名");
    }

    @Test
    void 未写槽位时默认主手() {
        final EnchantDefinition definition = definition(
                "no_slot", "<gray>无槽位", 1, Set.of(Material.DIAMOND_SWORD), Set.of()
        );

        final Map<String, Object> json = parse(
                EnchantDatapackWriter.render(List.of(definition), DATAPACK).files().get(ENCHANT_DIR + "/no_slot.json")
        );

        assertEquals(List.of("mainhand"), json.get("slots"));
    }

    @Test
    void MiniMessage展示名转成纯文本() {
        final EnchantDefinition definition = definition(
                "colored", "<gradient:gold:red><bold>焰爆</bold></gradient>", 3,
                Set.of(Material.DIAMOND_SWORD), Set.of(EquipmentSlot.HAND)
        );

        final Map<String, Object> json = parse(
                EnchantDatapackWriter.render(List.of(definition), DATAPACK).files().get(ENCHANT_DIR + "/colored.json")
        );

        assertEquals(Map.of("text", "焰爆"), json.get("description"));
    }

    @Test
    void 关闭的附魔不进数据包() {
        final EnchantDefinition disabled = new EnchantDefinition(
                "off", "<gray>已关", 1, Set.of(Material.DIAMOND_SWORD), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.1D, 0.0D, "off.js", false
        );

        final EnchantDatapackWriter.Plan plan = EnchantDatapackWriter.render(List.of(disabled), DATAPACK);

        assertNull(plan.files().get(ENCHANT_DIR + "/off.json"));
        assertEquals(List.of(), parse(plan.files().get(TAG)).get("values"));
    }

    @Test
    void 落盘幂等并清理已下线附魔(@TempDir final Path dir) throws IOException {
        final Path world = Files.createDirectories(dir.resolve("world"));
        final EnchantDatapackWriter writer = new EnchantDatapackWriter(LOGGER);

        final EnchantDatapackWriter.Report first = writer.write(
                List.of(sword("fireball", 3), sword("lifesteal", 2)), DATAPACK, world
        );

        assertTrue(first.written());
        assertEquals(2, first.enchantments());

        final Path enchantDir = world.resolve("datapacks/jsenchants").resolve(ENCHANT_DIR);

        assertTrue(Files.isRegularFile(enchantDir.resolve("fireball.json")));
        assertTrue(Files.isRegularFile(enchantDir.resolve("lifesteal.json")));
        assertTrue(Files.isRegularFile(world.resolve("datapacks/jsenchants/pack.mcmeta")));
        assertTrue(Files.isRegularFile(world.resolve("datapacks/jsenchants").resolve(TAG)));

        // 目录里服主自己放的东西不能被当成垃圾清掉。
        final Path foreign = enchantDir.resolve("README.txt");

        Files.writeString(foreign, "不要删我", StandardCharsets.UTF_8);

        final EnchantDatapackWriter.Report second = writer.write(List.of(sword("fireball", 3)), DATAPACK, world);

        assertEquals(1, second.enchantments());
        assertEquals(1, second.removed(), "已下线附魔的旧 json 必须被清掉，否则附魔台还能刷到它");
        assertFalse(Files.exists(enchantDir.resolve("lifesteal.json")));
        assertTrue(Files.isRegularFile(enchantDir.resolve("fireball.json")));
        assertTrue(Files.isRegularFile(foreign), "目录内其它内容必须保留");

        // 幂等：同样输入再写一次，内容完全一致、不再删东西。
        final String before = Files.readString(enchantDir.resolve("fireball.json"), StandardCharsets.UTF_8);
        final EnchantDatapackWriter.Report third = writer.write(List.of(sword("fireball", 3)), DATAPACK, world);

        assertEquals(0, third.removed());
        assertEquals(before, Files.readString(enchantDir.resolve("fireball.json"), StandardCharsets.UTF_8));
    }

    @Test
    void 没有世界时不写文件也不抛异常() {
        final EnchantDatapackWriter.Report report = new EnchantDatapackWriter(LOGGER)
                .write(List.of(sword("fireball", 3)), DATAPACK, null);

        assertFalse(report.written());
        assertEquals(0, report.enchantments());
        assertFalse(report.warnings().isEmpty(), "跳过必须留下可见的告警");
    }

    @Test
    void 关闭数据包时不动世界目录(@TempDir final Path dir) throws IOException {
        final Path world = Files.createDirectories(dir.resolve("world"));
        final ConfigService.Datapack disabled = new ConfigService.Datapack(false, 10, 1, 11, 21, 11, 1);

        final EnchantDatapackWriter.Report report = new EnchantDatapackWriter(LOGGER)
                .write(List.of(sword("fireball", 3)), disabled, world);

        assertFalse(report.written());
        assertFalse(Files.exists(world.resolve("datapacks")), "关闭后连目录都不该创建");
    }
}
