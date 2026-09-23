package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.item.AttachSupport;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.internal.time.TickingTestSupport;
import net.momirealms.sparrow.ui.window.MerchantWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantTradeSignalBindingsTest {

    private static final Player VIEWER = AttachSupport.player();
    private static final Window VIEWER_WINDOW = AttachSupport.window(VIEWER);

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void periodicTradeItemsDriveOfferInvalidationThroughTheirOwnDependencies() {
        TickingTestSupport.install();
        try {
            Item fast = Item.builder().setItemProviderAsync(ItemProvider.EMPTY).updatePeriodically(5).build();
            Item slow = Item.builder().setItemProviderAsync(ItemProvider.EMPTY).updatePeriodically(7).build();
            MerchantWindow.Trade trade = MerchantWindow.Trade.builder()
                    .setFirstInput(fast)
                    .setSecondInput(slow)
                    .setResult(Item.simple(ItemProvider.EMPTY))
                    .build();
            AtomicInteger invalidations = new AtomicInteger();
            MerchantMenuHandleImpl.TradeBindings bindings = new MerchantMenuHandleImpl.TradeBindings(
                    List.of(trade),
                    VIEWER_WINDOW,
                    invalidations::incrementAndGet
            );
            bindings.activate();
            TickingTestSupport.advance(4);

            assertEquals(0, invalidations.get());
            TickingTestSupport.advance(1);

            assertEquals(1, invalidations.get(), "第 5 tick: 只有 5 tick 周期的物品到期");
            TickingTestSupport.advance(2);

            assertEquals(2, invalidations.get(), "第 7 tick: 只有 7 tick 周期的物品到期");
            TickingTestSupport.advance(3);

            assertEquals(3, invalidations.get(), "第 10 tick: 5 tick 周期的物品再次到期");
            bindings.retire();
            TickingTestSupport.advance(10);

            assertEquals(3, invalidations.get());
            bindings.close();
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void duplicateTradesOwnIndependentSubscriptionsAttachmentsAndLateNotificationGates() {
        TrackingItem item = new TrackingItem("shared", false, null);
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder()
                .setFirstInput(item)
                .setSecondInput(item)
                .setResult(item)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        MerchantMenuHandleImpl.TradeBindings bindings = new MerchantMenuHandleImpl.TradeBindings(
                List.of(trade, trade),
                VIEWER_WINDOW,
                invalidations::incrementAndGet
        );

        assertEquals(6, item.liveAttachments.get());
        bindings.activate();
        trade.setDiscount(1);

        assertEquals(2, invalidations.get());
        item.notifyUpdateAll();

        assertEquals(8, invalidations.get());
        bindings.retire();
        trade.setAvailable(false);
        item.notifyUpdateAll();

        assertEquals(8, invalidations.get());
        bindings.close();
        bindings.close();

        assertEquals(0, item.liveAttachments.get());
    }

    @Test
    void preparingNotificationIsIgnoredAndCloseRetiresInFlightCallbacks() {
        TrackingItem item = new TrackingItem("preparing", true, null);
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder()
                .setFirstInput(item)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        MerchantMenuHandleImpl.TradeBindings bindings = new MerchantMenuHandleImpl.TradeBindings(
                List.of(trade),
                VIEWER_WINDOW,
                invalidations::incrementAndGet
        );

        assertEquals(0, invalidations.get());
        bindings.activate();

        assertEquals(0, invalidations.get());
        item.notifyUpdateAll();

        assertEquals(1, invalidations.get());
        bindings.close();
        item.notifyUpdateAll();
        trade.setDiscount(1);

        assertEquals(1, invalidations.get());
    }

    @Test
    void sharedTradeInvalidatesEveryActiveBindingIndependently() {
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder().build();
        AtomicInteger firstWindowInvalidations = new AtomicInteger();
        AtomicInteger secondWindowInvalidations = new AtomicInteger();
        MerchantMenuHandleImpl.TradeBindings first = new MerchantMenuHandleImpl.TradeBindings(
                List.of(trade),
                VIEWER_WINDOW,
                firstWindowInvalidations::incrementAndGet
        );
        MerchantMenuHandleImpl.TradeBindings second = new MerchantMenuHandleImpl.TradeBindings(
                List.of(trade),
                VIEWER_WINDOW,
                secondWindowInvalidations::incrementAndGet
        );
        first.activate();
        second.activate();
        trade.setDiscount(1);

        assertEquals(1, firstWindowInvalidations.get());
        assertEquals(1, secondWindowInvalidations.get());
        first.close();
        trade.setAvailable(false);

        assertEquals(1, firstWindowInvalidations.get());
        assertEquals(2, secondWindowInvalidations.get());
        second.close();
    }

    @Test
    void preparationFailureRollsBackEarlierAttachmentsAndSubscriptions() {
        TrackingItem first = new TrackingItem("first", false, null);
        TrackingItem failing = new TrackingItem("failing", false, null);
        failing.failNextAttach.set(true);
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder()
                .setFirstInput(first)
                .setSecondInput(failing)
                .build();
        AtomicInteger invalidations = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> new MerchantMenuHandleImpl.TradeBindings(
                        List.of(trade),
                        VIEWER_WINDOW,
                        invalidations::incrementAndGet
                )
        );

        assertEquals(0, first.liveAttachments.get());
        assertEquals(0, failing.liveAttachments.get());
        first.notifyUpdateAll();
        trade.setDiscount(1);

        assertEquals(0, invalidations.get());
    }

    @Test
    void closesEntriesAndTheirItemsInReverseOwnershipOrder() {
        ArrayList<String> closeOrder = new ArrayList<>();
        TrackingItem firstA = new TrackingItem("first-a", false, closeOrder);
        TrackingItem secondA = new TrackingItem("second-a", false, closeOrder);
        TrackingItem resultA = new TrackingItem("result-a", false, closeOrder);
        TrackingItem firstB = new TrackingItem("first-b", false, closeOrder);
        TrackingItem secondB = new TrackingItem("second-b", false, closeOrder);
        TrackingItem resultB = new TrackingItem("result-b", false, closeOrder);
        MerchantWindow.Trade tradeA = MerchantWindow.Trade.builder()
                .setFirstInput(firstA)
                .setSecondInput(secondA)
                .setResult(resultA)
                .build();
        MerchantWindow.Trade tradeB = MerchantWindow.Trade.builder()
                .setFirstInput(firstB)
                .setSecondInput(secondB)
                .setResult(resultB)
                .build();
        MerchantMenuHandleImpl.TradeBindings bindings = new MerchantMenuHandleImpl.TradeBindings(
                List.of(tradeA, tradeB),
                VIEWER_WINDOW,
                () -> {}
        );
        bindings.activate();
        bindings.close();

        assertEquals(
                List.of("result-b", "second-b", "first-b", "result-a", "second-a", "first-a"),
                closeOrder
        );
    }

    @Test
    void minimumDiscountSaturatesToMaximumVanillaSurcharge() {
        assertEquals(Integer.MAX_VALUE, MerchantMenuHandleImpl.OfferMath.specialPriceDiff(Integer.MIN_VALUE));
        assertEquals(-15, MerchantMenuHandleImpl.OfferMath.specialPriceDiff(15));
        assertEquals(15, MerchantMenuHandleImpl.OfferMath.specialPriceDiff(-15));
        assertEquals(0, MerchantMenuHandleImpl.OfferMath.specialPriceDiff(0));
    }

    private static final class TrackingItem implements Item {
        private final String name;
        private final boolean invalidateDuringAttach;
        private final List<String> closeOrder;
        private final List<Observer<? super Item>> observers = new ArrayList<>();
        private final AtomicInteger liveAttachments = new AtomicInteger();
        private final AtomicBoolean failNextAttach = new AtomicBoolean();
        private TrackingItem(
                String name,
                boolean invalidateDuringAttach,
                List<String> closeOrder
        ) {
            this.name = name;
            this.invalidateDuringAttach = invalidateDuringAttach;
            this.closeOrder = closeOrder;
        }
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
        @Override
        public ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
            if (this.failNextAttach.compareAndSet(true, false)) {
                throw new IllegalStateException("injected Merchant Item attachment failure");
            }
            this.observers.add(observer);
            this.liveAttachments.incrementAndGet();
            if (this.invalidateDuringAttach) {
                observer.onUpdate(this);
            }
            AtomicBoolean closed = new AtomicBoolean();
            return new ItemAttachment() {
                @Override
                public void close() {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    TrackingItem.this.liveAttachments.decrementAndGet();
                    if (TrackingItem.this.closeOrder != null) {
                        TrackingItem.this.closeOrder.add(TrackingItem.this.name);
                    }
                }
            };
        }
        private void notifyUpdateAll() {
            List<Observer<? super Item>> snapshot = List.copyOf(this.observers);
            for (int index = 0; index < snapshot.size(); index++) {
                snapshot.get(index).onUpdate(this);
            }
        }
    }
}
