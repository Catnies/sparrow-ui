package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTest {

    @Test
    void simpleItemUsesStaticFastPath() {
        ItemProvider provider = ItemProvider.EMPTY;
        Item item = Item.simple(provider);

        assertInstanceOf(StaticItem.class, item);
        assertSame(provider, item.getItemProvider());
        assertFalse(item instanceof ObservableItem);
        assertSame(ItemAttachment.PASSIVE, AttachSupport.attach(item, ignoredInvalidation -> { }));
    }

    @Test
    void declarativeBuilderAlwaysBuildsObservableConfiguredItem() {
        Item item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickHandler(ignoredClick -> { })
                .addBundleSelectHandler(ignoredSelect -> { })
                .build();

        assertInstanceOf(ObservableItem.class, item);
        assertFalse(item instanceof StaticItem);
    }

    @Test
    void builtItemCanNotifyAttachedSlots() {
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredInvalidation -> invalidations.incrementAndGet());
        item.notifyWindows();
        attachment.close();
        attachment.close();
        item.notifyWindows();

        assertEquals(1, invalidations.get());
    }

    @Test
    void modifiersRunInOrderAfterItemIsFullyConstructed() {
        List<String> order = new ArrayList<>();
        AtomicReference<ObservableItem> received = new AtomicReference<>();
        AtomicInteger notifications = new AtomicInteger();
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addModifier(built -> {
                    order.add("first");
                    received.set(built);
                    ItemAttachment attachment = AttachSupport.attach(built, ignoredInvalidation -> notifications.incrementAndGet());
                    built.notifyWindows();
                    attachment.close();
                })
                .addModifier(ignoredItem -> order.add("second"))
                .build();

        assertSame(item, received.get());
        assertEquals(List.of("first", "second"), order);
        assertEquals(1, notifications.get());
    }

    @Test
    void modifierFailureStopsLaterModifiersAndEscapesBuild() {
        IllegalStateException failure = new IllegalStateException("modifier failed");
        AtomicInteger laterCalls = new AtomicInteger();
        ItemBuilder builder = Item.builder()
                .addModifier(ignoredItem -> {
                    throw failure;
                })
                .addModifier(ignoredItem -> laterCalls.incrementAndGet());

        assertSame(failure, assertThrows(IllegalStateException.class, builder::build));
        assertEquals(0, laterCalls.get());
    }

    @Test
    void displaySourceCanOnlyBeConfiguredOnce() {
        ItemBuilder builder = Item.builder().setItemProviderAsync(ItemProvider.EMPTY);

        assertThrows(
                IllegalStateException.class,
                () -> builder.setItemProviderAsync(ItemProvider.EMPTY)
        );
    }
}
