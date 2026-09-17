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

// 一份按槽位分组的失效订阅表: 订阅弱持有回执, 使用方把句柄丢了就等于退订.
//
// 每格的订阅数组都是 CAS 整体替换的不可变快照, 派发不用加锁; 死掉的弱引用在进出订阅表时顺手清掉.
final class VisualDirtyAttachments {
    private final AtomicReferenceArray<AttachmentReference[]> attachmentsBySlot; // 每格的订阅数组; 没人订阅的格子是 null
    private final ReferenceQueue<Attachment> deadAttachments = new ReferenceQueue<>(); // 回执被回收后引用进这里, 下次进出订阅表时按它清账

    VisualDirtyAttachments(int size) {
        this.attachmentsBySlot = new AtomicReferenceArray<>(size);
    }

    // 把订阅追加到这一格数组的末尾; CAS 没抢过别的线程就重来一轮.
    @NotNull
    Subscription attach(int slot, @NotNull Runnable invalidator) {
        this.reapDeadAttachments();
        // 表里存的是回执的弱引用, 调用方把回执丢了就等于退订
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

    // 通知这一格的订阅者
    void dirty(int slot) {
        this.reapDeadAttachments();
        RuntimeException failure = this.publish(this.attachmentsBySlot.get(slot), null);
        if (failure != null) {
            throw failure;
        }
    }

    // 一个回调抛了也要把剩下的槽位通知完, 异常攒着最后一起抛
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
     * 把这一格的订阅挨个叫一遍, 回调抛的异常攒起来交给调用方.
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
            // 回执已经被回收了, 这一趟顺手把它从表里摘掉
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

    // 从这一格的快照里摘掉一条订阅; CAS 没抢过别的线程就重来一轮
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

    // 进出订阅表时顺手把已经回收的回执清掉
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

    // 交给使用方的订阅回执; 表里只弱引用它, 保它到使用方丢掉为止
    private static final class Attachment implements Subscription {
        private final AtomicReference<Runnable> invalidator; // 已经退订就是 null
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

    // 表里存的那条弱引用. owner 也走弱引用: 引用队列里的条目不能反过来把整个视觉配置钉住.
    private static final class AttachmentReference extends WeakReference<Attachment> {
        private final WeakReference<VisualDirtyAttachments> owner;
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
