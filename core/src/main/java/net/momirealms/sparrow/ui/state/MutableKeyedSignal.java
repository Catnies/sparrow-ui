package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

public interface MutableKeyedSignal<K, T> extends KeyedSignal<K, T> {

    /**
     * 返回指定分区的句柄, 语义同 {@link KeyedSignal#at}, 另外可以经它写入这个分区.
     * <p><strong>给已经被驱逐的 key 写入会把分区重新建出来</strong>, 它要等下一次驱逐才会消失.
     * 定时任务与采样回调里不要给可能已经离线的玩家写入.
     *
     * @param key 分区 key
     * @return 可写的分区句柄
     */
    @Override
    @NotNull
    MutableSignal<T> at(@NotNull K key);

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