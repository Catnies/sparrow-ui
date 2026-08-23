package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

final class VisualDirtyAttachments {
    private final AtomicReferenceArray<AttachmentReference[]> attachmentsBySlot; // 没有订阅的槽位为 null
    private final ReferenceQueue<Attachment> deadAttachments = new ReferenceQueue<>();

    VisualDirtyAttachments(int size) {
        this.attachmentsBySlot = new AtomicReferenceArray<>(size);
    }

    // 往槽位数组末尾添一条订阅, 与别的线程抢同一槽位时重来一轮.
    @NotNull
    Subscription attach(int slot, @NotNull Runnable invalidator) {
        this.reapDeadAttachments();
        // 订阅表弱持有回执, 调用方丢掉回执即退订
        Attachment attachment = new Attachment(invalidator);
        AttachmentReference reference = new AttachmentReference(attachment, this, slot, this.deadAttachments);
        attachment.reference = reference;
        while (true) {
            AttachmentReference[] current = this.attachmentsBySlot.get(slot);
            AttachmentReference[] updated;
            if (current == null) {
                updated = new AttachmentReference[]{reference};
            } else {
                updated = Arrays.copyOf(current, current.length + 1);
                updated[current.length] = reference;
            }
            if (this.attachmentsBySlot.compareAndSet(slot, current, updated)) {
                return attachment;
            }
        }
    }

    void dirty(int slot) {
        this.reapDeadAttachments();
        RuntimeException failure = this.publish(this.attachmentsBySlot.get(slot), null);
        if (failure != null) {
            throw failure;
        }
    }

    // 某个回调失败也会继续通知其余槽位
    void dirtyAll() {
        this.reapDeadAttachments();
        RuntimeException failure = null;
        for (int slot = 0; slot < this.attachmentsBySlot.length(); slot++) {
            failure = this.publish(this.attachmentsBySlot.get(slot), failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 通知一个槽位上的全部订阅, 回调抛出的异常攒起来交给调用方抛.
     *
     * @param attachments 该槽位的订阅数组, {@code null} 表示这一槽没有订阅
     * @param failure 已经攒下的异常, 没有时为 {@code null}
     * @return 算上本轮之后攒下的异常, 全程无异常时为 {@code null}
     */
    @Nullable
    private RuntimeException publish(AttachmentReference @Nullable [] attachments, @Nullable RuntimeException failure) {
        if (attachments == null) {
            return failure;
        }
        for (int index = 0; index < attachments.length; index++) {
            AttachmentReference reference = attachments[index];
            Attachment attachment = reference.get();
            // 派发时顺手摘掉已经回收的回执
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

    // 从槽位快照中摘掉一条订阅
    private void removeAt(int slot, @NotNull AttachmentReference reference) {
        while (true) {
            AttachmentReference[] current = this.attachmentsBySlot.get(slot);
            if (current == null) {
                return;
            }
            int index = indexOf(current, reference);
            if (index < 0) {
                return;
            }
            AttachmentReference[] updated;
            if (current.length == 1) {
                updated = null;
            } else {
                updated = new AttachmentReference[current.length - 1];
                System.arraycopy(current, 0, updated, 0, index);
                System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
            }
            if (this.attachmentsBySlot.compareAndSet(slot, current, updated)) {
                return;
            }
        }
    }

    // 每次进出订阅表时顺手清理死亡回执
    private void reapDeadAttachments() {
        Reference<? extends Attachment> reference;
        while ((reference = this.deadAttachments.poll()) != null) {
            ((AttachmentReference) reference).remove();
        }
    }

    private static int indexOf(AttachmentReference @NotNull [] attachments, @NotNull AttachmentReference reference) {
        for (int index = 0; index < attachments.length; index++) {
            if (attachments[index] == reference) {
                return index;
            }
        }
        return -1;
    }

    private static final class Attachment implements Subscription {
        private final AtomicReference<Runnable> invalidator; // null 表示已退订
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
        private final WeakReference<VisualDirtyAttachments> owner; // 引用队列中的条目不能钉住视觉配置
        private final int slot;

        private AttachmentReference(
                @NotNull Attachment attachment,
                @NotNull VisualDirtyAttachments owner,
                int slot,
                @NotNull ReferenceQueue<Attachment> queue
        ) {
            super(attachment, queue);
            this.owner = new WeakReference<>(owner);
            this.slot = slot;
        }

        private void remove() {
            VisualDirtyAttachments owner = this.owner.get();
            if (owner != null) {
                owner.removeAt(this.slot, this);
            }
            this.clear();
        }
    }
}
