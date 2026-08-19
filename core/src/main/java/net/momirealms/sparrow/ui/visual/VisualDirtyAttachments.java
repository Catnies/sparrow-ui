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

final class VisualDirtyAttachments {
    private final AtomicReferenceArray<CopyOnWriteArrayList<AttachmentReference>> attachmentsBySlot;
    private final ReferenceQueue<Attachment> deadAttachments = new ReferenceQueue<>();

    VisualDirtyAttachments(int size) {
        this.attachmentsBySlot = new AtomicReferenceArray<>(size);
    }

    // 按槽位保存视觉失效订阅, 回执只被弱持有.
    @NotNull
    Subscription attach(int slot, @NotNull Runnable invalidator) {
        this.reapDeadAttachments();
        CopyOnWriteArrayList<AttachmentReference> attachments = this.attachments(slot);
        Attachment attachment = new Attachment(invalidator);
        AttachmentReference reference = new AttachmentReference(attachment, attachments, this.deadAttachments);
        attachment.reference = reference;
        attachments.add(reference);
        return attachment;
    }

    void dirty(int slot) {
        this.reapDeadAttachments();
        RuntimeException failure = publish(this.attachmentsBySlot.get(slot), null);
        if (failure != null) {
            throw failure;
        }
    }

    // 逐槽直接发布, 不先攒一份全量快照: 两者都不保证与 attach 的原子性, 而 COW 列表遍历本身已是快照.
    void dirtyAll() {
        this.reapDeadAttachments();
        RuntimeException failure = null;
        for (int slot = 0; slot < this.attachmentsBySlot.length(); slot++) {
            failure = publish(this.attachmentsBySlot.get(slot), failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    @NotNull
    private CopyOnWriteArrayList<AttachmentReference> attachments(int slot) {
        CopyOnWriteArrayList<AttachmentReference> attachments = this.attachmentsBySlot.get(slot);
        if (attachments != null) {
            return attachments;
        }
        CopyOnWriteArrayList<AttachmentReference> created = new CopyOnWriteArrayList<>();
        if (this.attachmentsBySlot.compareAndSet(slot, null, created)) {
            return created;
        }
        return this.attachmentsBySlot.get(slot);
    }

    private void reapDeadAttachments() {
        Reference<? extends Attachment> reference;
        while ((reference = this.deadAttachments.poll()) != null) {
            ((AttachmentReference) reference).remove();
        }
    }

    @Nullable
    private static RuntimeException publish(
            @Nullable CopyOnWriteArrayList<AttachmentReference> attachments,
            @Nullable RuntimeException failure
    ) {
        if (attachments == null || attachments.isEmpty()) {
            return failure;
        }
        // COW 列表的迭代器就是这一刻的快照, 发布期间的增删不影响本轮
        for (AttachmentReference reference : attachments) {
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
        @Nullable private volatile AttachmentReference reference;

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
            AttachmentReference reference = this.reference;
            this.reference = null;
            if (reference != null) {
                reference.remove();
            }
        }
    }

    private static final class AttachmentReference extends WeakReference<Attachment> {
        private final WeakReference<CopyOnWriteArrayList<AttachmentReference>> owner;

        private AttachmentReference(
                @NotNull Attachment attachment,
                @NotNull CopyOnWriteArrayList<AttachmentReference> owner,
                @NotNull ReferenceQueue<Attachment> queue
        ) {
            super(attachment, queue);
            this.owner = new WeakReference<>(owner);
        }

        private void remove() {
            CopyOnWriteArrayList<AttachmentReference> entries = this.owner.get();
            if (entries != null) {
                entries.remove(this);
            }
            this.clear();
        }
    }
}
