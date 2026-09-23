package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemDependsOnTest {

    @Test
    void unattachedItemDoesNotSubscribe() {
        MutableSignal<String> season = Signal.of("spring");
        AtomicInteger invalidations = new AtomicInteger();
        Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        season.set("summer");

        assertEquals(0, invalidations.get());
    }

    @Test
    void attachedItemIsInvalidatedWhenTheSignalChanges() {
        MutableSignal<String> season = Signal.of("spring");
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        season.set("summer");

        assertEquals(1, invalidations.get());
    }

    @Test
    void detachStopsInvalidation() {
        MutableSignal<String> season = Signal.of("spring");
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        attachment.close();
        season.set("summer");

        assertEquals(0, invalidations.get());
    }

    @Test
    void closingAttachmentTwiceIsHarmless() {
        MutableSignal<String> season = Signal.of("spring");
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> {
        });
        attachment.close();
        attachment.close();
    }

    @Test
    void multipleSignalsAllInvalidate() {
        MutableSignal<String> season = Signal.of("spring");
        MutableSignal<Integer> level = Signal.of(1);
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season, level)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        season.set("summer");
        level.set(2);

        assertEquals(2, invalidations.get());
    }

    @Test
    void eachAttachmentIsInvalidatedIndependently() {
        MutableSignal<String> season = Signal.of("spring");
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        ItemAttachment firstAttachment = AttachSupport.attach(item, ignoredItem -> first.incrementAndGet());
        AttachSupport.attach(item, ignoredItem -> second.incrementAndGet());
        season.set("summer");

        assertEquals(1, first.get());
        assertEquals(1, second.get());
        firstAttachment.close();
        season.set("autumn");

        assertEquals(1, first.get());
        assertEquals(2, second.get());
    }

    @Test
    void keyedDependencyOnlyInvalidatesTheMatchingViewer() {
        MutableKeyedSignal<UUID, Integer> coins = KeyedSignal.of(key -> 0);
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(coins, context -> context.player().getUniqueId())
                .build();
        Player alice = AttachSupport.player();
        Player bob = AttachSupport.player();
        AtomicInteger aliceInvalidations = new AtomicInteger();
        AtomicInteger bobInvalidations = new AtomicInteger();
        item.attach(RenderContext.offSlot(AttachSupport.window(alice)), ignoredItem -> aliceInvalidations.incrementAndGet());
        item.attach(RenderContext.offSlot(AttachSupport.window(bob)), ignoredItem -> bobInvalidations.incrementAndGet());
        coins.set(alice.getUniqueId(), 100);

        assertEquals(1, aliceInvalidations.get());
        assertEquals(0, bobInvalidations.get());
    }

    @Test
    void keyedDependencyFollowsPartitionEviction() {
        MutableKeyedSignal<UUID, Integer> coins = KeyedSignal.of(key -> 0);
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(coins, context -> context.player().getUniqueId())
                .build();
        Player viewer = AttachSupport.player();
        AtomicInteger invalidations = new AtomicInteger();
        item.attach(RenderContext.offSlot(AttachSupport.window(viewer)), ignoredItem -> invalidations.incrementAndGet());
        coins.remove(viewer.getUniqueId());

        assertEquals(0, invalidations.get(), "驱逐不通知");
        coins.set(viewer.getUniqueId(), 7);

        assertEquals(1, invalidations.get());
    }

    @Test
    void signalDoesNotPinTheAttachment() {
        MutableSignal<String> season = Signal.of("spring");
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        WeakReference<ItemAttachment> probe = new WeakReference<>(attachment);
        attachment = null;
        for (int attempt = 0; attempt < 100 && probe.get() != null; attempt++) {
            System.gc();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertNull(probe.get(), "signal 不应钉住挂载");
        season.set("summer");

        assertEquals(0, invalidations.get(), "持有方被回收后依赖订阅自动消亡");
    }

    @Test
    void dependencyInvalidationAlsoReachesManualNotify() {
        MutableSignal<String> season = Signal.of("spring");
        ObservableItem item = (ObservableItem) Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        season.set("summer");
        item.notifyWindows();

        assertEquals(2, invalidations.get());
    }

    @Test
    void keyExtractionFailureRollsBackTheWholeAttachment() {
        MutableSignal<String> season = Signal.of("spring");
        MutableKeyedSignal<UUID, Integer> coins = KeyedSignal.of(key -> 0);
        AtomicInteger attempts = new AtomicInteger();
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .dependsOn(season)
                .dependsOn(coins, context -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("no guild");
                    }
                    return context.player().getUniqueId();
                })
                .build();
        Player failing = AttachSupport.player();
        Window failingWindow = AttachSupport.window(failing);

        assertThrows(IllegalStateException.class, () -> item.attach(RenderContext.offSlot(failingWindow), ignoredItem -> {
        }));
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        season.set("summer");

        assertEquals(1, invalidations.get());
    }
}
