package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@ApiStatus.Internal
public abstract class ActivePlayback<H> implements AnimationHandle {
    // 还没开始就已经结束的播放(比如槽位序列是空的), 取消它是空操作, 回调立刻收到 COMPLETED
    public static final AnimationHandle FINISHED = new AnimationHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public void whenFinished(@NotNull Consumer<FinishReason> callback) {
            callback.accept(FinishReason.COMPLETED);
        }
    };

    private final WeakReference<H> host;
    private final long startTick;
    private final long totalTicks;                  // 开播时从描述读定的总时长, 负数表示无限播
    private volatile Subscription clock;            // 帧推进的订阅凭证, 播放自己拿着它, 结束时解绑
    private volatile FinishReason finishReason;     // 有值就说明已经结束, 赋值在 this 锁里做
    private List<Consumer<FinishReason>> callbacks; // 等着结束的回调; 结束时连同终态整批取走并置 null, 之后注册的就当场触发

    protected ActivePlayback(@NotNull H host, long startTick, long totalTicks) {
        this.host = new WeakReference<>(host);
        this.startTick = startTick;
        this.totalTicks = totalTicks;
    }

    // 挂上时钟; 播放弱持有宿主, 宿主被回收之后时钟在下一拍自己解绑
    public final void startClock(@NotNull Signal<Long> clock) {
        Subscription subscription = clock.onDirty(this::onTick);
        this.clock = subscription;
        // 挂钟和结束撞在一起时, 晚到的那一方负责把订阅关掉
        if (this.finishReason != null) {
            subscription.close();
        }
    }

    // 每一拍: 该结束就结束, 宿主已经不在了就停钟, 否则推进一帧
    private void onTick() {
        H host = this.host.get();
        if (host == null || this.finishReason != null) {
            Subscription clock = this.clock;
            if (clock != null) {
                clock.close();
            }
            return;
        }
        if (this.totalTicks >= 0 && Signals.ticking().get() - this.startTick >= this.totalTicks) {
            this.finish(FinishReason.COMPLETED);
            return;
        }
        this.advanceFrame(host);
    }

    // 换帧: 把被这次播放盖住的显示标脏, 让它们按当前帧重算.
    protected abstract void advanceFrame(@NotNull H host);

    @Override
    public final void cancel() {
        this.finish(FinishReason.CANCELLED);
    }

    // 结束只能发生一次, 顺序固定为停钟, 摘层, 回调
    public final void finish(@NotNull FinishReason reason) {
        List<Consumer<FinishReason>> pending;
        synchronized (this) {
            if (this.finishReason != null) return;
            this.finishReason = reason;
            pending = this.callbacks;
            this.callbacks = null;
        }
        // 先把钟停掉, 再把层摘掉, 回调跑的时候被盖住的显示已经恢复
        Subscription clock = this.clock;
        if (clock != null) {
            clock.close();
        }
        H host = this.host.get();
        if (host != null) {
            this.detach(host);
        }
        // 一个回调抛了也要把剩下的叫完, 异常攒着最后一起抛
        if (pending != null) {
            RuntimeException failure = null;
            for (int index = 0; index < pending.size(); index++) {
                try {
                    pending.get(index).accept(reason);
                } catch (RuntimeException exception) {
                    failure = ThrowableUtils.combine(failure, exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    // 把自己从宿主的动画通道里摘掉, 只会在宿主还活着的时候被调用.
    protected abstract void detach(@NotNull H host);

    @Override
    public final void whenFinished(@NotNull Consumer<FinishReason> callback) {
        FinishReason finished;
        synchronized (this) {
            if (this.finishReason == null) {
                if (this.callbacks == null) {
                    this.callbacks = new ArrayList<>(2);
                }
                this.callbacks.add(callback);
                return;
            }
            finished = this.finishReason;
        }
        // 已经结束了的话就在锁外当场回调
        callback.accept(finished);
    }

    protected final long startTick() {
        return this.startTick;
    }
}
