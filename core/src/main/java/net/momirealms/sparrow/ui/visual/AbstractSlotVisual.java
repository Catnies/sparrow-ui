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
    private final Object stateLock = new Object();          // 护住 State 的替换和终结标记; 标脏一律放到锁外
    private final VisualDirtyAttachments dirtyAttachments;  // 按槽位分的失效订阅表; 槽位数建好就固定不变
    private volatile State state;                           // 两层映射加上正在播的动画, 整体放在一个不可变 State 里; 改就整份换, 读不加锁
    private AnimationHandle.FinishReason finishing;         // 通道正在以这个原因整体终结; 有值期间新播放进不了场, 由 stateLock 保护

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
            // 配置身份没变就照旧, 已经算出来的异步结果可以接着用
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
            // 只换这一格的那一层, 别的槽位和动画那份数组照旧复用
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
        // 没有槽位, 或者总时长是 0, 这种播放一出生就到点, 直接给个完成句柄
        if (slots.length == 0 || animationDefinition.totalTicks() == 0) {
            return ActivePlayback.FINISHED;
        }
        long periodTicks = animationDefinition.periodTicks();
        Signal<Long> clock = Signals.everyTicks(periodTicks);
        // 先把 槽位 -> 动画序号 的查找表排好, 之后求帧按槽位直接定位, 不用每次去数组里翻
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
        // 起播时刻对齐到周期的共享节拍, 同周期的动画于是同一拍换帧
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
        // 通道正在整体终结时, 新播放不进通道, 当场以同一个原因结束; 句柄的结束回调照样恰好来一次
        if (finishing != null) {
            playing.finish(finishing);
            return playing;
        }
        // 一进场就把参与的槽位标脏, 它们立刻显示动画的帧
        this.dirtyAnimated(slots);
        try {
            playing.startClock(clock);
        } catch (RuntimeException exception) {
            // 挂钟失败就把进场这一步撤回来, 别留下一条没有时钟的播放
            this.removeAnimation(playing);
            throw exception;
        }
        return playing;
    }

    // 摘掉这次播放, 顺手把它盖住的槽位标脏, 恢复成下面的层
    final void removeAnimation(@NotNull ActiveSlotAnimation animation) {
        synchronized (this.stateLock) {
            State current = this.state;
            int index = indexOf(current.animations, animation);
            // 找不到说明它早就摘过了
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

    // 按给定原因结束所有在播动画.
    // 终结期间入场的播放会当场以同一个原因结束, 不进通道: 否则结束回调里接着播的那一段又会让通道活过来.
    // 某个结束回调抛了也继续终结其余的. 外层已经在 beginFinishing 阶段里时, 开合的账归外层管.
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

    // 进入整体终结阶段, 期间新播放当场以这个原因结束.
    // 宿主想把这段时间拉长到多次批量终结之外时用它, 必须和 endFinishing 配对.
    @ApiStatus.Internal
    public final void beginFinishing(@NotNull AnimationHandle.FinishReason reason) {
        synchronized (this.stateLock) {
            this.finishing = reason;
        }
    }

    // 新播放重新可以进场.
    @ApiStatus.Internal
    public final void endFinishing() {
        synchronized (this.stateLock) {
            this.finishing = null;
        }
    }

    // 换帧和摘层都用这一份逐槽标脏, 走的是和改配置同一条失效路径.
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
        // 后开始的盖住先开始的; 某一层放行就继续往前找, 找完动画才轮到逐槽和全局映射
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

    // 订阅表那本账在 dirtyAttachments 上, 这里先把越界槽号挡掉
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

    // 一次配置快照: 两层映射和动画通道都在里面, 换任何一样都是整份替换
    private static final class State {
        @NotNull private final VisualLayer global;
        @NotNull private final VisualLayer @NotNull [] bySlot;
        @NotNull private final ActiveSlotAnimation @NotNull [] animations; // 按开始顺序排: 越靠后开始得越晚, 求值从后往前找

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
