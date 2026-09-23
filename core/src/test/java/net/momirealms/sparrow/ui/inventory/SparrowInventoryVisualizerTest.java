package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.visual.ResolvedVisual;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparrowInventoryVisualizerTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void visualizeReturnsNullWhenNothingConfigured() {
        TestInventory inventory = new TestInventory(2);

        assertSame(inventory.visual(), inventory.visual());
        assertNull(inventory.visual().visualizerProvider());
        assertNull(inventory.visual().visualizerProvider(0));
        assertNull(inventory.visual().background());
        assertNull(inventory.visualizerProvider());
        assertNull(inventory.visualizerProvider(0));
        assertNull(inventory.getBackground());
        assertNull(visualize(inventory, 0, new ItemStack(Material.DIAMOND)));
        assertNull(visualize(inventory, 1, null));
    }

    @Test
    void globalVisualizerReceivesActualItemIncludingNull() {
        TestInventory inventory = new TestInventory(2);
        ImmediateItemProvider mapped = ItemProvider.sync(ignoredContext -> new ItemStack(Material.BARRIER));
        List<ItemStack> received = new ArrayList<>();
        inventory.visual().setVisualizerProvider(stack -> {
            received.add(stack);
            return stack == null ? null : mapped;
        });

        assertSame(inventory.visual().visualizerProvider(), inventory.visualizerProvider());
        ItemStack actual = new ItemStack(Material.DIAMOND, 3);

        assertSame(mapped, visualize(inventory, 0, actual));
        assertNull(visualize(inventory, 1, null));
        assertEquals(2, received.size());
        assertSame(actual, received.get(0));
        assertNull(received.get(1));
    }

    @Test
    void visualizerSugarWrapsGlobalAndSlotItemStacks() {
        TestInventory inventory = new TestInventory(2);
        ItemStack mapped = new ItemStack(Material.BARRIER, 2);
        Function<@Nullable ItemStack, @Nullable ItemStack> visualizer = actual -> actual == null ? null : mapped;
        inventory.setVisualizerItem(visualizer);
        ItemProvider global = visualize(inventory, 0, new ItemStack(Material.DIAMOND));

        assertNotNull(global);
        mapped.setAmount(64);

        assertEquals(new ItemStack(Material.BARRIER, 2), global.provide(null).join());
        assertNull(visualize(inventory, 0, null), "null 结果应让当前视觉层放行");
        inventory.visual().setVisualizerItem(1, ignoredActual -> ItemStack.empty());
        ItemProvider slot = visualize(inventory, 1, new ItemStack(Material.DIAMOND));

        assertNotNull(slot);
        assertTrue(slot.provide(null).join().isEmpty(), "空 ItemStack 应覆盖为空视觉");
        inventory.setVisualizerItem(1, null);

        assertNotNull(visualize(inventory, 1, new ItemStack(Material.DIAMOND)));
        inventory.setVisualizerItem(null);

        assertNull(inventory.visualizerProvider());
    }

    @Test
    void slotVisualizerOverridesGlobalAndClearingRestoresGlobal() {
        TestInventory inventory = new TestInventory(2);
        ImmediateItemProvider globalMapped = ItemProvider.sync(ignoredContext -> new ItemStack(Material.STONE));
        ImmediateItemProvider slotMapped = ItemProvider.sync(ignoredContext -> new ItemStack(Material.BARRIER));
        inventory.setVisualizerProvider(ignoredStack -> globalMapped);
        inventory.visual().setVisualizerProvider(1, ignoredStack -> slotMapped);

        assertSame(globalMapped, visualize(inventory, 0, null));
        assertSame(slotMapped, visualize(inventory, 1, null));
        assertNotNull(inventory.visualizerProvider(1));
        assertNull(inventory.visualizerProvider(0));
        inventory.visual().setVisualizerProvider(1, null);

        assertSame(globalMapped, visualize(inventory, 1, null));
        assertNull(inventory.visualizerProvider(1));
    }

    @Test
    void setBackgroundMapsOnlyEmptySlotsAndClearsWithNull() {
        TestInventory inventory = new TestInventory(1);
        ImmediateItemProvider background = ItemProvider.sync(ignoredContext -> new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        inventory.setBackground(background);

        assertSame(background, inventory.getBackground());
        assertNull(inventory.visualizerProvider());
        assertSame(background, visualize(inventory, 0, null));
        assertNull(visualize(inventory, 0, new ItemStack(Material.DIAMOND)));
        inventory.setBackground((ImmediateItemProvider) null);

        assertNull(inventory.getBackground());
        assertNull(visualize(inventory, 0, null));
    }

    @Test
    void setBackgroundItemStackSnapshotsTemplate() {
        TestInventory inventory = new TestInventory(1);
        ItemStack template = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 2);
        inventory.setBackgroundItem(template);
        template.setAmount(64);

        assertNotNull(visualize(inventory, 0, null));
        assertNull(visualize(inventory, 0, new ItemStack(Material.DIAMOND)));
    }

    @Test
    void backgroundCoexistsWithVisualizersAsBottomLayer() {
        TestInventory inventory = new TestInventory(1);
        ImmediateItemProvider background = ItemProvider.sync(ignoredContext -> new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        ImmediateItemProvider mapped = ItemProvider.sync(ignoredContext -> new ItemStack(Material.BARRIER));
        inventory.setBackground(background);
        inventory.setVisualizerProvider(stack -> stack == null ? null : mapped);

        assertSame(background, inventory.getBackground());
        assertSame(mapped, visualize(inventory, 0, new ItemStack(Material.DIAMOND)));
        assertSame(background, visualize(inventory, 0, null));
        inventory.setVisualizerProvider(ignoredStack -> mapped);

        assertSame(mapped, visualize(inventory, 0, null));
    }

    @Test
    void passThroughCascadesSlotToGlobalToBackground() {
        TestInventory inventory = new TestInventory(1);
        ImmediateItemProvider background = ItemProvider.sync(ignoredContext -> new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        ImmediateItemProvider globalMapped = ItemProvider.sync(ignoredContext -> new ItemStack(Material.STONE));
        inventory.setBackground(background);
        inventory.setVisualizerProvider(stack -> stack == null ? null : globalMapped);
        inventory.setVisualizerProvider(0, ignoredStack -> null);

        assertSame(globalMapped, visualize(inventory, 0, new ItemStack(Material.DIAMOND)));
        assertSame(background, visualize(inventory, 0, null));
        inventory.setVisualizerProvider(null);

        assertNull(visualize(inventory, 0, new ItemStack(Material.DIAMOND)));
        assertSame(background, visualize(inventory, 0, null));
    }

    @Test
    void visualInvalidationsRouteToExactSlotsAndStopAfterClose() {
        TestInventory inventory = new TestInventory(2);
        List<Integer> received = new ArrayList<>();
        Subscription first = inventory.visual().attach(0, () -> received.add(0));
        Subscription second = inventory.visual().attach(1, () -> received.add(1));
        inventory.setVisualizerProvider(ignoredStack -> null);
        inventory.setVisualizerProvider(1, ignoredStack -> null);
        inventory.setBackground(ItemProvider.sync(ignoredContext -> new ItemStack(Material.STONE)));

        assertEquals(List.of(0, 1, 1, 0, 1), received);
        second.close();
        inventory.setVisualizerProvider(0, ignoredStack -> null);
        inventory.visual().dirty();

        assertEquals(List.of(0, 1, 1, 0, 1, 0, 0), received);
        first.close();
    }

    @Test
    void visualIsStableAndBindsWithoutReplayOrRequiredHandleRetention() {
        TestInventory inventory = new TestInventory(1);
        AtomicInteger invalidations = new AtomicInteger();
        Subscription attachment = inventory.visual().attach(0, invalidations::incrementAndGet);
        MutableSignal<Boolean> retainedBindingSignal = Signal.of(false);

        assertSame(inventory.visual(), inventory.visual());
        inventory.visual().bind(retainedBindingSignal);

        assertEquals(0, invalidations.get(), "bind 不补发当前 Signal 状态");
        retainedBindingSignal.set(true);

        assertEquals(1, invalidations.get(), "丢弃 bind 返回值不应结束宿主持有的绑定");
        MutableSignal<Boolean> controlledSignal = Signal.of(false);
        Subscription binding = inventory.visual().bind(controlledSignal);
        controlledSignal.set(true);

        assertEquals(2, invalidations.get());
        binding.close();
        controlledSignal.set(false);

        assertTrue(binding.isClosed());
        assertEquals(2, invalidations.get(), "显式 close 后应立即停止绑定");
        attachment.close();
    }

    @Test
    void droppedVisualAttachmentDoesNotPinItsCallbackCapture() {
        TestInventory inventory = new TestInventory(1);
        WeakReference<Object> capture = attachDroppedVisual(inventory);
        GcSupport.awaitCollected(capture);
        inventory.visual().dirty();
        Reference.reachabilityFence(inventory);
    }

    @Test
    void manualVisualDirtyDoesNotChangeContentOrPublishTransactionEvents() {
        TestInventory inventory = new TestInventory(new ItemStack[]{new ItemStack(Material.DIAMOND, 3)});
        ItemStack[] before = inventory.snapshot();
        AtomicInteger invalidations = new AtomicInteger();
        AtomicInteger postEvents = new AtomicInteger();
        Subscription visual = inventory.visual().attach(0, invalidations::incrementAndGet);
        Subscription post = inventory.subscribePostUpdate(ignoredEvent -> postEvents.incrementAndGet());
        inventory.visual().dirty();

        assertEquals(1, invalidations.get());
        assertEquals(0, postEvents.get());
        assertEquals(List.of(before), List.of(inventory.snapshot()));
        visual.close();
        post.close();
    }

    @Test
    void visualDirtyNotifiesRemainingPathsAndCombinesRuntimeFailures() {
        TestInventory inventory = new TestInventory(2);
        IllegalStateException firstFailure = new IllegalStateException("first");
        IllegalArgumentException secondFailure = new IllegalArgumentException("second");
        AtomicInteger successfulInvalidations = new AtomicInteger();
        Subscription first = inventory.visual().attach(0, () -> {
            throw firstFailure;
        });
        Subscription successful = inventory.visual().attach(0, successfulInvalidations::incrementAndGet);
        Subscription second = inventory.visual().attach(1, () -> {
            throw secondFailure;
        });
        RuntimeException thrown = assertThrows(RuntimeException.class, inventory.visual()::dirty);

        assertSame(firstFailure, thrown);
        assertEquals(List.of(secondFailure), List.of(thrown.getSuppressed()));
        assertEquals(1, successfulInvalidations.get());
        first.close();
        successful.close();
        second.close();
    }

    @Test
    void slotAccessorsRejectOutOfRangeSlots() {
        TestInventory inventory = new TestInventory(2);

        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setVisualizerProvider(2, ignoredStack -> null));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setVisualizerProvider(-1, ignoredStack -> null));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setVisualizerProvider(2, ignoredStack -> null));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setVisualizerProvider(-1, ignoredStack -> null));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.visualizerProvider(2));
        assertThrows(IndexOutOfBoundsException.class, () -> visualize(inventory, -1, null));
    }

    @Nullable
    private static ItemProvider visualize(TestInventory inventory, int slot, @Nullable ItemStack actual) {
        ResolvedVisual resolved = inventory.visual().visualizeWithBackground(slot, actual);
        return resolved == null ? null : resolved.provider();
    }

    private static WeakReference<Object> attachDroppedVisual(TestInventory inventory) {
        Object capture = new Object();
        inventory.visual().attach(0, () -> Reference.reachabilityFence(capture));
        return new WeakReference<>(capture);
    }
}
