package com.linmumua.jsenchant.enchant;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 无服务端的 {@link PersistentDataContainer} 替身。
 *
 * <p>为什么不用 {@code ItemMeta} 的代理：附魔书的数据本来就只在 PDC 上，而 {@code ItemMeta}
 * 的方法签名引用了 {@code net.md_5.bungee} 的类型（服务端才提供），代理会在构造时炸掉。
 * 所以被测代码在 PDC 这一层就是纯函数，替身也只需要实现这一层。
 *
 * <p>与 Bukkit 一致的两点：同一个键覆盖写；键相同但类型不同视为读不到
 * （本插件正是靠这一点识别「等级被写成字符串」这类坏数据）。
 */
final class FakeContainers implements PersistentDataContainer {

    private final Map<NamespacedKey, Object[]> entries = new LinkedHashMap<>();

    static NamespacedKey key(final String name) {
        return new NamespacedKey(EnchantApplier.DATA_NAMESPACE, name);
    }

    @Override
    public <P, C> void set(final NamespacedKey key, final PersistentDataType<P, C> type, final C value) {
        this.entries.put(key, new Object[]{type, value});
    }

    @Override
    public void remove(final NamespacedKey key) {
        this.entries.remove(key);
    }

    @Override
    public void readFromBytes(final byte[] bytes, final boolean clear) {
        throw new UnsupportedOperationException("替身不支持字节读取");
    }

    @Override
    public <P, C> boolean has(final NamespacedKey key, final PersistentDataType<P, C> type) {
        return null != this.get(key, type);
    }

    @Override
    public boolean has(final NamespacedKey key) {
        return this.entries.containsKey(key);
    }

    @Override
    public <P, C> C get(final NamespacedKey key, final PersistentDataType<P, C> type) {
        final Object[] entry = this.entries.get(key);

        if (null == entry || entry[0] != type) {
            return null;
        }

        return type.getComplexType().cast(entry[1]);
    }

    @Override
    public <P, C> C getOrDefault(
            final NamespacedKey key,
            final PersistentDataType<P, C> type,
            final C fallbackValue
    ) {
        final C value = this.get(key, type);

        return null == value ? fallbackValue : value;
    }

    @Override
    public Set<NamespacedKey> getKeys() {
        return Set.copyOf(this.entries.keySet());
    }

    @Override
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    @Override
    public void copyTo(final PersistentDataContainer target, final boolean replace) {
        for (final Map.Entry<NamespacedKey, Object[]> entry : this.entries.entrySet()) {
            if (!replace && target.has(entry.getKey())) {
                continue;
            }

            copyOne(entry.getKey(), entry.getValue(), target);
        }
    }

    @Override
    public PersistentDataAdapterContext getAdapterContext() {
        throw new UnsupportedOperationException("替身不支持序列化上下文");
    }

    @Override
    public byte[] serializeToBytes() {
        throw new UnsupportedOperationException("替身不支持序列化");
    }

    @Override
    public int getSize() {
        return this.entries.size();
    }

    private static <P, C> void copyOne(
            final NamespacedKey key,
            final Object[] entry,
            final PersistentDataContainer target
    ) {
        @SuppressWarnings("unchecked")
        final PersistentDataType<P, C> type = (PersistentDataType<P, C>) entry[0];
        @SuppressWarnings("unchecked")
        final C value = (C) entry[1];

        target.set(key, type, value);
    }
}
