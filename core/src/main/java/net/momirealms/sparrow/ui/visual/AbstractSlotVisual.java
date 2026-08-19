package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

@ApiStatus.Internal
public abstract class AbstractSlotVisual extends AbstractVisual implements SlotVisual {
    private final Object stateLock = new Object();
    private final VisualDirtyAttachments dirtyAttachments;  // 按槽位的失效订阅表, 槽位数量建成后固定不变
    private volatile State state;                           // 两层映射整体存于不可变 State, 修改即整体替换, 读取无锁

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
            this.state = new State(new VisualLayer(visualizerProvider, placeholder), current.bySlot);
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
            this.state = new State(current.global, bySlot);
        }
        this.dirtyAttachments.dirty(slot);
    }

    @Nullable
    @Override
    public final ResolvedVisual visualize(int slot, @Nullable ItemStack actual) {
        State current = this.state;
        Objects.checkIndex(slot, current.bySlot.length);
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

        private State(@NotNull VisualLayer global, @NotNull VisualLayer @NotNull [] bySlot) {
            this.global = global;
            this.bySlot = bySlot;
        }

        @NotNull
        private static State empty(int size) {
            VisualLayer[] bySlot = new VisualLayer[size];
            Arrays.fill(bySlot, VisualLayer.NONE);
            return new State(VisualLayer.NONE, bySlot);
        }
    }
}
