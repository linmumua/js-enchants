package com.linmumua.jsenchant.enchant;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.Locale;

/**
 * 原版附魔注册表的只读桥。
 *
 * <p><b>只读，不注册</b>：注册由数据包完成（见 {@link EnchantDatapackWriter}），
 * {@code org.bukkit.Registry} 本身也不允许插件写入。
 *
 * <p>这个类存在的意义只有一个：让「要不要写原版附魔组件」这件事有一个统一的判断入口。
 * 数据包只在世界加载时生效，插件 enable 时注册表里还没有我们的附魔；此时
 * {@link #enchantment(String)} 返回 {@code null}，调用方就必须退化为纯 PDC 存储——
 * 否则 {@code ItemMeta#addEnchant} 会抛异常或写入一个查不到的 id，物品直接坏掉。
 */
public final class VanillaEnchantBridge {

    /** 附魔命名空间，与 PDC namespace、数据包 id 一致。 */
    public static final String NAMESPACE = "jsenchants";

    /**
     * 用来探测注册表是否可用的原版附魔。
     *
     * <p>选它是因为它一定存在、而且永远不可能被本插件占用；能读到它就说明
     * {@code Registry.ENCHANTMENT} 已经初始化完成。
     */
    private static final NamespacedKey PROBE = new NamespacedKey("minecraft", "sharpness");

    private VanillaEnchantBridge() {
    }

    /**
     * 附魔 id 对应的注册表键，形如 {@code jsenchants:fireball}。
     *
     * <p>id 一律小写：{@link NamespacedKey} 不接受大写，而 yml 里的 id 已经过校验，
     * 这里再收一次口是为了让脚本侧/命令侧拼出来的 id 也能用。
     */
    public static NamespacedKey key(final String id) {
        if (null == id || id.isBlank()) {
            throw new IllegalArgumentException("附魔 id 不能为空");
        }

        return new NamespacedKey(NAMESPACE, id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 注册表是否可用（服务端已初始化附魔注册表）。
     *
     * <p>单测与服务端未就绪的早期阶段都会走 {@code false} 分支，因此这里吞掉一切
     * 探测异常——探测本身不该成为新的故障源。
     */
    public static boolean available() {
        try {
            return null != Registry.ENCHANTMENT.get(PROBE);
        } catch (final Throwable unavailable) {
            return false;
        }
    }

    /**
     * 从注册表解析本插件的附魔。
     *
     * @return 注册表中不存在（数据包未加载、附魔已下线或注册表不可用）时返回 {@code null}——
     *         调用方据此判断「不要写原版附魔组件」
     */
    public static Enchantment enchantment(final String id) {
        if (null == id || id.isBlank()) {
            return null;
        }

        try {
            return Registry.ENCHANTMENT.get(key(id));
        } catch (final Throwable unavailable) {
            return null;
        }
    }

    /**
     * 该附魔是否已在注册表里生效（等价于 {@code null != enchantment(id)}）。
     */
    public static boolean registered(final String id) {
        return null != enchantment(id);
    }

    /**
     * 反查：物品上的这个原版附魔是不是本插件的。
     *
     * @return 是则返回插件侧 id（不含命名空间），否则（原版附魔、{@code null}）返回 {@code null}
     */
    public static String idOf(final Enchantment enchantment) {
        if (null == enchantment) {
            return null;
        }

        try {
            return idIn(enchantment.getKey());
        } catch (final RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * 键名反查的纯函数部分：只有本插件命名空间下的键才算我们的。
     *
     * @return 属于 {@link #NAMESPACE} 时返回 key 部分，否则返回 {@code null}
     */
    static String idIn(final NamespacedKey key) {
        if (null == key || !NAMESPACE.equals(key.getNamespace())) {
            return null;
        }

        return key.getKey();
    }
}
