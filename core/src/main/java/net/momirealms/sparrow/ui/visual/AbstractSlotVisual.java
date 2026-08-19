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
    private final VisualDirtyRoutes dirtyRoutes;    // 按槽位的失效路由, 槽位数量建成后固定不变
    private volatile State state;                   // 配置整体存于不可变 State, 修改即整体替换, 读取无锁

    protected AbstractSlotVisual(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings);
        this.dirtyRoutes = new VisualDirtyRoutes(size);
        this.state = State.empty(size);
    }

    @Nullable
    @Override
    public final Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.state.global.visualizer();
    }

    @Override
    public final void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        VisualLayer newLayer = new VisualLayer(visualizerProvider, placeholder);
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(newLayer, current.bySlot, current.background);
        }
        this.dirty();
    }

    @Nullable
    @Override
    public final Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        return this.state.bySlot[slot].visualizer();
    }

    @Override
    public final void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
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

    // 空槽背景是否对外由子类接口决定, 不开放的宿主中它始终为 null, 求值自然跳过.
    @Nullable
    public final ItemProvider background() {
        return this.state.background;
    }

    // 与当前是同一个背景时不做任何事.
    public final void background(@Nullable ItemProvider background) {
        synchronized (this.stateLock) {
            State current = this.state;
            if (current.background == background) {
                return;
            }
            this.state = new State(current.global, current.bySlot, background);
        }
        this.dirty();
    }

    // 逐槽层, 全局层, 空槽背景依次求值(actual 为该槽当前内容, 空为 null), 上层放行才轮到下层.
    @Nullable
    public final ResolvedVisual visualize(int slot, @Nullable ItemStack actual) {
        State current = this.state;
        ResolvedVisual bound = overlay(current, slot, actual);
        if (bound != null) {
            return bound;
        }
        return actual == null && current.background != null ? ResolvedVisual.of(current.background) : null;
    }

    // 只求值两层映射不含背景, 背景只在内容所在宿主生效, 覆盖别人的宿主用这个入口.
    @Nullable
    public final ResolvedVisual visualizeOverlay(int slot, @Nullable ItemStack actual) {
        return overlay(this.state, slot, actual);
    }

    // 挂一条该槽位的失效路由, 路由只弱持有回执, 丢掉回执即退订.
    @NotNull
    public final Subscription attach(int slot, @NotNull Runnable invalidator) {
        return this.dirtyRoutes.attach(slot, invalidator);
    }

    @Override
    public final void dirty() {
        this.dirtyRoutes.dirtyAll();
    }

    @Nullable
    private static ResolvedVisual overlay(@NotNull State state, int slot, @Nullable ItemStack actual) {
        Objects.checkIndex(slot, state.bySlot.length);
        ResolvedVisual bound = state.bySlot[slot].visualize(actual);
        return bound != null ? bound : state.global.visualize(actual);
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
