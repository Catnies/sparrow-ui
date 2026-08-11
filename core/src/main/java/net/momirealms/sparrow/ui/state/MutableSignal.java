package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 可写的响应式数据源.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface MutableSignal<T> extends Signal<T> permits MutableSignalImpl {

    /**
     * 写入新值.
     * <p>与当前值 {@link Objects#equals} 相等时静默跳过, 不产生失效.
     *
     * @param value 新值, 允许为 {@code null}
     */
    void set(T value);

    /**
     * 基于当前值原子更新.
     *
     * @param updater 纯函数
     */
    void update(@NotNull UnaryOperator<T> updater);
}
