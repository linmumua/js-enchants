package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附魔定义是玩家可编辑的入口，解析必须稳健：坏文件跳过而不是崩服，
 * 概率必须随等级正确递增（这是附魔手感的核心，改坏玩家会立刻察觉）。
 */
class EnchantParserTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private final EnchantParser parser = new EnchantParser(LOGGER);

    private Path write(final Path dir, final String name, final String content) throws IOException {
        final Path file = dir.resolve(name);

        Files.writeString(file, content, StandardCharsets.UTF_8);

        return file;
    }

    @Test
    void 解析完整定义(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "fireball.yml", """
                id: fireball
                display: "<gold>Fireball"
                max-level: 3
                items: [DIAMOND_SWORD, DIAMOND_AXE]
                trigger: ATTACK
                cooldown-ms: 3000
                chance:
                  base: 0.15
                  per-level: 0.05
                script: fireball.js
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals("fireball", definition.id());
        assertEquals(3, definition.maxLevel());
        assertEquals(TriggerType.ATTACK, definition.trigger().builtin());
        assertEquals(3000L, definition.cooldownMillis());
        assertEquals("fireball.js", definition.script());
        assertTrue(definition.supports(Material.DIAMOND_SWORD));
        assertFalse(definition.supports(Material.IRON_SHOVEL));
    }

    @Test
    void 缺省值合理(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "minimal.yml", "id: minimal");

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(1, definition.maxLevel());
        assertEquals(TriggerType.ATTACK, definition.trigger().builtin(), "默认触发应为攻击");
        assertEquals(0L, definition.cooldownMillis());
        assertEquals("minimal.js", definition.script(), "脚本名应默认与 id 一致");
        assertTrue(definition.supports(Material.DIRT), "未指定 items 时应不限制物品");
    }

    @Test
    void 非法id被拒绝(@TempDir final Path dir) throws IOException {
        // id 会进 PDC key，大写和非法字符必须挡住。
        final Path file = this.write(dir, "bad.yml", "id: Bad Enchant!");

        assertNull(this.parser.parse(file));
    }

    @Test
    void 未知trigger被拒绝(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "bad.yml", """
                id: bad
                trigger: NOT_A_TRIGGER
                """);

        assertNull(this.parser.parse(file));
    }

    @Test
    void trigger允许中划线与小写(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "rc.yml", """
                id: rc
                trigger: right-click
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(TriggerType.RIGHT_CLICK, definition.trigger().builtin());
    }

    @Test
    void 概率随等级递增并封顶(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "scale.yml", """
                id: scale
                max-level: 10
                chance:
                  base: 0.15
                  per-level: 0.5
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);

        assertEquals(0.15D, definition.chanceAt(1), 1e-9, "1 级应等于基础值");
        assertEquals(0.65D, definition.chanceAt(2), 1e-9, "每级递增");
        assertEquals(1.0D, definition.chanceAt(5), 1e-9, "超过 1 必须封顶，否则等于必触发");
    }

    @Test
    void 等级被夹到合法范围(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "clamp.yml", """
                id: clamp
                max-level: 3
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(1, definition.clampLevel(0), "0 级应夹到 1");
        assertEquals(1, definition.clampLevel(-5), "负数应夹到 1");
        assertEquals(3, definition.clampLevel(99), "超上限应夹到 max-level");
    }

    @Test
    void 未知物品类型被忽略而不报错(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "mixed.yml", """
                id: mixed
                items: [DIAMOND_SWORD, NOT_A_MATERIAL]
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(1, definition.items().size(), "非法物品应被跳过，合法项保留");
        assertTrue(definition.supports(Material.DIAMOND_SWORD));
    }

    @Test
    void 数字物品名被拒绝(@TempDir final Path dir) throws IOException {
        // items 写成数字（几乎等于把「数量」写到了物品名位置）时不能悄悄忽略：
        // 忽略后 items 变空，等于「所有物品都能附着」，附魔会莫名其妙到处触发。
        final Path file = this.write(dir, "numeric.yml", """
                id: numeric
                items: [1, 2]
                """);

        assertNull(this.parser.parse(file));
    }

    @Test
    void 重复键被拒绝(@TempDir final Path dir) throws IOException {
        // 重复键默认后者覆盖前者且毫无提示，服主改配置时极易丢字段。
        final Path file = this.write(dir, "duplicate.yml", """
                id: dup
                max-level: 2
                max-level: 5
                """);

        assertNull(this.parser.parse(file));
    }

    @Test
    void 非映射内容返回null(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "list.yml", "- a\n- b\n");

        assertNull(this.parser.parse(file));
    }

    /** 写一个只有等级的最小定义，取解析出的稀有度。 */
    private EnchantRarity rarityOf(final Path dir, final String id, final int maxLevel) throws IOException {
        final Path file = this.write(dir, id + ".yml", """
                id: %s
                max-level: %d
                """.formatted(id, maxLevel));

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition, id + " 应该能解析成功");

        return definition.rarity();
    }

    @Test
    void 无rarity时按最大等级推导(@TempDir final Path dir) throws IOException {
        // 存量的 10 个 yml 全都没有 rarity 字段。缺省若统一取 common，
        // 「按稀有度分色」就退化成单色——等于没做，所以这里必须逐档验证。
        assertEquals(EnchantRarity.COMMON, this.rarityOf(dir, "lv1", 1), "1 级推导为 common");
        assertEquals(EnchantRarity.UNCOMMON, this.rarityOf(dir, "lv2", 2), "2 级推导为 uncommon");
        assertEquals(EnchantRarity.RARE, this.rarityOf(dir, "lv3", 3), "3 级推导为 rare");
        assertEquals(EnchantRarity.EPIC, this.rarityOf(dir, "lv4", 4), "4 级推导为 epic");
    }

    @Test
    void 显式rarity覆盖推导值(@TempDir final Path dir) throws IOException {
        // 1 级按推导应是 common，显式写 rare 必须生效。
        final Path file = this.write(dir, "explicit.yml", """
                id: explicit
                max-level: 1
                rarity: rare
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(EnchantRarity.RARE, definition.rarity());
    }

    @Test
    void rarity大小写与空白被容忍(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "spaced.yml", """
                id: spaced
                max-level: 1
                rarity: "  rARe  "
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(EnchantRarity.RARE, definition.rarity());
    }

    @Test
    void 非法rarity整条拒绝且点明字段(@TempDir final Path dir) throws IOException {
        // 与 bool/number 一致：写错就整条拒绝，不能静默退回推导值——
        // 配置里明明写着一个值，服主不会想到去查解析器。
        final Path file = this.write(dir, "bad_rarity.yml", """
                id: badrarity
                max-level: 3
                rarity: legendary
                """);

        final EnchantParser.ParseOutcome outcome = this.parser.parseOutcome(file);

        assertNull(outcome.definition(), "非法 rarity 必须整条拒绝");
        assertNotNull(outcome.error(), "被拒绝时必须带原因");
        assertTrue(outcome.error().contains("rarity"), "错误消息要点明字段名，实际为: " + outcome.error());
    }

    @Test
    void options原样透传标量与字符串列表(@TempDir final Path dir) throws IOException {
        // 这条通道存在的唯一理由：附魔自己的知识（例如"允许连锁哪些方块"）
        // 不该逼框架为它加字段。框架只做类型把关，不做语义解释。
        final Path file = this.write(dir, "opt.yml", """
                id: opt
                options:
                  blocks:
                    - "#minecraft:ores"
                    - DIAMOND_ORE
                  radius: 4
                  highlight: true
                  label: "古矿"
                """);

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertEquals(
                List.of("#minecraft:ores", "DIAMOND_ORE"),
                definition.options().get("blocks"),
                "列表必须规整成字符串列表，顺序保持原样"
        );
        assertEquals(4, definition.options().get("radius"), "数字原样保留");
        assertEquals(Boolean.TRUE, definition.options().get("highlight"), "布尔原样保留");
        assertEquals("古矿", definition.options().get("label"));
    }

    @Test
    void 未写options时为空映射(@TempDir final Path dir) throws IOException {
        // 存量 10 个 yml 都没有 options：缺省必须是空映射，
        // 而不是 null——脚本侧读选项不该额外判空。
        final Path file = this.write(dir, "none.yml", "id: none");

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);
        assertTrue(definition.options().isEmpty());
    }

    @Test
    void options写成非映射会被拒绝并点明字段(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, "bad_opt.yml", """
                id: badopt
                options:
                  - a
                  - b
                """);

        final EnchantParser.ParseOutcome outcome = this.parser.parseOutcome(file);

        assertNull(outcome.definition(), "options 是列表时必须整条拒绝");
        assertNotNull(outcome.error());
        assertTrue(outcome.error().contains("options"), "错误消息要点明字段名，实际为: " + outcome.error());
    }

    @Test
    void options含嵌套映射会被拒绝(@TempDir final Path dir) throws IOException {
        // 放行嵌套就等于把 SnakeYAML 反序列化出的任意结构交给脚本：
        // 脚本拿到一个"看起来有值"的怪对象，排查成本远高于直接拒绝。
        final Path file = this.write(dir, "nested_opt.yml", """
                id: nested
                options:
                  blocks:
                    inner:
                      - DIAMOND_ORE
                """);

        final EnchantParser.ParseOutcome outcome = this.parser.parseOutcome(file);

        assertNull(outcome.definition(), "嵌套映射必须整条拒绝");
        assertNotNull(outcome.error());
        assertTrue(outcome.error().contains("blocks"), "错误消息要点明是哪个选项，实际为: " + outcome.error());
    }

    @Test
    void options的空值会被拒绝(@TempDir final Path dir) throws IOException {
        // `blocks:` 后面什么都没写（YAML 里就是 null）几乎一定是漏填，
        // 静默变成"没有白名单"会让连锁采集换一套规则跑，症状极难联想。
        final Path file = this.write(dir, "blank_opt.yml", """
                id: blankopt
                options:
                  blocks:
                """);

        final EnchantParser.ParseOutcome outcome = this.parser.parseOutcome(file);

        assertNull(outcome.definition());
        assertNotNull(outcome.error());
        assertTrue(outcome.error().contains("blocks"), "实际为: " + outcome.error());
    }

    @Test
    void 随包分发的vein_miner定义可以解析(@TempDir final Path dir) throws IOException {
        // options.blocks 里可能写带 # 的标签，而 YAML 里 # 是注释符：
        // 少一层引号就会被截成空值，整份定义会因此被拒（空值选项一律报错）。
        // 用真实的随包文件跑一遍，防止"只有部署后才发现配置写坏"。
        final Path file = dir.resolve("vein_miner.yml");

        try (final InputStream stream = EnchantParserTest.class.getResourceAsStream("/enchants/vein_miner.yml")) {
            assertNotNull(stream, "默认附魔定义必须随插件分发: /enchants/vein_miner.yml");
            Files.write(file, stream.readAllBytes());
        }

        final EnchantDefinition definition = this.parser.parse(file);

        assertNotNull(definition);

        // 断言的是"结构成立"，不是逐项钉死清单：白名单会随需求增删方块，
        // 把具体内容写死会让每次调整都要改测试，反而掩盖真正的问题。
        // 真正必须守住的是：非空、元素都是非空字符串、没有把注释符写进去（那说明引号丢了）。
        final Object blocks = definition.options().get("blocks");

        assertNotNull(blocks, "随包定义必须带 options.blocks");
        assertTrue(blocks instanceof List<?>, "blocks 必须解析成列表，实际为: " + blocks.getClass());

        final List<?> entries = (List<?>) blocks;

        assertFalse(entries.isEmpty(), "白名单不能为空：空列表会回退到旧的兜底行为");

        for (final Object entry : entries) {
            assertTrue(entry instanceof String, "白名单项必须是字符串，实际为: " + entry);
            assertFalse(((String) entry).isBlank(), "白名单里不能有空项（多半是 # 开头的标签漏了引号）");
        }

        // 原木与矿石都要在：砍树和挖矿是这条附魔的两项核心用途。
        assertTrue(entries.contains("OAK_LOG"), "白名单应包含原木，否则砍树不连锁");
        assertTrue(entries.contains("DIAMOND_ORE"), "白名单应包含矿石");
    }

    @Test
    void 不带options的构造重载等价于空映射() {
        // 存量构造点（测试与脚本侧拼装）不写 options：这个重载必须继续可用。
        final EnchantDefinition definition = new EnchantDefinition(
                "legacy", "旧定义", 2, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK),
                0L, 0.5D, 0.0D, "legacy.js", true
        );

        assertTrue(definition.options().isEmpty());
    }
}
