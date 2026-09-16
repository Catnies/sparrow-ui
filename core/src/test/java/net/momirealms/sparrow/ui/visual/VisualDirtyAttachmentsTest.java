package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualDirtyAttachmentsTest {

    @Test
    void everyAttachmentOnASlotIsNotifiedOncePerDirty() {
        VisualDirtyAttachments attachments = new VisualDirtyAttachments(2);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger otherSlot = new AtomicInteger();
        Subscription firstHandle = attachments.attach(0, first::incrementAndGet);
        Subscription secondHandle = attachments.attach(0, second::incrementAndGet);
        Subscription otherHandle = attachments.attach(1, otherSlot::incrementAndGet);
        attachments.dirty(0);

        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(0, otherSlot.get(), "只标脏 0 号槽位不该惊动 1 号");
        attachments.dirtyAll();

        assertEquals(2, first.get());
        assertEquals(2, second.get());
        assertEquals(1, otherSlot.get());
        firstHandle.close();
        secondHandle.close();
        otherHandle.close();
    }

    @Test
    void closedAttachmentStopsReceivingAndIsIdempotent() {
        VisualDirtyAttachments attachments = new VisualDirtyAttachments(1);
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger surviving = new AtomicInteger();
        Subscription closedHandle = attachments.attach(0, closed::incrementAndGet);
        Subscription survivingHandle = attachments.attach(0, surviving::incrementAndGet);
        closedHandle.close();
        closedHandle.close();
        attachments.dirty(0);

        assertTrue(closedHandle.isClosed());
        assertEquals(0, closed.get());
        assertEquals(1, surviving.get(), "关掉一条不影响同槽位的其余订阅");
        survivingHandle.close();
    }

    @Test
    void failingCallbackStillLetsTheRestRunAndSurfacesTheFailure() {
        VisualDirtyAttachments attachments = new VisualDirtyAttachments(1);
        RuntimeException failure = new IllegalStateException("broken invalidator");
        AtomicInteger successful = new AtomicInteger();
        Subscription failing = attachments.attach(0, () -> {
            throw failure;
        });
        Subscription healthy = attachments.attach(0, successful::incrementAndGet);

        assertEquals(failure, assertThrows(RuntimeException.class, () -> attachments.dirty(0)));
        assertEquals(1, successful.get());
        failing.close();
        healthy.close();
    }

    @Test
    void concurrentAttachAndCloseLeaveExactlyTheSurvivingSubscriptions() throws InterruptedException {
        int slots = 4;
        int threads = 8;
        int perThread = 200;
        VisualDirtyAttachments attachments = new VisualDirtyAttachments(slots);
        List<Subscription> surviving = new ArrayList<>();
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int index = 0; index < threads; index++) {
            int slot = index % slots;
            Thread worker = new Thread(() -> {
                List<Subscription> kept = new ArrayList<>();
                try {
                    start.await();
                    for (int round = 0; round < perThread; round++) {
                        Subscription handle = attachments.attach(slot, notifications::incrementAndGet);
                        if (round % 2 == 0) {
                            handle.close();
                        } else {
                            kept.add(handle);
                        }
                    }
                    synchronized (surviving) {
                        surviving.addAll(kept);
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    done.countDown();
                }
            }, "visual-dirty-attachments-test-" + index);
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();

        assertTrue(done.await(30, TimeUnit.SECONDS), "并发挂接超时");
        assertEquals(null, failure.get(), "并发挂接不该抛出异常");
        attachments.dirtyAll();

        assertEquals(threads * perThread / 2, surviving.size());
        assertEquals(surviving.size(), notifications.get());
        for (int index = 0; index < surviving.size(); index++) {
            surviving.get(index).close();
        }
        notifications.set(0);
        attachments.dirtyAll();

        assertEquals(0, notifications.get(), "全部关闭后不该再有通知");
    }
}
