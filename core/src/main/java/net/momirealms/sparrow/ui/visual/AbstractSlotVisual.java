package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.Signals;
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
    private final Object stateLock = new Object();          // 只保护 State 替换, 标脏一律出了锁再做
    private final VisualDirtyAttachments dirtyAttachments;  // 按槽位的失效订阅表, 槽位数量建成后固定不变
    private volatile State state;                           // 两层映射与播放中的动画整体存于不可变 State, 修改即整体替换, 读取无锁

    protected AbstractSlotVisual(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings);
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
            // 同一份配置重设不算换来源, 否则会白白作废在飞的异步结果
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
        if (slots.length == 0) {
            return ActiveAnimation.FINISHED_EMPTY;
        }
        // 预排宿主槽位到 orderIndex 的查找表, 求值热路径按它 O(1) 判定参与
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
        ActiveAnimation playing = new ActiveAnimation(this, animationDefinition, slots, orderBySlot, Signals.ticking().get());
        synchronized (this.stateLock) {
            State current = this.state;
            ActiveAnimation[] animations = Arrays.copyOf(current.animations, current.animations.length + 1);
            animations[current.animations.length] = playing;
            this.state = new State(current.global, current.bySlot, animations);
        }
        // 入场即盖住参与的槽位
        for (int index = 0; index < slots.length; index++) {
            this.dirtyAttachments.dirty(slots[index]);
        }
        return playing;
    }

    // 摘除一次播放并恢复它盖住的槽位, 已经不在场时静默返回.
    final void removeAnimation(@NotNull ActiveAnimation animation) {
        synchronized (this.stateLock) {
            State current = this.state;
            int index = indexOf(current.animations, animation);
            if (index < 0) return;
            ActiveAnimation[] animations;
            if (current.animations.length == 1) {
                animations = ActiveAnimation.NONE;
            } else {
                animations = new ActiveAnimation[current.animations.length - 1];
                System.arraycopy(current.animations, 0, animations, 0, index);
                System.arraycopy(current.animations, index + 1, animations, index, current.animations.length - index - 1);
            }
            this.state = new State(current.global, current.bySlot, animations);
        }
        int[] slots = animation.slots;
        for (int index = 0; index < slots.length; index++) {
            this.dirtyAttachments.dirty(slots[index]);
        }
    }

    private static int indexOf(ActiveAnimation @NotNull [] animations, @NotNull ActiveAnimation animation) {
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
        // 播放中的动画最优先, 后开始的盖住先开始的, 帧放行时逐层下落
        ActiveAnimation[] animations = current.animations;
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
        @NotNull private final ActiveAnimation @NotNull [] animations; // 按开始序排列, 求值时从末尾往前看

        private State(@NotNull VisualLayer global, @NotNull VisualLayer @NotNull [] bySlot, @NotNull ActiveAnimation @NotNull [] animations) {
            this.global = global;
            this.bySlot = bySlot;
            this.animations = animations;
        }

        @NotNull
        private static State empty(int size) {
            VisualLayer[] bySlot = new VisualLayer[size];
            Arrays.fill(bySlot, VisualLayer.NONE);
            return new State(VisualLayer.NONE, bySlot, ActiveAnimation.NONE);
        }
    }
}
