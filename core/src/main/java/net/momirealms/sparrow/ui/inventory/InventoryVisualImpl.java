package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.visual.AbstractVisual;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.visual.ResolvedVisual;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.visual.InventoryVisual;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

/**
 * 保存一个 Inventory 的视觉配置, Signal 绑定与逐槽显示 attachment.
 */
final class InventoryVisualImpl extends AbstractVisual implements InventoryVisual {
    private final Object stateLock = new Object();
    private final VisualDirtyRoutes dirtyRoutes;
    private volatile State state;

    InventoryVisualImpl(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings);
        this.dirtyRoutes = new VisualDirtyRoutes(size);
        this.state = State.empty(size);
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider() {
        return this.state.global.sync();
    }

    @Override
    public void visualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider) {
        Layer newLayer = new Layer(visualizerProvider, null, null);
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(newLayer, current.bySlot, current.background);
        }
        this.dirty();
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync() {
        return this.state.global.async();
    }

    @Override
    public void visualizerAsync(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync, @Nullable ImmediateItemProvider placeholder) {
        Layer newLayer = new Layer(null, visualizerAsync, placeholder);
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(newLayer, current.bySlot, current.background);
        }
        this.dirty();
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider(int slot) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        return this.state.bySlot[slot].sync();
    }

    @Override
    public void visualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        Layer newLayer = new Layer(visualizerProvider, null, null);
        synchronized (this.stateLock) {
            State current = this.state;
            Layer[] bySlot = current.bySlot.clone();
            bySlot[slot] = newLayer;
            this.state = new State(current.global, bySlot, current.background);
        }
        this.dirtyRoutes.dirty(slot);
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync(int slot) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        return this.state.bySlot[slot].async();
    }

    @Override
    public void visualizerAsync(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync, @Nullable ImmediateItemProvider placeholder) {
        Objects.checkIndex(slot, this.state.bySlot.length);
        Layer newLayer = new Layer(null, visualizerAsync, placeholder);
        synchronized (this.stateLock) {
            State current = this.state;
            Layer[] bySlot = current.bySlot.clone();
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
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual) {
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
    Subscription attach(int slot, @NotNull Runnable invalidator) {
        return this.dirtyRoutes.attach(slot, invalidator);
    }

    @Override
    public void dirty() {
        this.dirtyRoutes.dirtyAll();
    }

    /**
     * 一层视觉配置, 同步与异步在同一层里互斥, 后设置的那种取代前一种;
     * 两者都为 {@code null} 表示这一层没有配置, 求值时直接放行到下一层.
     * <p>本记录一经设置就不再变化, 因此渲染层可以拿它自己当来源身份.
     */
    private record Layer(
            @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> sync,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> async,
            @Nullable ImmediateItemProvider asyncPlaceholder
    ) {
        private static final Layer NONE = new Layer(null, null, null);

        // 求值这一层, 放行时返回 null.
        @Nullable
        private ResolvedVisual visualize(@Nullable ItemStack actual) {
            if (this.async != null) {
                ItemProvider mapped = this.async.apply(actual);
                return mapped == null ? null : new ResolvedVisual(this, mapped, this.asyncPlaceholder);
            }
            if (this.sync != null) {
                ImmediateItemProvider mapped = this.sync.apply(actual);
                return mapped == null ? null : ResolvedVisual.of(mapped);
            }
            return null;
        }
    }

    private static final class State {
        @NotNull private final Layer global;
        @NotNull private final Layer @NotNull [] bySlot;
        @Nullable private final ItemProvider background;

        private State(
                @NotNull Layer global,
                @NotNull Layer @NotNull [] bySlot,
                @Nullable ItemProvider background
        ) {
            this.global = global;
            this.bySlot = bySlot;
            this.background = background;
        }

        @NotNull
        private static State empty(int size) {
            Layer[] bySlot = new Layer[size];
            Arrays.fill(bySlot, Layer.NONE);
            return new State(Layer.NONE, bySlot, null);
        }
    }

    /**
     * 按 Inventory 槽位保存弱 attachment 的视觉失效路由.
     */
    private static final class VisualDirtyRoutes {
        private final AtomicReferenceArray<CopyOnWriteArrayList<RouteReference>> routesBySlot;
        private final ReferenceQueue<Attachment> deadAttachments = new ReferenceQueue<>();

        private VisualDirtyRoutes(int size) {
            this.routesBySlot = new AtomicReferenceArray<>(size);
        }

        @NotNull
        private Subscription attach(int slot, @NotNull Runnable invalidator) {
            this.reapDeadAttachments();
            CopyOnWriteArrayList<RouteReference> routes = this.routes(slot);
            Attachment attachment = new Attachment(invalidator);
            RouteReference reference = new RouteReference(attachment, routes, this.deadAttachments);
            attachment.reference = reference;
            routes.add(reference);
            return attachment;
        }

        private void dirty(int slot) {
            this.reapDeadAttachments();
            CopyOnWriteArrayList<RouteReference> routes = this.routesBySlot.get(slot);
            Object[] snapshot = routes == null || routes.isEmpty() ? null : routes.toArray();
            RuntimeException failure = publish(snapshot, null);
            if (failure != null) {
                throw failure;
            }
        }

        private void dirtyAll() {
            this.reapDeadAttachments();
            Object[][] snapshots = new Object[this.routesBySlot.length()][];
            for (int slot = 0; slot < snapshots.length; slot++) {
                CopyOnWriteArrayList<RouteReference> routes = this.routesBySlot.get(slot);
                if (routes != null && !routes.isEmpty()) {
                    snapshots[slot] = routes.toArray();
                }
            }
            RuntimeException failure = null;
            for (int slot = 0; slot < snapshots.length; slot++) {
                failure = publish(snapshots[slot], failure);
            }
            if (failure != null) {
                throw failure;
            }
        }

        @NotNull
        private CopyOnWriteArrayList<RouteReference> routes(int slot) {
            CopyOnWriteArrayList<RouteReference> routes = this.routesBySlot.get(slot);
            if (routes != null) {
                return routes;
            }
            CopyOnWriteArrayList<RouteReference> created = new CopyOnWriteArrayList<>();
            if (this.routesBySlot.compareAndSet(slot, null, created)) {
                return created;
            }
            return this.routesBySlot.get(slot);
        }

        private void reapDeadAttachments() {
            Reference<? extends Attachment> reference;
            while ((reference = this.deadAttachments.poll()) != null) {
                ((RouteReference) reference).remove();
            }
        }

        @Nullable
        private static RuntimeException publish(Object @Nullable [] snapshot, @Nullable RuntimeException failure) {
            if (snapshot == null) {
                return failure;
            }
            for (int index = 0; index < snapshot.length; index++) {
                RouteReference reference = (RouteReference) snapshot[index];
                Attachment attachment = reference.get();
                if (attachment == null) {
                    reference.remove();
                    continue;
                }
                Runnable invalidator = attachment.invalidator.get();
                if (invalidator == null) {
                    continue;
                }
                try {
                    invalidator.run();
                } catch (RuntimeException exception) {
                    failure = ThrowableUtils.combine(failure, exception);
                }
            }
            return failure;
        }

        private static final class Attachment implements Subscription {
            private final AtomicReference<Runnable> invalidator;
            @Nullable private volatile RouteReference reference;

            private Attachment(@NotNull Runnable invalidator) {
                this.invalidator = new AtomicReference<>(invalidator);
            }

            @Override
            public boolean isClosed() {
                return this.invalidator.get() == null;
            }

            @Override
            public void close() {
                if (this.invalidator.getAndSet(null) == null) {
                    return;
                }
                RouteReference reference = this.reference;
                this.reference = null;
                if (reference != null) {
                    reference.remove();
                }
            }
        }

        private static final class RouteReference extends WeakReference<Attachment> {
            private final WeakReference<CopyOnWriteArrayList<RouteReference>> owner;

            private RouteReference(
                    @NotNull Attachment attachment,
                    @NotNull CopyOnWriteArrayList<RouteReference> owner,
                    @NotNull ReferenceQueue<Attachment> queue
            ) {
                super(attachment, queue);
                this.owner = new WeakReference<>(owner);
            }

            private void remove() {
                CopyOnWriteArrayList<RouteReference> entries = this.owner.get();
                if (entries != null) {
                    entries.remove(this);
                }
                this.clear();
            }
        }
    }
}
