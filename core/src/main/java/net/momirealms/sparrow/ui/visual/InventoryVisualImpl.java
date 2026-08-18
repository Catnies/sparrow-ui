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
public final class InventoryVisualImpl extends AbstractVisual implements InventoryVisual {
    private final Object stateLock = new Object();
    private final VisualDirtyRoutes dirtyRoutes;
    private volatile State state;

    public InventoryVisualImpl(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings);
        this.dirtyRoutes = new VisualDirtyRoutes(size);
        this.state = State.empty(size);
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> getVisualizerProvider() {
        return this.state.global.visualizer();
    }

    @Override
    public void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        VisualLayer newLayer = new VisualLayer(visualizerProvider, placeholder);
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(newLayer, current.bySlot, current.background);
        }
        this.dirty();
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> getVisualizerProvider(int slot) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        return this.state.bySlot[slot].visualizer();
    }

    @Override
    public void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        VisualLayer newLayer = new VisualLayer(visualizerProvider, placeholder);
        synchronized (this.stateLock) {
            State current = this.state;
            VisualLayer[] bySlot = current.bySlot.clone();
            bySlot[slot] = newLayer;
            this.state = new State(current.global, bySlot, current.background);
        }
        this.dirtyRoutes.dirty(slot);
    }

    @Nullable
    @Override
    public ItemProvider background() {
        return this.state.background;
    }

    @Override
    public void background(@Nullable ItemProvider background) {
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(current.global, current.bySlot, background);
        }
        this.dirty();
    }

    /**
     * 从高到低逐层求值: 逐槽层, 全局层, 空槽背景. 上层放行才轮到下层.
     *
     * @param slot Inventory 槽位
     * @param actual 该槽当前真实内容, 空槽为 null
     * @return 求值结果; 所有层都缺席或放行时为 null, 表示按真实内容显示
     */
    @Nullable
    public ResolvedVisual visualize(int slot, @Nullable ItemStack actual) {
        State current = this.state;
        Objects.checkIndex(slot, current.bySlot.length);
        ResolvedVisual bound = current.bySlot[slot].visualize(actual);
        if (bound != null) {
            return bound;
        }
        bound = current.global.visualize(actual);
        if (bound != null) {
            return bound;
        }
        return actual == null && current.background != null ? ResolvedVisual.of(current.background) : null;
    }

    @NotNull
    public Subscription attach(int slot, @NotNull Runnable invalidator) {
        return this.dirtyRoutes.attach(slot, invalidator);
    }

    @Override
    public void dirty() {
        this.dirtyRoutes.dirtyAll();
    }

    private static final class State {
        @NotNull private final VisualLayer global;
        @NotNull private final VisualLayer @NotNull [] bySlot;
        @Nullable private final ItemProvider background;

        private State(
                @NotNull VisualLayer global,
                @NotNull VisualLayer @NotNull [] bySlot,
                @Nullable ItemProvider background
        ) {
            this.global = global;
            this.bySlot = bySlot;
            this.background = background;
        }

        @NotNull
        private static State empty(int size) {
            VisualLayer[] bySlot = new VisualLayer[size];
            Arrays.fill(bySlot, VisualLayer.NONE);
            return new State(VisualLayer.NONE, bySlot, null);
        }
    }
}
