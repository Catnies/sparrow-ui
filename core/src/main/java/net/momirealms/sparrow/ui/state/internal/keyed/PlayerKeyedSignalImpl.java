package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.internal.AsyncSignalImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;

@ApiStatus.Internal
public final class PlayerKeyedSignalImpl<T> extends AsyncKeyedSignalImpl<UUID, T> implements PlayerKeyedSignal<T> {

    public PlayerKeyedSignalImpl(T placeholder, Executor executor, Function<? super UUID, ? extends T> loader, BiPredicate<? super T, ? super T> sameValue, @Nullable AsyncSignalImpl.Polling polling) {
        super(placeholder, executor, loader, sameValue, polling);
        // 通用基类已经完成构造且本类没有额外状态, 注册表可以在这里发布 this.
        PlayerSignalRegistry.track(this);
    }
}
