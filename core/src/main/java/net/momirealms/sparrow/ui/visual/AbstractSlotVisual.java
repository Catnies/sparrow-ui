package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.visual.animation.ActivePlayback;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

@ApiStatus.Internal
public abstract class AbstractSlotVisual extends AbstractVisual implements SlotVisual {
    private final Object stateLock = new Object();          // 只保护 State 替换与终结闸门, 标脏一律出了锁再做
    private final VisualDirtyAttachments dirtyAttachments;  // 按槽位的失效订阅表, 槽位数量建成后固定不变
    private volatile State state;                           // 两层映射与播放中的动画整体存于不可变 State, 修改即整体替换, 读取无锁
    private AnimationHandle.FinishReason finishing;         // 通道正在以这个原因整体终结, 非 null 期间新播放不入场; 由 stateLock 保护

    protected AbstractSlotVisual(@NotNull Bindings bindings, int size) {
        super(bindings);
        this.dirtyAttachments = new VisualDirtyAttachments(size);
        this.state = State.empty(size);
    }

    @Nullable
    @Override
    public final Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.state.global.visualizer();
    }

    @Override
    public final void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        synchronized (this.stateLock) {
            State current = this.state;
            // 配置身份没变就沿用已有异步结果
            if (current.global.isSameVisualizerSamePlaceholder(visualizerProvider, placeholder)) return;
            this.state = new State(new VisualLayer(visualizerProvider, placeholder), current.bySlot, current.animations);
        }
        this.dirty();
    }

    @Nullable
    @Override
    public final Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot) {
        VisualLayer[] bySlot = this.state.bySlot;
        Objects.checkIndex(slot, bySlot.length);
        return bySlot[slot].visualizer();
    }

    @Override
    public final void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        synchronized (this.stateLock) {
            State current = this.state;
            Objects.checkIndex(slot, current.bySlot.length);
            if (current.bySlot[slot].isSameVisualizerSamePlaceholder(visualizerProvider, placeholder)) {
                return;
            }
            VisualLayer[] bySlot = current.bySlot.clone();
            bySlot[slot] = new VisualLayer(visualizerProvider, placeholder);
            this.state = new State(current.global, bySlot, current.animations);
        }
        this.dirtyAttachments.dirty(slot);
    }

    @NotNull
    @Override
    public final AnimationHandle play(@NotNull AnimationDefinition animationDefinition) {
        int size = this.state.bySlot.length;
        int[] slots = animationDefinition.slots();
        // 空槽位或零时长的播放出生即到点, 当场完成
        if (slots.length == 0 || animationDefinition.totalTicks() == 0) {
            return ActivePlayback.FINISHED;
        }
        long periodTicks = animationDefinition.periodTicks();
        Signal<Long> clock = Signals.everyTicks(periodTicks);
        // 预排槽位到 orderIndex 的查找表, 帧求值按槽位直接定位
        int[] orderBySlot = new int[size];
        Arrays.fill(orderBySlot, -1);
        for (int index = 0; index < slots.length; index++) {
            int slot = slots[index];
            Objects.checkIndex(slot, size);
            if (orderBySlot[slot] >= 0) {
                throw new IllegalArgumentException("duplicate slot " + slot);
            }
            orderBySlot[slot] = index;
        }
        // 对齐共享节拍, 让同周期动画同步换帧
        long startTick = Signals.ticking().get() / periodTicks * periodTicks;
        ActiveSlotAnimation playing = new ActiveSlotAnimation(this, animationDefinition, slots, orderBySlot, startTick);
        AnimationHandle.FinishReason finishing;
        synchronized (this.stateLock) {
            finishing = this.finishing;
            if (finishing == null) {
                State current = this.state;
                ActiveSlotAnimation[] animations = Arrays.copyOf(current.animations, current.animations.length + 1);
                animations[current.animations.length] = playing;
                this.state = new State(current.global, current.bySlot, animations);
            }
        }
        // 通道正在整体终结时不再放新播放进场, 当场以同一原因结束, 句柄的结束回调照常恰好触发一次
        if (finishing != null) {
            playing.finish(finishing);
            return playing;
        }
        // 入场即盖住参与的槽位
        this.dirtyAnimated(slots);
        try {
            playing.startClock(clock);
        } catch (RuntimeException exception) {
            // 挂钟失败时撤掉已经入场的动画层
            this.removeAnimation(playing);
            throw exception;
        }
        return playing;
    }

    // 摘除播放并恢复它盖住的槽位
    final void removeAnimation(@NotNull ActiveSlotAnimation animation) {
        synchronized (this.stateLock) {
            State current = this.state;
            int index = indexOf(current.animations, animation);
            if (index < 0) return;
            ActiveSlotAnimation[] animations;
            if (current.animations.length == 1) {
                animations = ActiveSlotAnimation.NONE;
            } else {
                animations = new ActiveSlotAnimation[current.animations.length - 1];
                System.arraycopy(current.animations, 0, animations, 0, index);
                System.arraycopy(current.animations, index + 1, animations, index, current.animations.length - index - 1);
            }
            this.state = new State(current.global, current.bySlot, animations);
        }
        this.dirtyAnimated(animation.slots);
    }

    // 以给定原因结束全部在播动画. 终结期间入场的播放当场以同一原因结束而不进通道, 否则结束回调里的链式续播会让通道死灰复燃.
    // 某个结束回调失败也会继续终结其余动画. 已在 beginFinishing 阶段内时, 闸门的开合由外层负责.
    @ApiStatus.Internal
    public final void finishAnimations(@NotNull AnimationHandle.FinishReason reason) {
        ActiveSlotAnimation[] animations;
        boolean owned;
        synchronized (this.stateLock) {
            owned = this.finishing == null;
            if (owned) {
                this.finishing = reason;
            }
            animations = this.state.animations;
        }
        RuntimeException failure = null;
        try {
            for (int index = 0; index < animations.length; index++) {
                try {
                    animations[index].finish(reason);
                } catch (RuntimeException exception) {
                    failure = ThrowableUtils.combine(failure, exception);
                }
            }
        } finally {
            if (owned) {
                this.endFinishing();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // 进入以给定原因整体终结的阶段, 期间新播放当场以该原因结束. 供宿主把阶段拉长到多次批量终结之外, 必须与 endFinishing 配对.
    @ApiStatus.Internal
    public final void beginFinishing(@NotNull AnimationHandle.FinishReason reason) {
        synchronized (this.stateLock) {
            this.finishing = reason;
        }
    }

    // 退出整体终结阶段, 新播放恢复入场.
    @ApiStatus.Internal
    public final void endFinishing() {
        synchronized (this.stateLock) {
            this.finishing = null;
        }
    }

    // 帧推进与摘层共用的逐槽标脏, 走与配置写入相同的失效路由.
    final void dirtyAnimated(int @NotNull [] slots) {
        for (int index = 0; index < slots.length; index++) {
            this.dirtyAttachments.dirty(slots[index]);
        }
    }

    private static int indexOf(ActiveSlotAnimation @NotNull [] animations, @NotNull ActiveSlotAnimation animation) {
        for (int index = 0; index < animations.length; index++) {
            if (animations[index] == animation) {
                return index;
            }
        }
        return -1;
    }

    @Nullable
    @Override
    public final ResolvedVisual visualize(int slot, @Nullable ItemStack actual) {
        State current = this.state;
        Objects.checkIndex(slot, current.bySlot.length);
        // 后开始的动画优先, 当前帧放行时继续向前找
        ActiveSlotAnimation[] animations = current.animations;
        if (animations.length > 0) {
            long nowTick = Signals.ticking().get();
            for (int index = animations.length - 1; index >= 0; index--) {
                ResolvedVisual playing = animations[index].visualize(slot, actual, nowTick);
                if (playing != null) {
                    return playing;
                }
            }
        }
        ResolvedVisual bound = current.bySlot[slot].visualize(actual);
        return bound != null ? bound : current.global.visualize(actual);
    }

    @NotNull
    @Override
    public final Subscription attach(int slot, @NotNull Runnable invalidator) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        return this.dirtyAttachments.attach(slot, invalidator);
    }

    @Override
    public final void dirty() {
        this.dirtyAttachments.dirtyAll();
    }

    private static final class State {
        @NotNull private final VisualLayer global;
        @NotNull private final VisualLayer @NotNull [] bySlot;
        @NotNull private final ActiveSlotAnimation @NotNull [] animations; // 按开始顺序排列

        private State(@NotNull VisualLayer global, @NotNull VisualLayer @NotNull [] bySlot, @NotNull ActiveSlotAnimation @NotNull [] animations) {
            this.global = global;
            this.bySlot = bySlot;
            this.animations = animations;
        }

        @NotNull
        private static State empty(int size) {
            VisualLayer[] bySlot = new VisualLayer[size];
            Arrays.fill(bySlot, VisualLayer.NONE);
            return new State(VisualLayer.NONE, bySlot, ActiveSlotAnimation.NONE);
        }
    }
}
