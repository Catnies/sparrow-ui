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
    private final WeakReference<H> host;
    private final long startTick;
    private final long totalTicks;                  // 播放开始时从描述读定的总时长, 负数表示无限
    private volatile Subscription clock;            // 帧推进的时钟订阅凭证, 由播放自己持有, 结束时解绑
    private volatile FinishReason finishReason;     // 有值即已结束, 写入由锁保护
    private List<Consumer<FinishReason>> callbacks; // 等待结束的回调, 由锁保护, 结束时与终态一起整批取走并置 null, 之后注册的改为当场触发

    protected ActivePlayback(@NotNull H host, long startTick, long totalTicks) {
        this.host = new WeakReference<>(host);
        this.startTick = startTick;
        this.totalTicks = totalTicks;
    }

    // 播放入场后挂上帧推进时钟. 凭证由播放自己持有, 宿主在场时经动画通道强持播放, 宿主消亡后时钟在下一拍自行解绑.
    public final void startClock(@NotNull Signal<Long> clock) {
        Subscription subscription = clock.onDirty(this::onTick);
        this.clock = subscription;
        // 挂钟与并发终结(如关窗)竞争时, 晚到的一方负责把钟收掉
        if (this.finishReason != null) {
            subscription.close();
        }
    }

    // 周期时钟回调, 到点自然结束, 未到点推进帧显示.
    // 宿主已被回收时自我解绑, 不再钉住 tick 源.
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

    // 帧推进动作, 让被盖住的显示按当前帧重新求值.
    protected abstract void advanceFrame(@NotNull H host);

    @Override
    public final void cancel() {
        this.finish(FinishReason.CANCELLED);
    }

    // 以给定原因结束, 负责摘层与回调.
    public final void finish(@NotNull FinishReason reason) {
        List<Consumer<FinishReason>> pending;
        synchronized (this) {
            if (this.finishReason != null) return;
            this.finishReason = reason;
            pending = this.callbacks;
            this.callbacks = null;
        }
        // 先停钟摘层再回调, 回调运行时被盖的显示已经恢复
        Subscription clock = this.clock;
        if (clock != null) {
            clock.close();
        }
        H host = this.host.get();
        if (host != null) {
            this.detach(host);
        }
        // 某个回调抛异常也照样触发剩下的, 攒起来交给终结方抛
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

    // 把自己从宿主的动画通道移除并恢复被盖住的显示, 只在宿主仍存活时被调用.
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
        // 回调放到锁外跑, 用户代码不该攥着实例锁
        callback.accept(finished);
    }

    protected final long startTick() {
        return this.startTick;
    }
}
