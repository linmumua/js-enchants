package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.config.ConfigService;
import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lore 合并是「玩家看得见的数据」里最容易出事的一块：删多了会吞掉别的插件的行，
 * 删少了会堆出一串重复的附魔行。这些测试锁的正是这两条边界。
 *
 * <p>后半部分锁的是改造后新加的三条：{@code HIDE_ENCHANTS} 的按构成重算、
 * 稀有度配色只换颜色不换文本、以及附魔书不能被当成装备读。
 */
class EnchantApplierTest {

    private static final String FORMAT = "<gray>{display} <dark_gray>{level}";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** 本插件附魔的 key，例如 {@code jsenchants:fireball}。 */
    private static NamespacedKey ours(final String id) {
        return new NamespacedKey(EnchantApplier.DATA_NAMESPACE, id);
    }

    /** 别人的原版附魔，例如 {@code minecraft:sharpness}。 */
    private static NamespacedKey vanilla(final String id) {
        return new NamespacedKey("minecraft", id);
    }

    /** 只改颜色、不改文本形态的调色板——这正是配置层要做到的红线。 */
    private static final ConfigService.LorePalette PALETTE = new ConfigService.LorePalette(
            "<gray>{display} <dark_gray>{level}",
            "<aqua>{display} <dark_aqua>{level}",
            "<light_purple>{display} <dark_purple>{level}",
            "<gold>{display} <yellow>{level}"
    );

    private static EnchantDefinition definition(final String id, final String display, final int maxLevel) {
        return new EnchantDefinition(
                id, display, maxLevel, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, id + ".js", true
        );
    }

    private static String plain(final Component component) {
        return PLAIN.serialize(component);
    }

    /** 旧版本遗留行的判定器：纯文本对得上某个已知附魔的渲染结果。 */
    private static boolean isLegacyOwnLine(final Component line, final EnchantDefinition definition,
                                           final String format) {
        final String expected = plain(EnchantApplier.renderLine(format, definition, 1));

        return null != line && expected.equals(plain(line));
    }

    @Test
    void 只清理自有标记行并保留第三方行() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        final List<Component> existing = new ArrayList<>(List.of(
                Component.text("第三方: 耐久 III"),
                EnchantApplier.renderLine(FORMAT, fireball, 2),
                Component.text("第三方: 附注")
        ));

        final List<Component> fresh = List.of(EnchantApplier.renderLine(FORMAT, fireball, 3));
        final List<Component> merged = EnchantApplier.rebuildLore(existing, 1, fresh, line -> false);

