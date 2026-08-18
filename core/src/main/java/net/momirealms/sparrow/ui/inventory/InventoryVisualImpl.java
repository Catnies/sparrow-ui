package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.AbstractVisual;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
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
        return this.state.visualizerProvider;
    }

    @Override
    public void visualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider) {
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(visualizerProvider, current.visualizerProvidersBySlot, current.background);
        }
        this.dirty();
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider(int slot) {
        Objects.checkIndex(slot, this.state.visualizerProvidersBySlot.length);
        return this.state.visualizerProvidersBySlot[slot];
    }

    @Override
    public void visualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider) {
        Objects.checkIndex(slot, this.state.visualizerProvidersBySlot.length);
        synchronized (this.stateLock) {
            State current = this.state;
            @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider>[] visualizerProvidersBySlot =
                    current.visualizerProvidersBySlot.clone();
            visualizerProvidersBySlot[slot] = visualizerProvider;
            this.state = new State(current.visualizerProvider, visualizerProvidersBySlot, current.background);
        }
        this.dirtyRoutes.dirty(slot);
    }

    @Nullable
    @Override
    public ImmediateItemProvider background() {
        return this.state.background;
    }

    @Override
    public void background(@Nullable ImmediateItemProvider background) {
        synchronized (this.stateLock) {
            State current = this.state;
            this.state = new State(current.visualizerProvider, current.visualizerProvidersBySlot, background);
        }
        this.dirty();
    }

    @Nullable
    ImmediateItemProvider visualize(int slot, @Nullable ItemStack actual) {
        State current = this.state;
        Objects.checkIndex(slot, current.visualizerProvidersBySlot.length);
        @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> slotVisualizerProvider =
                current.visualizerProvidersBySlot[slot];
        if (slotVisualizerProvider != null) {
            ImmediateItemProvider mapped = slotVisualizerProvider.apply(actual);
            if (mapped != null) {
                return mapped;
            }
        }
        if (current.visualizerProvider != null) {
            ImmediateItemProvider mapped = current.visualizerProvider.apply(actual);
            if (mapped != null) {
                return mapped;
            }
        }
        return actual == null ? current.background : null;
    }

    @NotNull
    Subscription attach(int slot, @NotNull Runnable invalidator) {
        return this.dirtyRoutes.attach(slot, invalidator);
    }

    @Override
    public void dirty() {
        this.dirtyRoutes.dirtyAll();
    }

    private static final class State {
        @Nullable private final Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider;
        @Nullable private final Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> @NotNull [] visualizerProvidersBySlot;
        @Nullable private final ImmediateItemProvider background;

        private State(
                @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider,
                @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> @NotNull [] visualizerProvidersBySlot,
                @Nullable ImmediateItemProvider background
        ) {
            this.visualizerProvider = visualizerProvider;
            this.visualizerProvidersBySlot = visualizerProvidersBySlot;
            this.background = background;
        }

        @NotNull
        @SuppressWarnings("unchecked")
        private static State empty(int size) {
            @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider>[] visualizerProvidersBySlot =
                    (Function<@Nullable ItemStack, @Nullable ImmediateItemProvider>[]) new Function<?, ?>[size];
            return new State(null, visualizerProvidersBySlot, null);
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
