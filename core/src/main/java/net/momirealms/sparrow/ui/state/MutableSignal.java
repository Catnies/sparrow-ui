package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.UnaryOperator;

public sealed interface MutableSignal<T> extends Signal<T> permits MutableSignalImpl {

    /**
     * 写入新值, 若与旧值相同则静默跳过, 不产生失效.
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
