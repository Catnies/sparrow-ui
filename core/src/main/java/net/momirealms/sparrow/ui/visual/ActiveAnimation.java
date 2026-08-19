package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// 一次播放的运行时, 求值时作为稳定的来源身份, 结束时从宿主摘除并触发回调.
final class ActiveAnimation implements AnimationHandle {
    static final ActiveAnimation[] NONE = new ActiveAnimation[0];
    // 空槽位序列没有可见过程, 全部共用这一个已经播完的句柄.
    static final AnimationHandle FINISHED_EMPTY = new AnimationHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public void whenFinished(@NotNull Consumer<FinishReason> callback) {
            callback.accept(FinishReason.COMPLETED);
        }
    };

    private final WeakReference<AbstractSlotVisual> host;
    private final AnimationDefinition animationDefinition;
    final int[] slots;               // 播放开始时从描述读出的槽位, 摘除时逐槽标脏
    private final int[] orderBySlot; // 宿主槽位 -> orderIndex, -1 表示不参与
    private final long startTick;
    private FinishReason finishReason;              // 有值即已结束, 由锁保护
    private List<Consumer<FinishReason>> callbacks; // 等待结束的回调, 由锁保护, 结束时与终态一起整批取走并置 null, 之后注册的改为当场触发

    public ActiveAnimation(@NotNull AbstractSlotVisual host, @NotNull AnimationDefinition animationDefinition, int @NotNull [] slots, int @NotNull [] orderBySlot, long startTick) {
        this.host = new WeakReference<>(host);
        this.animationDefinition = animationDefinition;
        this.slots = slots;
        this.orderBySlot = orderBySlot;
        this.startTick = startTick;
    }

    // 求值一个槽位此刻的显示, 槽位不参与或帧放行时返回 null.
    @Nullable
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual, long nowTick) {
        int orderIndex = this.orderBySlot[slot];
        if (orderIndex < 0) return null;
        ItemProvider frame = this.animationDefinition.frame(orderIndex, slot, nowTick - this.startTick, actual);
        return frame == null ? null : new ResolvedVisual(this, frame, null);
    }

    @Override
    public void cancel() {
        this.finish(FinishReason.CANCELLED);
    }

    // 以给定原因结束, 负责摘层与回调.
    void finish(@NotNull FinishReason reason) {
        List<Consumer<FinishReason>> pending;
        synchronized (this) {
            if (this.finishReason != null) return;
            this.finishReason = reason;
            pending = this.callbacks;
            this.callbacks = null;
        }
        // 先摘层再回调, 回调运行时被盖的槽位已经恢复显示
        AbstractSlotVisual host = this.host.get();
        if (host != null) {
            host.removeAnimation(this);
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

    @Override
    public void whenFinished(@NotNull Consumer<FinishReason> callback) {
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
}
