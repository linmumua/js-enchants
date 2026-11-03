package com.linmumua.jsenchant.enchant;

import com.linmumua.jsenchant.config.ConfigService;
import com.linmumua.jsenchant.trigger.TriggerBinding;
import com.linmumua.jsenchant.trigger.TriggerType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附魔书的数据层与渲染层。
 *
 * <p>锁的是三件事：书的数据必须能和装备的 {@code e_<id>} 完全分开、坏数据必须读不出来、
 * 展示必须沿用 {@code lore.format} 而不是另起一套写法。
 *
 * <p><b>测试范围</b>：到 PDC 与「展示组件」为止。{@link EnchantedBookService#create} 要造真正的
 * {@code ItemStack}（需要注册好的服务端），所以「书真的被做出来、客户端看到什么样」不在单测里；
 * 非空 {@code items} 的材质译名同理（{@code Material#translationKey()} 要读原版注册表）。
 */
class EnchantedBookServiceTest {

    private static final String FORMAT = "<gray>{display} <dark_gray>{level}";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** 只改颜色、不改文本形态的调色板（配置层的红线就在这里）。 */
    private static final ConfigService.LorePalette PALETTE = new ConfigService.LorePalette(
            "<gray>{display} <dark_gray>{level}",
            "<aqua>{display} <dark_aqua>{level}",
            "<light_purple>{display} <dark_purple>{level}",
            "<gold>{display} <yellow>{level}"
    );

    private static ConfigService.Snapshot snapshot(final boolean loreEnabled) {
        return new ConfigService.Snapshot(
                FORMAT,
                loreEnabled,
                false,
                List.of(),
                List.of(),
                new ConfigService.LootBooks(true, 0.1D, List.of("minecraft:chests/*"), 1)
        );
    }

    private static EnchantDefinition definition(final int maxLevel, final Set<Material> items) {
        return new EnchantDefinition(
                "fireball", "<gold><bold>Fireball", maxLevel, items, Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, "fireball.js", true
        );
    }

    /**
     * 稀有度专用定义：{@code display} 不带颜色标签。
     *
     * <p>定义里的 {@code display} 若自带 {@code <gold>} 之类标签，按 MiniMessage 语义会覆盖模板颜色，
     * 那时能看出配色的只有等级那一段。要验证「模板按稀有度取色」本身，就得让 display 保持无色。
     */
    private static EnchantDefinition definition(final EnchantRarity rarity) {
        return new EnchantDefinition(
                "fireball", "Fireball", 3, Set.of(), Set.of(EquipmentSlot.HAND),
                TriggerBinding.builtin(TriggerType.ATTACK), 0L, 0.0D, 0.0D, "fireball.js", true, rarity
        );
    }

    private static NamespacedKey key(final String name) {
        return FakeContainers.key(name);
    }

    /** 书的展示名（与装备 Lore 行同一套模板）。 */
    private static Component name(final EnchantDefinition definition) {
        return EnchantedBookService.presentation(definition, 2, snapshot(true), PALETTE).name();
    }

    @Test
    void 写入后能读回同一份数据且不占用装备的等级键() {
        final FakeContainers container = new FakeContainers();

        assertTrue(EnchantedBookService.write(container, "fireball", 2));

        final EnchantedBookService.BookData data = EnchantedBookService.read(container);

        assertEquals("fireball", data.id());
        assertEquals(2, data.level());

        final Set<String> keys = container.getKeys().stream()
                .map(NamespacedKey::getKey)
                .collect(Collectors.toSet());

        assertEquals(Set.of("book_enchant", "book_level"), keys, "书只该写自己那两个键");

        for (final NamespacedKey written : container.getKeys()) {
            assertEquals("jsenchants", written.getNamespace(), "书与装备共用已发布的数据 namespace");
            assertFalse(
                    written.getKey().startsWith(EnchantApplier.LEVEL_PREFIX),
                    "书的键不能落进装备的 e_ 前缀，否则手拿一本书就会触发装备效果"
            );
        }
    }

    @Test
    void 数据不完整时读不出来但仍被认作本插件的书() {
        final FakeContainers container = new FakeContainers();

        EnchantedBookService.write(container, "fireball", 2);
        container.remove(key(EnchantedBookService.BOOK_LEVEL_KEY));

        assertNull(EnchantedBookService.read(container), "只有 id 没有等级不能当成一本可用的书");
        assertTrue(EnchantedBookService.marked(container), "键的痕迹还在，铁砧必须按「坏数据」处理而不是放行");
    }

    @Test
    void 等级被写成字符串或非正数时一律读不出来() {
        final FakeContainers container = new FakeContainers();

        EnchantedBookService.write(container, "fireball", 2);
        container.set(key(EnchantedBookService.BOOK_LEVEL_KEY), PersistentDataType.STRING, "2");

        assertNull(EnchantedBookService.read(container), "等级必须是整数，字符串形式的 2 不认");
        assertTrue(EnchantedBookService.marked(container));

        container.set(key(EnchantedBookService.BOOK_LEVEL_KEY), PersistentDataType.INTEGER, 0);

        assertNull(EnchantedBookService.read(container), "0 级不是合法附魔等级");
    }

    @Test
    void 非法输入不写任何键() {
        final FakeContainers container = new FakeContainers();

        assertFalse(EnchantedBookService.write(container, "  ", 1), "空白 id 不能写");
        assertFalse(EnchantedBookService.write(container, null, 1));
        assertFalse(EnchantedBookService.write(container, "fireball", 0), "0 级不能写");
        assertFalse(EnchantedBookService.write(null, "fireball", 1));

        assertTrue(container.isEmpty(), "被拒绝的写入不能在物品上留下半个键");
    }

    @Test
    void 没有本插件的键就不是本插件的书() {
        final FakeContainers empty = new FakeContainers();

        assertFalse(EnchantedBookService.marked(empty));
        assertNull(EnchantedBookService.read(empty));

        final FakeContainers other = new FakeContainers();

        // 原版附魔书在 PDC 上有自己的东西（这里是别的 namespace 的键）：照样不认。
        other.set(new NamespacedKey("minecraft", "enchantments"), PersistentDataType.STRING, "x");

        assertFalse(
                EnchantedBookService.marked(other),
                "识别只认本插件自己的键，原版书必须走原版流程"
        );
    }

    @Test
    void 数据格式常量属于已发布约定() {
        // 这两个键会出现在玩家手上的物品里，改名等于让发出去的旧书全部失效。
        assertEquals("book_enchant", EnchantedBookService.BOOK_ID_KEY);
        assertEquals("book_level", EnchantedBookService.BOOK_LEVEL_KEY);
        assertEquals("jsenchants", EnchantApplier.DATA_NAMESPACE);
        assertNotEquals(EnchantApplier.LORE_LINES_KEY, EnchantedBookService.BOOK_ID_KEY);
        assertNotEquals(EnchantApplier.LEVEL_PREFIX, EnchantedBookService.BOOK_ID_KEY);
    }

    @Test
    void 展示名沿用配置里的_lore_format() {
        final EnchantedBookService.Presentation shown =
                EnchantedBookService.presentation(definition(3, Set.of()), 2, snapshot(true), PALETTE);

        assertEquals("Fireball 2", PLAIN.serialize(shown.name()), "名称与装备 Lore 行是同一套模板");
    }

    @Test
    void 适用物品留空时说明任意物品并给出等级区间() {
        final EnchantedBookService.Presentation shown =
                EnchantedBookService.presentation(definition(3, Set.of()), 2, snapshot(true), PALETTE);

        final List<Component> lore = shown.lore();

        assertEquals(2, lore.size(), "两行：适用物品 + 等级区间");
        assertEquals("适用物品：任意物品", PLAIN.serialize(lore.get(0)));
        assertEquals("等级：2 / 3", PLAIN.serialize(lore.get(1)));
    }

    @Test
    void 关闭_lore_时不写说明行() {
        final EnchantedBookService.Presentation shown =
                EnchantedBookService.presentation(definition(3, Set.of()), 1, snapshot(false), PALETTE);

        assertEquals("Fireball 1", PLAIN.serialize(shown.name()), "名字仍要能看出是什么书");
        assertTrue(
                shown.lore().isEmpty(),
                "lore.enabled=false 是服主对 Lore 的全局开关，书也不该绕过（空表示不写 Lore）"
        );
    }

    @Test
    void 书名按稀有度取色但文本形态不变() {
        // 书与装备共用同一份模板文本：只有颜色跟着稀有度走。
        final Component common = name(definition(EnchantRarity.COMMON));
        final Component epic = name(definition(EnchantRarity.EPIC));

        assertEquals("Fireball 2", PLAIN.serialize(common), "普通稀有度的书名");
        assertEquals("Fireball 2", PLAIN.serialize(epic), "史诗稀有度只换颜色，文本必须一字不差");
        assertNotEquals(common.color(), epic.color(), "不同稀有度的书必须是不同颜色，否则分色等于没做");
    }

    @Test
    void 书等级双读取较大值() {
        final EnchantedBookService.BookData data = new EnchantedBookService.BookData("fireball", 2);

        assertEquals(3, EnchantedBookService.withStored(data, 3).level(), "组件上更高时以组件为准");
        assertEquals(2, EnchantedBookService.withStored(data, 1).level(), "组件上更低时仍以 PDC 为准");
        assertEquals(2, EnchantedBookService.withStored(data, 0).level(), "没有组件就是 PDC 的值");
        assertNull(EnchantedBookService.withStored(null, 3), "数据本身读不出来时不能凭组件造出一本书");
    }
}
