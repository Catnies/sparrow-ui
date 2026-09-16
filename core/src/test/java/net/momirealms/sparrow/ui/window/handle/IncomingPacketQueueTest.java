package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.handle.IncomingPacketQueue;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncomingPacketQueueTest {

    @Test
    void reportsOverflowAndNeverAcceptsAfterClose() {
        IncomingPacketQueue<String> queue = new IncomingPacketQueue<>(2);

        assertEquals(IncomingPacketQueue.OfferResult.ACCEPTED, queue.offer(4, "first"));
        assertEquals(IncomingPacketQueue.OfferResult.ACCEPTED, queue.offer(4, "second"));
        assertEquals(IncomingPacketQueue.OfferResult.OVERFLOW, queue.offer(4, "third"));
        queue.close();

        assertEquals(IncomingPacketQueue.OfferResult.CLOSED, queue.offer(4, "late"));
        assertTrue(queue.drain(4).isEmpty());
    }

    @Test
    void concurrentOffersReceiveOneStrictFifoSequence() throws InterruptedException {
        int producerCount = 4;
        int messagesPerProducer = 100;
        IncomingPacketQueue<Integer> queue = new IncomingPacketQueue<>(producerCount * messagesPerProducer);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(producerCount);
        List<Thread> producers = new ArrayList<>(producerCount);
        for (int producer = 0; producer < producerCount; producer++) {
            int producerId = producer;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int index = 0; index < messagesPerProducer; index++) {
                        queue.offer(6, producerId * messagesPerProducer + index);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    complete.countDown();
                }
            }, "incoming-queue-producer-" + producer);
            thread.start();
            producers.add(thread);
        }
        start.countDown();

        assertTrue(complete.await(5, TimeUnit.SECONDS));
        List<IncomingPacketQueue.Entry<Integer>> drained = queue.drain(producerCount * messagesPerProducer);

        assertEquals(producerCount * messagesPerProducer, drained.size());
        for (int index = 0; index < drained.size(); index++) {
            assertEquals(index, drained.get(index).sequence());
            assertEquals(6, drained.get(index).generation());
        }
        for (int index = 0; index < producers.size(); index++) {
            producers.get(index).join();
        }
    }

    @Test
    void drainsOnlyTheCurrentGenerationWithoutLeavingStaleEntries() {
        IncomingPacketQueue<String> queue = new IncomingPacketQueue<>(4);
        queue.offer(3, "stale");
        queue.offer(4, "current-first");
        queue.offer(4, "current-second");

        assertEquals(List.of("current-first"), queue.drain(4, 2));
        assertEquals(List.of("current-second"), queue.drain(4, 2));
        assertEquals(0, queue.size());
    }

    @Test
    void preservesMetadataAndOrderAfterRingBufferWraps() {
        IncomingPacketQueue<String> queue = new IncomingPacketQueue<>(3);
        queue.offer(7, "first");
        queue.offer(7, "second");

        assertEquals(List.of("first"), queue.drain(7, 1));
        queue.offer(8, "third");
        queue.offer(8, "fourth");
        List<IncomingPacketQueue.Entry<String>> drained = queue.drain(3);

        assertEquals("second", drained.get(0).packet());
        assertEquals(1, drained.get(0).sequence());
        assertEquals(7, drained.get(0).generation());
        assertEquals("third", drained.get(1).packet());
        assertEquals(2, drained.get(1).sequence());
        assertEquals(8, drained.get(1).generation());
        assertEquals("fourth", drained.get(2).packet());
        assertEquals(3, drained.get(2).sequence());
        assertEquals(8, drained.get(2).generation());
    }
}