        assertEquals(3, merged.size(), "一条旧标记行换一条新行，第三方行不能被碰");
        assertEquals("第三方: 耐久 III", plain(merged.get(0)));
        assertEquals("第三方: 附注", plain(merged.get(1)), "第三方行保持原有相对顺序");
        assertEquals("Fireball 3", plain(merged.get(2)), "自有行追加在末尾");
        assertTrue(EnchantApplier.isOwnLoreLine(merged.get(2)), "新行必须带标记，下次才能精确定位");
        assertFalse(EnchantApplier.isOwnLoreLine(merged.get(0)));
    }

    @Test
    void 反复重绘不堆叠重复行() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);
        final List<Component> fresh = List.of(EnchantApplier.renderLine(FORMAT, fireball, 3));

        List<Component> lore = new ArrayList<>(List.of(Component.text("第三方")));

        for (int round = 0; round < 5; round++) {
            lore = EnchantApplier.rebuildLore(lore, 1, fresh, line -> false);
        }

        assertEquals(2, lore.size(), "重绘五次之后仍然只能有一行自有 Lore");
        assertEquals("第三方", plain(lore.get(0)));
        assertEquals("Fireball 3", plain(lore.get(1)));
    }

    @Test
    void 旧版本无标记行按配额清理() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        final List<Component> existing = new ArrayList<>(List.of(
                Component.text("第一行第三方"),
                Component.text("Fireball 1"),
                Component.text("第二行第三方")
        ));

        final List<Component> merged = EnchantApplier.rebuildLore(
                existing,
                1,
                List.of(),
                line -> isLegacyOwnLine(line, fireball, FORMAT)
        );

        assertEquals(2, merged.size(), "配额为 1，只能删掉那一行对得上模板的旧行");
        assertEquals("第一行第三方", plain(merged.get(0)));
        assertEquals("第二行第三方", plain(merged.get(1)));
    }

    @Test
    void 没有历史计数时不清理无标记行() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        final List<Component> existing = new ArrayList<>(List.of(
                Component.text("Fireball 1"),
                Component.text("第三方")
        ));

        // previousLines 为 null 表示旧物品上没有可信的行数记录：宁可留一行旧文字，
        // 也不能凭「看起来像」就删掉别人的 Lore。
        final List<Component> merged = EnchantApplier.rebuildLore(
                existing,
                null,
                List.of(),
                line -> isLegacyOwnLine(line, fireball, FORMAT)
        );

        assertEquals(2, merged.size());
    }

    @Test
    void 配额不会被无标记行突破() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        final List<Component> existing = new ArrayList<>(List.of(
                Component.text("Fireball 1"),
                Component.text("Fireball 1"),
                Component.text("第三方")
        ));

        final List<Component> merged = EnchantApplier.rebuildLore(
                existing,
                1,
                List.of(),
                line -> isLegacyOwnLine(line, fireball, FORMAT)
        );

        assertEquals(2, merged.size(), "历史行数只记了 1 行，就不能借匹配规则删掉两行");
    }

    @Test
    void 物品数据格式固定为已发布结构() {
        // 这些字面量已经出现在服务器里的物品上，改名等于让旧物品的附魔全部失联。
        assertEquals("jsenchants", EnchantApplier.DATA_NAMESPACE);
        assertEquals("lore_lines", EnchantApplier.LORE_LINES_KEY);
        assertEquals("e_", EnchantApplier.LEVEL_PREFIX);
        assertEquals("e_fireball", EnchantApplier.levelKeyName("Fireball"), "等级键要小写且带 e_ 前缀");
    }

    @Test
    void 纯插件附魔的物品打上隐藏标记() {
        // 全是本插件附魔：原版行和我们的彩色行会重复显示同一个附魔，隐藏原版行没有信息损失。
        final boolean shouldHide = EnchantApplier.hideEnchants(true, List.of(ours("fireball"), ours("vehicle")));

        assertTrue(shouldHide, "纯本插件附魔的物品应当隐藏原版行，否则同一附魔显示两遍");
        assertEquals(
                EnchantApplier.FlagAction.HIDE,
                EnchantApplier.flagAction(false, shouldHide),
                "标记原本没打，这一轮要打上"
        );
    }

    @Test
    void 带原版附魔的物品不隐藏以免丢掉原版行() {
        // 附魔台产出的就是这种混合物品：锋利 V + 焰爆。
        final List<NamespacedKey> mixed = List.of(ours("fireball"), vanilla("sharpness"));

        assertFalse(
                EnchantApplier.hideEnchants(true, mixed),
                "HIDE_ENCHANTS 是全物品标记，打上之后连锋利这一行也会消失——宁可重复也不能丢原版信息"
        );
        assertEquals(
                EnchantApplier.FlagAction.NONE,
                EnchantApplier.flagAction(false, false),
                "本来就该显示，不许动元数据"
        );
    }

    @Test
    void 后加原版附魔时隐藏标记必须被摘除() {
        // 第一次：纯本插件附魔，标记被打了上去。
        final boolean first = EnchantApplier.hideEnchants(true, List.of(ours("fireball")));

        assertEquals(EnchantApplier.FlagAction.HIDE, EnchantApplier.flagAction(false, first));

        // 之后玩家在铁砧里把锋利加上去：标记必须摘掉，否则原版行永久看不见。
        final boolean later = EnchantApplier.hideEnchants(true, List.of(ours("fireball"), vanilla("sharpness")));

        assertEquals(
                EnchantApplier.FlagAction.SHOW,
                EnchantApplier.flagAction(true, later),
                "「先纯插件附魔、后来被加上锋利」是真实路径，摘除那一半坏掉就会留下永久隐藏的死角"
        );
    }

    @Test
    void 关闭_lore_时永不隐藏原版行() {
        // 我们不再写彩色行，再隐藏原版行就等于让玩家什么附魔都看不到。
        final boolean shouldHide = EnchantApplier.hideEnchants(false, List.of(ours("fireball")));

        assertFalse(shouldHide);
        assertEquals(
                EnchantApplier.FlagAction.SHOW,
                EnchantApplier.flagAction(true, shouldHide),
                "旧版本残留的标记要顺手摘掉，否则关掉 lore 之后原版行也一起消失"
        );
    }

    @Test
    void 空物品不打隐藏标记() {
        assertFalse(EnchantApplier.hideEnchants(true, List.of()), "没有本插件附魔就没有要隐藏的重复行");
    }

    @Test
    void 稀有度只换颜色不换文本形态() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);
        final String legacyText = plain(EnchantApplier.renderLine(FORMAT, fireball, 2));

        for (final EnchantRarity rarity : EnchantRarity.values()) {
            assertEquals(
                    legacyText,
                    plain(EnchantApplier.renderLine(PALETTE.templateFor(rarity), fireball, 2)),
                    "旧 Lore 行靠纯文本完全相等识别，配色改成 " + rarity
                            + " 也必须一字不差，否则存量旧行永久残留"
            );
        }
    }

    @Test
    void 不同稀有度渲染出不同颜色() {
        final EnchantDefinition common = new EnchantDefinition(
                "fireball", "Fireball", 1, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, "fireball.js", true,
                EnchantRarity.COMMON
        );
        final EnchantDefinition epic = new EnchantDefinition(
                "fireball", "Fireball", 1, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, "fireball.js", true,
                EnchantRarity.EPIC
        );

        assertNotEquals(
                EnchantApplier.renderLine(PALETTE.templateFor(common.rarity()), common, 1).color(),
                EnchantApplier.renderLine(PALETTE.templateFor(epic.rarity()), epic, 1).color(),
                "不同稀有度必须真的是不同颜色，否则「按稀有度分色」等于没做"
        );
    }

    @Test
    void 附魔书不算装备() {
        // 书上的原版组件落在 stored_enchantments：当成装备附魔读出来，手拿一本书就会触发装备效果。
        assertFalse(EnchantApplier.readsAsEquipment(Material.ENCHANTED_BOOK));
        assertTrue(EnchantApplier.readsAsEquipment(Material.DIAMOND_SWORD));
    }

    // ------------------------------------------------------------------
    // 阶位（{tier}）与换成阶位之后的旧行兼容
    // ------------------------------------------------------------------

    /** 改动前的模板形态：等级写成 {@code Lv.数字}。 */
    private static final String OLD_FORMAT = "<gray>{display} <dark_gray>Lv.{level}";

    /** 改动后的模板形态：等级写成阶位。 */
    private static final String TIER_FORMAT = "<gray>{display} <dark_gray>{tier}";

    private static final String[] ROMAN = {
            "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    @Test
    void 阶位一到十渲染成罗马数字() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 10);

        for (int level = 1; level <= 10; level++) {
            assertEquals(ROMAN[level - 1], EnchantApplier.romanTier(level), level + " 级该显示成阶位");

            final String expected = "Fireball " + ROMAN[level - 1];

            assertEquals(
                    expected,
                    plain(EnchantApplier.renderLine(TIER_FORMAT, fireball, level)),
                    "Lore 行必须是「名字 + 阶位」，玩家才不会看到 Lv." + level
            );
            assertEquals(
                    "<gray>Fireball <dark_gray>" + ROMAN[level - 1],
                    EnchantApplier.formatLine(TIER_FORMAT, fireball, level),
                    "formatLine 只做占位符替换，颜色标签留给 MiniMessage"
            );
        }
    }

    @Test
    void 阶位超出范围时回落阿拉伯数字而不是抛异常() {
        // 0 是「没有这个附魔」的语义（getLevel 的缺失值），负数与 >10 都是定义里能配出来的等级：
        // 模板写 {tier} 不该因为某个附魔开到 12 级就让整条 Lore 渲染失败。
        assertEquals("0", EnchantApplier.romanTier(0));
        assertEquals("-1", EnchantApplier.romanTier(-1));
        assertEquals("11", EnchantApplier.romanTier(11));
        assertEquals("42", EnchantApplier.romanTier(42));

        final EnchantDefinition high = definition("fireball", "Fireball", 12);

        assertEquals("Fireball 12", plain(EnchantApplier.renderLine(TIER_FORMAT, high, 12)));
    }

    @Test
    void 数字与阶位占位符可以同时出现() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        assertEquals(
                "Fireball III (3/3)",
                plain(EnchantApplier.renderLine("<gray>{display} <dark_gray>{tier} <gray>({level}/3)", fireball, 3)),
                "两个占位符互不干扰：一个说厉不厉害，一个说具体几级"
        );
    }

    @Test
    void 换成阶位写法后未登记的旧行认不出来() {
        // 这条锁的是「为什么必须登记 legacy-formats」：旧行是纯文本完全相等识别的。
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);
        final Component oldLine = EnchantApplier.renderLine(OLD_FORMAT, fireball, 2);
        final var withoutLegacy = EnchantApplier.legacyOwnLineMatcher(
                List.of(fireball),
                List.of(TIER_FORMAT)
        );

        assertFalse(
                withoutLegacy.test(oldLine),
                "只登记当前模板时，旧形态的行认不出来——这正是旧行永久残留的原因"
        );
        assertTrue(
                EnchantApplier.legacyOwnLineMatcher(List.of(fireball), List.of(TIER_FORMAT, OLD_FORMAT)).test(oldLine),
                "把旧模板登记进 legacy-formats 之后才能认出来"
        );
    }

    @Test
    void 登记legacy模板后旧行被清理而第三方行保留() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        final List<Component> existing = new ArrayList<>(List.of(
                Component.text("第一行第三方"),
                // 旧版本留下的行没有标记，只能靠 legacy-formats 认出来。
                Component.text("Fireball Lv.2"),
                Component.text("第二行第三方")
        ));

        final List<Component> fresh = List.of(EnchantApplier.renderLine(TIER_FORMAT, fireball, 3));
        final List<Component> merged = EnchantApplier.rebuildLore(
                existing,
                1,
                fresh,
                EnchantApplier.legacyOwnLineMatcher(List.of(fireball), List.of(TIER_FORMAT, OLD_FORMAT))
        );

        assertEquals(3, merged.size(), "一条旧行换成一条新行");
        assertEquals("第一行第三方", plain(merged.get(0)));
        assertEquals("第二行第三方", plain(merged.get(1)), "第三方行不能被 legacy 模板波及");
        assertEquals("Fireball III", plain(merged.get(2)));
        assertTrue(EnchantApplier.isOwnLoreLine(merged.get(2)), "重绘后的新行必须带标记");
    }

    @Test
    void legacy清理仍受lore_lines预算约束() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        // 两条旧形态的自有行，但 PDC 只记了 1 行历史：多出来那条不许动，
        // 否则「登记模板」就等于给自己发了一张随便删行的许可证。
        // 这里用无标记的纯文本行，模拟旧版本留下的行——带标记的行由标记精确清理，不消耗配额。
        final List<Component> existing = new ArrayList<>(List.of(
                Component.text("Fireball Lv.1"),
                Component.text("Fireball Lv.2"),
                Component.text("第三方")
        ));

        final List<Component> merged = EnchantApplier.rebuildLore(
                existing,
                1,
                List.of(),
                EnchantApplier.legacyOwnLineMatcher(List.of(fireball), List.of(OLD_FORMAT))
        );

        assertEquals(2, merged.size(), "预算 1 只能删一行");
        assertEquals("Fireball Lv.1", plain(merged.get(0)), "从末尾往回删，靠前的那条旧行这一轮留着");
        assertEquals("第三方", plain(merged.get(1)), "第三方行排在后面，更不能被预算之外的规则吃掉");
    }

    @Test
    void 空模板不会让空行变成自有行() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);

        // 空模板渲染出的纯文本是空串，若把它也算成候选，物品上任何空行都会被当成自有行删掉。
        assertFalse(
                EnchantApplier.legacyOwnLineMatcher(List.of(fireball), List.of("", "   ")).test(Component.text("")),
                "空模板不能成为候选"
        );
    }

    @Test
    void 三种模板来源都参与旧行识别() {
        final EnchantDefinition fireball = definition("fireball", "Fireball", 3);
        final var matcher = EnchantApplier.legacyOwnLineMatcher(
                List.of(fireball),
                List.of(TIER_FORMAT, OLD_FORMAT, "<green>{display} <dark_green>{level}")
        );

        assertTrue(matcher.test(EnchantApplier.renderLine(TIER_FORMAT, fireball, 1)), "当前模板");
        assertTrue(matcher.test(EnchantApplier.renderLine(OLD_FORMAT, fireball, 1)), "登记过的旧模板");
        assertTrue(
                matcher.test(EnchantApplier.renderLine("<green>{display} <dark_green>{level}", fireball, 1)),
                "另一种数字形态（别的稀有度模板）同样算自有行"
        );
        assertFalse(matcher.test(Component.text("第三方: 耐久 III")), "对不上任何模板的行必须保留");
    }
}
