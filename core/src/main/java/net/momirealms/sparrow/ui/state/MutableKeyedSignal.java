package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * 可写的分区响应式数据源.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型, 允许为 {@code null}
 */
public interface MutableKeyedSignal<K, T> extends KeyedSignal<K, T> {

    /**
     * 写入指定分区的新值.
     *
     * @param key 分区 key
     * @param value 新值, 允许为 {@code null}
     */
    void set(@NotNull K key, T value);

    /**
     * 基于指定分区的当前值原子更新.
     *
     * @param key 分区 key
     * @param updater 纯函数
     */
    void update(@NotNull K key, @NotNull UnaryOperator<T> updater);
}
