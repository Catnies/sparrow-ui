package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantTradeTest {

    @Test
    void defaultsAndBuilderReuseKeepConstructedItemReferencesFixed() {
        MerchantWindow.Trade defaults = MerchantWindow.Trade.builder().build();
        Item first = Item.simple(ItemProvider.EMPTY);
        Item second = Item.simple(ItemProvider.EMPTY);
        Item result = Item.simple(ItemProvider.EMPTY);
        MerchantWindow.Trade.Builder builder = MerchantWindow.Trade.builder()
                .setFirstInput(first)
                .setSecondInput(second)
                .setResult(result)
                .setDiscount(Integer.MIN_VALUE)
                .setAvailable(false);
        MerchantWindow.Trade built = builder.build();
        MerchantWindow.Trade rebuilt = builder
                .setFirstInput(Item.empty())
                .setDiscount(Integer.MAX_VALUE)
                .setAvailable(true)
                .build();

        assertSame(Item.empty(), defaults.getFirstInput());
        assertSame(Item.empty(), defaults.getSecondInput());
        assertSame(Item.empty(), defaults.getResult());
        assertEquals(0, defaults.getDiscount());
        assertTrue(defaults.isAvailable());
        assertSame(first, built.getFirstInput());
        assertSame(second, built.getSecondInput());
        assertSame(result, built.getResult());
        assertEquals(Integer.MIN_VALUE, built.getDiscount());
        assertFalse(built.isAvailable());
        assertSame(Item.empty(), rebuilt.getFirstInput());
        assertEquals(Integer.MAX_VALUE, rebuilt.getDiscount());
        assertTrue(rebuilt.isAvailable());
    }

    @Test
    void settersPublishOnlyRealChangesSynchronouslyOnTheCallingThread() throws Exception {
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder().build();
        List<MerchantWindow.TradeChange> changes = new ArrayList<>();
        AtomicReference<Thread> notificationThread = new AtomicReference<>();
        Subscription subscription = trade.subscribe(change -> {
            changes.add(change);
            notificationThread.set(Thread.currentThread());
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            trade.setDiscount(0);
            trade.setAvailable(true);
            Future<Thread> setterThread = executor.submit(() -> {
                trade.setDiscount(Integer.MIN_VALUE);
                return Thread.currentThread();
            });

            assertSame(setterThread.get(), notificationThread.get());
            assertEquals(List.of(MerchantWindow.TradeChange.DISCOUNT), changes);
            assertEquals(Integer.MIN_VALUE, trade.getDiscount());
            trade.setAvailable(false);
            trade.setAvailable(false);

            assertEquals(
                    List.of(
                            MerchantWindow.TradeChange.DISCOUNT,
                            MerchantWindow.TradeChange.AVAILABLE
                    ),
                    changes
            );

            assertFalse(trade.isAvailable());
        } finally {
            subscription.close();
            executor.shutdownNow();
        }

        assertTrue(subscription.isClosed());
    }

    @Test
    void concurrentSameValueWritesProduceOneAtomicTransition() throws Exception {
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder().build();
        AtomicInteger notifications = new AtomicInteger();
        Subscription subscription = trade.subscribe(ignoredChange -> notifications.incrementAndGet());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        ArrayList<Future<?>> writers = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                writers.add(executor.submit(() -> {
                    start.await();
                    trade.setDiscount(Integer.MAX_VALUE);
                    trade.setAvailable(false);
                    return null;
                }));
            }
            start.countDown();
            for (int index = 0; index < writers.size(); index++) {
                writers.get(index).get();
            }

            assertEquals(Integer.MAX_VALUE, trade.getDiscount());
            assertFalse(trade.isAvailable());
            assertEquals(2, notifications.get());
        } finally {
            subscription.close();
            executor.shutdownNow();
        }
    }
}
