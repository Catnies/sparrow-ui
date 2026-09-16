package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.GcSupport;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalPaneTest {

    @Test
    void emptyPaneUsesExplicitEmptyElementForEverySlot() {
        NormalPane pane = Pane.empty(3, 2);
        for (int slot = 0; slot < pane.area(); slot++) {
            assertSame(Element.Empty.INSTANCE, pane.element(slot));
        }
    }

    @Test
    void attachmentCarriesAtomicSnapshotAndReceivesStateChanges() {
        ImmediateItemProvider firstBackground = ignoredContext -> ItemStack.empty();
        ImmediateItemProvider secondBackground = ignoredContext -> ItemStack.empty();
        NormalPane pane = Pane.builder(new PaneSize(1, 1))
                .setBackground(firstBackground)
                .setFrozen(true)
                .build();
        AtomicInteger notifications = new AtomicInteger();
        PaneSlotAttachment attachment = pane.attach(0, ignoredInvalidation -> notifications.incrementAndGet());
        Element replacement = element();
        pane.setElement(0, replacement);
        pane.setBackground(secondBackground);
        pane.setFrozen(false);

        assertSame(Element.Empty.INSTANCE, attachment.element());
        assertTrue(attachment.frozen());
        assertSame(replacement, pane.element(0));
        assertEquals(2, notifications.get());
        assertSame(secondBackground, pane.background());
        attachment.close();
    }

    @Test
    void visualIsStableAndCarriesBackgroundConfiguredAtBuild() {
        ImmediateItemProvider background = ignoredContext -> ItemStack.empty();
        NormalPane pane = Pane.builder(new PaneSize(1, 1)).setBackground(background).build();

        assertSame(pane.visual(), pane.visual());
        assertSame(background, pane.visual().background());
        assertSame(background, pane.background());
        pane.setBackground((ItemProvider) null);

        assertNull(pane.visual().background());
    }

    @Test
    void visualizerFacadeDelegatesToVisual() {
        NormalPane pane = Pane.empty(1, 1);
        Function<ItemStack, ItemProvider> visualizer = ignoredActual -> null;
        pane.setVisualizerProvider(visualizer);

        assertSame(visualizer, pane.visualizerProvider());
        assertSame(visualizer, pane.visual().visualizerProvider());
        pane.setVisualizerProvider(0, visualizer);

        assertSame(visualizer, pane.visualizerProvider(0));
        pane.setVisualizerProvider(null);

        assertNull(pane.visualizerProvider());
        pane.setVisualizerProvider(0, null);

        assertNull(pane.visualizerProvider(0));
    }

    @Test
    void droppedVisualAttachmentDoesNotPinItsCallbackCapture() {
        NormalPane pane = Pane.empty(1, 1);
        WeakReference<Object> capture = attachDroppedVisual(pane);
        GcSupport.awaitCollected(capture);
        pane.visual().dirty();
        Reference.reachabilityFence(pane);
    }

    private static WeakReference<Object> attachDroppedVisual(NormalPane pane) {
        Object capture = new Object();
        pane.visual().attach(0, capture::hashCode);
        return new WeakReference<>(capture);
    }

    @Test
    void identityNoOpSkipsNotificationButEqualDistinctReplacementDoesNot() {
        NormalPane pane = Pane.empty(1, 1);
        Element first = new Element.Item(new EqualItem());
        Element second = new Element.Item(new EqualItem());

        assertEquals(first, second);
        assertNotSame(first, second);
        pane.setElement(0, first);
        AtomicInteger notifications = new AtomicInteger();
        PaneSlotAttachment attachment = pane.attach(0, ignoredInvalidation -> notifications.incrementAndGet());
        pane.setElement(0, first);
        pane.setElement(0, second);

        assertEquals(1, notifications.get());
        attachment.close();
    }

    @Test
    void multipleAttachmentsCloseInConstantTimeAndIndependently() {
        NormalPane pane = Pane.empty(1, 1);
        AtomicInteger firstNotifications = new AtomicInteger();
        AtomicInteger secondNotifications = new AtomicInteger();
        PaneSlotAttachment first = pane.attach(0, ignoredInvalidation -> firstNotifications.incrementAndGet());
        PaneSlotAttachment second = pane.attach(0, ignoredInvalidation -> secondNotifications.incrementAndGet());
        pane.setElement(0, element());
        first.close();
        first.close();
        pane.setElement(0, Element.Empty.INSTANCE);

        assertTrue(first.isClosed());
        assertEquals(1, firstNotifications.get());
        assertEquals(2, secondNotifications.get());
        second.close();
    }

    @Test
    void snapshotsAndExplicitInvalidationDoNotExposeStorage() {
        NormalPane pane = Pane.empty(2, 1);
        AtomicInteger notifications = new AtomicInteger();
        PaneSlotAttachment attachment = pane.attach(1, ignoredInvalidation -> notifications.incrementAndGet());
        Element[] snapshot = pane.elements();
        snapshot[1] = element();
        pane.dirty(1);

        assertSame(Element.Empty.INSTANCE, pane.element(1));
        assertEquals(1, notifications.get());
        attachment.close();
    }

    @Test
    void observerFailuresAreIsolatedUntilAllObserversRun() {
        NormalPane pane = Pane.empty(1, 1);
        RuntimeException failure = new IllegalStateException("broken observer");
        AtomicInteger successful = new AtomicInteger();
        PaneSlotAttachment first = pane.attach(0, ignoredInvalidation -> {
            throw failure;
        });
        PaneSlotAttachment second = pane.attach(0, ignoredInvalidation -> successful.incrementAndGet());

        assertSame(failure, assertThrows(RuntimeException.class, () -> pane.setElement(0, element())));
        assertEquals(1, successful.get());
        first.close();
        second.close();
    }

    @Test
    void failedBatchGenerationLeavesEverySlotUnchanged() {
        NormalPane pane = Pane.empty(3, 1);

        assertThrows(
                IllegalStateException.class,
                () -> pane.setElements(
                        SlotSequence.all(pane.size()),
                        (ignoredSize, occurrence) -> {
                            if (occurrence == 1) {
                                throw new IllegalStateException("broken batch");
                            }
                            return element();
                        },
                        true
                )
        );
        for (int slot = 0; slot < pane.area(); slot++) {
            assertSame(Element.Empty.INSTANCE, pane.element(slot));
        }
    }

    @Test
    void fillAddAndNestedConveniencesConvergeOnCoreOperations() {
        NormalPane pane = Pane.empty(4, 3);
        Item center = new TestItem();
        Item border = new TestItem();
        pane.setItem(5, center);
        pane.fillBorders(border, false);

        assertSame(center, assertInstanceOf(Element.Item.class, pane.element(5)).item());
        assertSame(border, assertInstanceOf(Element.Item.class, pane.element(0)).item());
        assertSame(Element.Empty.INSTANCE, pane.element(6));
        Item firstAdded = new TestItem();
        Item secondAdded = new TestItem();
        pane.addItems(firstAdded, secondAdded);

        assertSame(firstAdded, assertInstanceOf(Element.Item.class, pane.element(6)).item());
        assertSame(border, assertInstanceOf(Element.Item.class, pane.element(9)).item());
        NormalPane child = Pane.empty(2, 1);
        pane.fillRectangle(1, 2, child);

        assertEquals(0, assertInstanceOf(Element.PaneLink.class, pane.element(9)).slot());
        assertEquals(1, assertInstanceOf(Element.PaneLink.class, pane.element(10)).slot());
    }

    @Test
    void builderCoversIngredientsModifiersCopyAndReusableBuilds() {
        Structure structure = Structure.of("AAB");
        Element first = element();
        Element second = element();
        AtomicInteger modifierCalls = new AtomicInteger();
        Pane.Builder<NormalPane, ?> base = Pane.builder(structure)
                .addIngredient('A', first)
                .addIngredient("B", () -> new TestItem())
                .setBackground(ItemProvider.EMPTY)
                .setFrozen(true)
                .addModifier(ignoredItem -> modifierCalls.incrementAndGet());
        NormalPane firstBuild = base.build();
        NormalPane secondBuild = base.build();
        NormalPane copiedBuild = base.copy().addIngredient("A", second).setFrozen(false).build();

        assertSame(first, firstBuild.element(0));
        assertSame(first, secondBuild.element(1));
        assertSame(second, copiedBuild.element(0));
        assertNotSame(firstBuild.element(2), secondBuild.element(2));
        assertTrue(firstBuild.frozen());
        assertFalse(copiedBuild.frozen());
        assertEquals(3, modifierCalls.get());
    }

    @Test
    void builderIngredientOverloadsConvergeOnOneSupplierTable() {
        Structure structure = Structure.of("ESPBIQG");
        Element fixed = element();
        Item direct = new TestItem();
        NormalPane child = Pane.empty(1, 1);
        AtomicInteger suppliedItems = new AtomicInteger();
        NormalPane pane = Pane.builder(structure)
                .addIngredient("E", fixed)
                .addIngredient("S", (ignoredSize, ignoredOccurrence) -> element())
                .addIngredient("P", ItemProvider.EMPTY)
                .addIngredient("B", Item.builder())
                .addIngredient("I", direct)
                .addIngredient("Q", () -> {
                    suppliedItems.incrementAndGet();
                    return new TestItem();
                })
                .addIngredient("G", child)
                .build();

        assertSame(fixed, pane.element(0));
        for (int slot : new int[]{1, 2, 3, 4, 5}) {
            assertInstanceOf(Element.Item.class, pane.element(slot));
        }

        assertSame(direct, pane.item(4));
        assertEquals(1, suppliedItems.get());
        assertSame(child, assertInstanceOf(Element.PaneLink.class, pane.element(6)).pane());
    }

    @Test
    void normalPaneHasNoMarkerOrContentState() {
        assertFalse(
                Arrays.stream(NormalPane.class.getDeclaredFields())
                        .map(field -> field.getName().toLowerCase())
                        .anyMatch(name -> name.contains("marker") || name.contains("content"))
        );
    }

    @Test
    void publicSeamsRejectInvalidSlotsAndNullElementsWithoutDuplicateChecks() {
        NormalPane pane = Pane.empty(1, 1);

        assertThrows(NullPointerException.class, () -> pane.attach(0, null));
        assertThrows(NullPointerException.class, () -> pane.setElement(0, null));
        assertThrows(IndexOutOfBoundsException.class, () -> pane.element(1));
        assertThrows(IndexOutOfBoundsException.class, () -> pane.attach(-1, ignoredInvalidation -> { }));
        assertThrows(IndexOutOfBoundsException.class, () -> pane.setElement(1, Element.Empty.INSTANCE));
    }

    private static Element element() {
        return new Element.Item(new TestItem());
    }

    private static class TestItem implements Item {
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
    }

    private static final class EqualItem extends TestItem {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualItem;
        }
        @Override
        public int hashCode() {
            return 1;
        }
    }
}
