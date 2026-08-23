package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * 可写的分区句柄, 写入原样转发给 owner 上同一个 key 的写入方法.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
final class MutablePartitionHandle<K, T> extends PartitionHandle<K, T> implements MutableSignal<T> {
    private final KeyedSignalImpl<K, T> owner;

    MutablePartitionHandle(KeyedSignalImpl<K, T> owner, K key) {
        super(owner, key);
        this.owner = owner;
    }

    @Override
    public void set(T value) {
        this.owner.set(this.key(), value);
    }

    @Override
    public void update(@NotNull UnaryOperator<T> updater) {
        this.owner.update(this.key(), updater);
    }
}