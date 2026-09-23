package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.state.MutablePlayerKeyedSignal;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

@ApiStatus.Internal
public final class MutablePlayerKeyedSignalImpl<T> extends KeyedSignalImpl<UUID, T> implements MutablePlayerKeyedSignal<T> {

    public MutablePlayerKeyedSignalImpl(Function<? super UUID, ? extends T> initial, BiPredicate<? super T, ? super T> sameValue) {
        super(initial, sameValue);
        // 通用基类已经完成构造且本类没有额外状态, 注册表可以在这里发布 this.
        SparrowUI.getInstance().playerSignals().track(this);
    }
}
