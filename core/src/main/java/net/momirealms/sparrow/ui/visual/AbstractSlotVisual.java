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

/**
 * 全部按槽位组织的视觉宿主共用的实现: 两层视觉映射, 空槽背景, 以及按槽位的失效路由.
 * <p>配置写在一份不可变的 {@link State} 里, 每次修改整体替换, 读取不加锁.
 * 空槽背景这一层由子类的接口决定要不要对外开放; 不开放的宿主它始终缺席, 求值时自然跳过.
 */
@ApiStatus.Internal
public abstract class AbstractSlotVisual extends AbstractVisual implements SlotVisual {
    private final Object stateLock = new Object();
    private final VisualDirtyRoutes dirtyRoutes;
    private volatile State state;

    /**
     * 建一个没有任何配置的视觉宿主.
     *
     * @param signalBindings 宿主持有的 Signal 绑定, 决定 bind 的订阅寿命
     * @param size 槽位数量, 建成后固定不变
     */
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

    /**
     * 返回当前空槽背景.
     *
     * @return 空槽背景; 没有设置过时为 {@code null}
     */
    @Nullable
    public final ItemProvider background() {
        return this.state.background;
    }

    /**
     * 替换空槽背景并标脏全部槽位.
     *
     * @param background 空槽背景, {@code null} 表示清除
     */
    public final void background(@Nullable ItemProvider background) {
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(current.global, current.bySlot, background);
        }
        this.dirty();
    }

    /**
     * 求值这个宿主的全部层: 逐槽层, 全局层, 空槽背景. 上层放行才轮到下层.
     *
     * @param slot 宿主槽位
     * @param actual 该槽当前的同步可读内容, 没有内容为 null
     * @return 求值结果; 所有层都缺席或放行时为 null
     */
    @Nullable
    public final ResolvedVisual visualize(int slot, @Nullable ItemStack actual) {
        State current = this.state;
        ResolvedVisual bound = overlay(current, slot, actual);
        if (bound != null) {
            return bound;
        }
        return actual == null && current.background != null ? ResolvedVisual.of(current.background) : null;
    }

    /**
     * 只求值两层视觉映射, 不含空槽背景.
     * <p>背景是"这一格没内容时显示什么"的替补, 只在内容所在的那个宿主生效, 因此覆盖别人的宿主用这个入口.
     *
     * @param slot 宿主槽位
     * @param actual 该槽当前的同步可读内容, 没有内容为 null
     * @return 求值结果; 两层都缺席或放行时为 null
     */
    @Nullable
    public final ResolvedVisual visualizeOverlay(int slot, @Nullable ItemStack actual) {
        return overlay(this.state, slot, actual);
    }

    /**
     * 挂一条这个槽位的视觉失效路由.
     * <p>路由只弱持有回执, 调用方丢掉回执即等于退订.
     *
     * @param slot 宿主槽位
     * @param invalidator 该槽视觉配置变化时要跑的通知
     * @return 可用于提前退订的回执
     */
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
