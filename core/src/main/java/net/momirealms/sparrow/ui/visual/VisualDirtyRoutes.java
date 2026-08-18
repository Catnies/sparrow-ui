package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 按槽位保存弱 attachment 的视觉失效路由.
 */
final class VisualDirtyRoutes {
    private final AtomicReferenceArray<CopyOnWriteArrayList<RouteReference>> routesBySlot;
    private final ReferenceQueue<Attachment> deadAttachments = new ReferenceQueue<>();

    VisualDirtyRoutes(int size) {
        this.routesBySlot = new AtomicReferenceArray<>(size);
    }

    @NotNull
    Subscription attach(int slot, @NotNull Runnable invalidator) {
        this.reapDeadAttachments();
        CopyOnWriteArrayList<RouteReference> routes = this.routes(slot);
        Attachment attachment = new Attachment(invalidator);
        RouteReference reference = new RouteReference(attachment, routes, this.deadAttachments);
        attachment.reference = reference;
        routes.add(reference);
        return attachment;
    }

    void dirty(int slot) {
        this.reapDeadAttachments();
        CopyOnWriteArrayList<RouteReference> routes = this.routesBySlot.get(slot);
        Object[] snapshot = routes == null || routes.isEmpty() ? null : routes.toArray();
        RuntimeException failure = publish(snapshot, null);
        if (failure != null) {
            throw failure;
        }
    }

    void dirtyAll() {
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
