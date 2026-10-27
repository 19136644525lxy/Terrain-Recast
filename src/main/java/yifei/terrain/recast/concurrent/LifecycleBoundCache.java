package yifei.terrain.recast.concurrent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生命周期绑定的本地缓存。与 RTF 不同——缓存不搞全局后台清理线程，而是由持有者
 * （通常是 {@code CellLookup} 或区块级采样器）在 {@link #close()} 时显式清空，
 * 杜绝"世界已卸载但缓存还在持有过期引用"的内存泄漏。
 *
 * <p>默认上限 {@link #MAX_SIZE_DEFAULT} 条。超过则 {@link #put} 直接丢弃（不淘汰，
 * 保持实现简单）——Cell 采样器粒度是区块级，区块数有限，真实负载不会超限。
 *
 * @param <K> 键（通常是区块坐标 key）
 * @param <V> 值（Cell 区域数据）
 */
public final class LifecycleBoundCache<K, V> implements AutoCloseable {

    public static final int MAX_SIZE_DEFAULT = 4096;

    private final int maxSize;
    private final ConcurrentHashMap<K, V> store;

    public LifecycleBoundCache() {
        this(MAX_SIZE_DEFAULT);
    }

    public LifecycleBoundCache(int maxSize) {
        this.maxSize = maxSize;
        this.store = new ConcurrentHashMap<>(Math.min(maxSize * 2, 16));
    }

    public V get(K key) {
        return store.get(key);
    }

    public V computeIfAbsent(K key, java.util.function.Function<K, V> creator) {
        Objects.requireNonNull(creator);
        V v = store.get(key);
        if (v != null) return v;
        // 简化：超限丢弃，不淘汰。真实负载远低于上限。
        if (store.size() >= maxSize) return creator.apply(key);
        return store.computeIfAbsent(key, creator);
    }

    /** 强制写入，用于已提前计算好的值。 */
    public void put(K key, V value) {
        if (store.size() >= maxSize) return;
        store.put(key, value);
    }

    /** 失效单个条目（区块卸载时调）。 */
    public void invalidate(K key) {
        store.remove(key);
    }

    /** 清空全部。持有者生命周期末尾调。 */
    @Override
    public void close() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }
}
