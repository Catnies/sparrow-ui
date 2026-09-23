package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.item.AbstractItem;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.ObservableItem;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.guard.ItemGuards;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.pane.PaneSlotAttachment;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.pane.Structure;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.time.TickingTestSupport;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayedSlotPathTest {

    private ServerMock server;
    private Player player;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.player = net.momirealms.sparrow.ui.PlayerStub.addTo(this.server);
        SparrowUiTestRuntime.installPlugin();
        SparrowUiTestRuntime.installOwnership(() -> false);
        TickingTestSupport.install();
    }

    @AfterEach
    void tearDown() {
        TickingTestSupport.restore();
        SparrowUiTestRuntime.restorePlugin();
        SparrowUiTestRuntime.restoreOwnership();
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keyedDependenciesUseTheActualRenderContextForEachSlot(boolean subclass) {
        MutableKeyedSignal<Integer, Integer> slots = KeyedSignal.of(key -> 0);
        List<RenderContext> attached = new ArrayList<>();
        List<RenderContext> rendered = new ArrayList<>();
        Function<RenderContext, Integer> keyOf = context -> {
            attached.add(context);
            return context.windowSlot;
        };
        Function<RenderContext, ItemStack> renderer = context -> {
            rendered.add(context);
            return new ItemStack(Material.DIAMOND);
        };
        Item item = subclass ? new AbstractItem() {
            {
                this.dependsOn(slots, keyOf);
            }

            @Override
            @NonNull
            protected CompletableFuture<ItemStack> render(RenderContext context) {
                return CompletableFuture.completedFuture(renderer.apply(context));
            }
        } : Item.builder().setItemProvider(renderer).dependsOn(slots, keyOf).build();
        NormalPane pane = paneWith(Element.item(item));
        TestWindow window = new TestWindow(this.player);
        try (DisplayedSlotPath first = new DisplayedSlotPath(window, 2, pane, 0);
             DisplayedSlotPath second = new DisplayedSlotPath(window, 8, pane, 0)) {
            first.render();
            second.render();
            assertEquals(2, attached.size());
            assertSame(attached.get(0), rendered.get(0));
            assertSame(attached.get(1), rendered.get(1));
            assertSame(window, attached.get(0).window);
            assertSame(this.player, attached.get(0).player());
            assertEquals(RenderContext.Kind.WINDOW_SLOT, attached.get(0).kind);
            assertEquals(List.of(2, 8), attached.stream().map(context -> context.windowSlot).toList());

            window.clearDirtySlots();
            slots.set(2, 1);
            assertEquals(Set.of(2), window.dirtySlots());
            first.render();
            assertEquals(2, attached.size());
            first.close();
            window.clearDirtySlots();
            slots.set(2, 2);
            assertTrue(window.dirtySlots().isEmpty());
            slots.set(8, 1);
            assertEquals(Set.of(8), window.dirtySlots());
        }
    }

    @Test
    void observableLeafNotifiesEveryFinalWindowSlot() {
        ObservableItem item = Item.builder().build();
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(window, 2, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(window, 8, pane, 0);
        window.clearDirtySlots();
        item.notifyWindows();

        assertEquals(Set.of(2, 8), window.dirtySlots());
        first.close();
        second.close();
        window.clearDirtySlots();
        item.notifyWindows();

        assertTrue(window.dirtySlots().isEmpty());
    }

    @Test
    void nestedPaneChangeIsResolvedOnDemandAndSwitchesLeafAttachment() {
        ObservableItem oldItem = Item.builder().build();
        ObservableItem newItem = Item.builder().build();
        NormalPane child = paneWith(new Element.Item(oldItem));
        NormalPane root = paneWith(new Element.PaneLink(child, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 4, root, 0);
        window.clearDirtySlots();
        child.setElement(0, new Element.Item(newItem));

        assertEquals(Set.of(4), window.dirtySlots());
        path.resolve();
        window.clearDirtySlots();
        oldItem.notifyWindows();

        assertTrue(window.dirtySlots().isEmpty());
        newItem.notifyWindows();

        assertEquals(Set.of(4), window.dirtySlots());
        path.close();
    }

    @Test
    void unchangedStructureKeepsLeafAttachment() {
        CountingItem item = new CountingItem();
        NormalPane root = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 8, root, 0);

        assertEquals(1, item.attachCount());
        path.resolve();

        assertEquals(1, item.attachCount());
        assertEquals(0, item.closeCount());
        path.close();

        assertEquals(1, item.closeCount());
    }

    @Test
    void equalButDistinctItemLeafIsStillReplaced() {
        ValueItem first = new ValueItem("shop", ItemProvider.constant(new ItemStack(Material.DIAMOND)));
        ValueItem second = new ValueItem("shop", ItemProvider.constant(new ItemStack(Material.EMERALD)));

        assertEquals(first, second, "前提: 使用方认定这两个 Item 相等");
        NormalPane pane = paneWith(Element.item(first));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.DIAMOND, path.render().getType());
        assertEquals(1, first.attachCount());
        pane.setElement(0, Element.item(second));

        assertEquals(Material.EMERALD, path.render().getType(), "Pane 里放的是哪个就渲染哪个");
        assertEquals(1, first.closeCount(), "被换掉的 Item 要摘挂载");
        assertEquals(1, second.attachCount(), "换上来的 Item 要挂上");
        path.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 3));

        assertEquals(0, first.clickCount(), "点击不该再落到被换掉的 Item");
        assertEquals(1, second.clickCount());
        path.close();
    }

    @Test
    void renderRemembersForTheSlotAndTheNextRenderOverwrites() {
        AtomicInteger renders = new AtomicInteger();
        Item item = Item.builder()
                .setItemProvider(context -> {
                    context.remember("render-" + renders.incrementAndGet());
                    return new ItemStack(Material.DIAMOND);
                })
                .build();
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, paneWith(Element.item(item)), 0);
        path.render();

        assertEquals("render-1", path.remembered());
        path.render();

        assertEquals("render-2", path.remembered(), "下一次渲染覆盖上一次记的");
        path.close();

        assertNull(path.remembered(), "路径关了就清");
    }

    @Test
    void clickReadsWhatTheSlotRemembered() {
        AtomicReference<Object> seen = new AtomicReference<>();
        Item item = Item.builder()
                .setItemProvider(context -> {
                    context.remember("variant-b");
                    return new ItemStack(Material.DIAMOND);
                })
                .addClickHandler(click -> seen.set(click.remembered()))
                .build();
        RememberingWindow window = new RememberingWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, paneWith(Element.item(item)), 0);
        window.path = path;
        path.render();
        path.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 3));

        assertEquals("variant-b", seen.get(), "点击取回的就是这次渲染记下的");
        path.close();
    }

    @Test
    void replacingTheLeafItemClearsWhatThePreviousOneRemembered() {
        Item remembering = Item.builder()
                .setItemProvider(context -> {
                    context.remember("old");
                    return new ItemStack(Material.DIAMOND);
                })
                .build();
        Item silent = Item.simple(new ItemStack(Material.EMERALD));
        NormalPane pane = paneWith(Element.item(remembering));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();

        assertEquals("old", path.remembered());
        pane.setElement(0, Element.item(remembering));
        path.render();

        assertEquals("old", path.remembered());
        pane.setElement(0, Element.item(silent));

        assertEquals(Material.EMERALD, path.render().getType());
        assertNull(path.remembered());
        path.close();
    }

    @Test
    void cursorAndOffSlotContextsIgnoreRemember() {
        TestWindow window = new TestWindow(this.player);
        RenderContext.cursor(window).remember("x");
        RenderContext.offSlot(window).remember("x");
        new RenderContext(window, 0).remember("x");

        assertNull(window.rememberedAt(0), "没有路径可记, 什么也不发生");
    }

    @Test
    void lateResultFromAnEqualButReplacedItemIsNotDisplayed() {
        CompletableFuture<ItemStack> pending = new CompletableFuture<>();
        ValueItem first = new ValueItem("shop", ignoredContext -> pending);
        ValueItem second = new ValueItem("shop", ItemProvider.constant(new ItemStack(Material.EMERALD)));
        NormalPane pane = paneWith(Element.item(first));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();
        pane.setElement(0, Element.item(second));

        assertEquals(Material.EMERALD, path.render().getType());
        pending.complete(new ItemStack(Material.BARRIER));

        assertEquals(Material.EMERALD, path.render().getType(), "被换掉的 Item 算出来的结果不能显示");
        path.close();
    }

    @Test
    void closingThePathReleasesThePeriodicRefreshSubscription() {
        Signal<Long> clock = Signals.everyTicks(20);
        ObservableItem item = Item.builder().updatePeriodically(20).build();
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(1, TickingTestSupport.entryCountOf(clock), "周期刷新应当在共享 tick 视图上挂一条订阅");
        path.close();

        assertEquals(0, TickingTestSupport.entryCountOf(clock), "关闭显示路径必须当场把它摘掉");
        Reference.reachabilityFence(clock);
    }

    @Test
    void droppedPathStillReleasesThePeriodicRefreshSubscription() {
        Signal<Long> clock = Signals.everyTicks(20);
        WeakReference<?> probe = this.wireDroppedPeriodicPath();

        assertEquals(1, TickingTestSupport.entryCountOf(clock));
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(1);

        assertEquals(0, TickingTestSupport.entryCountOf(clock), "忘了 close 也要靠弱订阅自愈");
        Reference.reachabilityFence(clock);
    }

    private WeakReference<?> wireDroppedPeriodicPath() {
        ObservableItem item = Item.builder().updatePeriodically(20).build();
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        return new WeakReference<>(new DisplayedSlotPath(window, 3, pane, 0));
    }

    @Test
    void closedWindowsOnASharedLongLivedPaneAreCollectable() {
        ObservableItem item = Item.builder().updatePeriodically(20).build();
        NormalPane pane = paneWith(new Element.Item(item));
        Signal<Long> clock = Signals.everyTicks(20);
        List<WeakReference<?>> probes = new java.util.ArrayList<>();
        for (int round = 0; round < 3; round++) {
            probes.add(this.openRenderClose(pane));
        }
        for (WeakReference<?> probe : probes) {
            GcSupport.awaitCollected(probe);
        }

        assertEquals(0, TickingTestSupport.entryCountOf(clock), "关过的窗口不该在共享 tick 视图上留条目");
        Reference.reachabilityFence(pane);
        Reference.reachabilityFence(item);
        Reference.reachabilityFence(clock);
    }

    private WeakReference<?> openRenderClose(NormalPane pane) {
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();
        path.close();
        return new WeakReference<>(window);
    }

    @Test
    void leafSwapKeepsEnclosingPaneSubscriptions() {
        ObservableItem first = Item.builder().build();
        ObservableItem second = Item.builder().build();
        NormalPane child = paneWith(new Element.Item(first));
        NormalPane root = paneWith(new Element.PaneLink(child, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 9, root, 0);
        child.setElement(0, new Element.Item(second));
        path.resolve();
        window.clearDirtySlots();
        child.setElement(0, Element.Empty.INSTANCE);

        assertEquals(Set.of(9), window.dirtySlots());
        path.resolve();
        window.clearDirtySlots();
        root.setElement(0, Element.Empty.INSTANCE);

        assertEquals(Set.of(9), window.dirtySlots());
        path.close();
    }

    @Test
    void divergingMiddleLayerDropsDeeperSubscriptions() {
        ObservableItem oldLeaf = Item.builder().build();
        ObservableItem newLeaf = Item.builder().build();
        NormalPane firstChild = paneWith(new Element.Item(oldLeaf));
        NormalPane secondChild = paneWith(new Element.Item(newLeaf));
        NormalPane root = paneWith(new Element.PaneLink(firstChild, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 10, root, 0);
        root.setElement(0, new Element.PaneLink(secondChild, 0));
        path.resolve();
        window.clearDirtySlots();
        firstChild.setElement(0, Element.Empty.INSTANCE);
        oldLeaf.notifyWindows();

        assertTrue(window.dirtySlots().isEmpty());
        secondChild.setElement(0, Element.Empty.INSTANCE);

        assertEquals(Set.of(10), window.dirtySlots());
        path.close();
    }

    @Test
    void detectsDirectAndIndirectCyclesButAllowsSharedChildPane() {
        TestWindow window = new TestWindow(this.player);
        NormalPane direct = NormalPane.empty(new PaneSize(1, 1));
        direct.setElement(0, new Element.PaneLink(direct, 0));

        assertThrows(
                IllegalStateException.class,
                () -> new DisplayedSlotPath(window, 0, direct, 0)
        );
        NormalPane first = NormalPane.empty(new PaneSize(1, 1));
        NormalPane second = NormalPane.empty(new PaneSize(1, 1));
        first.setElement(0, new Element.PaneLink(second, 0));
        second.setElement(0, new Element.PaneLink(first, 0));

        assertThrows(
                IllegalStateException.class,
                () -> new DisplayedSlotPath(window, 1, first, 0)
        );
        ObservableItem item = Item.builder().build();
        NormalPane shared = paneWith(new Element.Item(item));
        NormalPane root = NormalPane.empty(new PaneSize(2, 1));
        root.setElement(0, new Element.PaneLink(shared, 0));
        root.setElement(1, new Element.PaneLink(shared, 0));
        DisplayedSlotPath left = new DisplayedSlotPath(window, 2, root, 0);
        DisplayedSlotPath right = new DisplayedSlotPath(window, 3, root, 1);
        window.clearDirtySlots();
        item.notifyWindows();

        assertEquals(Set.of(2, 3), window.dirtySlots());
        left.close();
        right.close();
    }

    @Test
    void failedResolutionKeepsPreviousPathActive() {
        ObservableItem oldItem = Item.builder().build();
        NormalPane root = paneWith(new Element.Item(oldItem));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 6, root, 0);
        root.setElement(0, new Element.Item(new FailingAttachItem()));
        window.clearDirtySlots();

        assertThrows(IllegalStateException.class, path::resolve);
        oldItem.notifyWindows();

        assertEquals(Set.of(6), window.dirtySlots());
        path.close();
    }

    @Test
    void retiredPathIgnoresLateNotificationFromAnotherThread() {
        LateNotifyingItem oldItem = new LateNotifyingItem();
        ObservableItem newItem = Item.builder().build();
        NormalPane root = paneWith(new Element.Item(oldItem));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 5, root, 0);
        root.setElement(0, new Element.Item(newItem));
        path.resolve();
        window.clearDirtySlots();
        oldItem.notifyLateFromAnotherThread();

        assertTrue(window.dirtySlots().isEmpty());
        newItem.notifyWindows();

        assertEquals(Set.of(5), window.dirtySlots());
        path.close();
    }

    @Test
    void concurrentNotificationDuringCandidateAttachmentIsReplayedAfterCommit() {
        NormalPane root = paneWith(Element.Empty.INSTANCE);
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 7, root, 0);
        root.setElement(0, new Element.Item(new ConcurrentlyInvalidatingItem()));
        window.clearDirtySlots();
        path.resolve();

        assertEquals(Set.of(7), window.dirtySlots());
        assertEquals(1, window.dirtyCallCount());
        path.close();
    }

    @Test
    void inventoryLinkRendersLiveItemAndFallsBackWhenEmpty() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        ItemStack rendered = path.render();

        assertEquals(Material.DIAMOND, rendered.getType());
        assertEquals(5, rendered.getAmount());
        assertSame(inventory.unsafeItemAt(0), rendered);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.EMERALD, 2));

        assertSame(inventory.unsafeItemAt(0), path.render());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, null);

        assertTrue(path.render().isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> Element.inventory(inventory, 2));
        path.close();
    }

    @Test
    void futureProviderCompletionMarksOnlyItsDisplayedSlotDirty() {
        CompletableFuture<ItemStack> result = new CompletableFuture<>();
        AtomicInteger submissions = new AtomicInteger();
        Item item = Item.simple(context -> {
            submissions.incrementAndGet();
            return result;
        });
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 6, pane, 0);
        window.clearDirtySlots();

        assertTrue(path.render().isEmpty());
        assertEquals(1, submissions.get());
        ItemStack computed = new ItemStack(Material.DIAMOND);
        result.complete(computed);

        assertEquals(Set.of(6), window.dirtySlots());
        window.clearDirtySlots();

        assertSame(computed, path.render());
        assertEquals(1, submissions.get());
        path.close();
    }

    @Test
    void sharedProviderKeepsFutureResultsAndCompletionReceiptsPerWindow() {
        CompletableFuture<ItemStack> firstResult = new CompletableFuture<>();
        CompletableFuture<ItemStack> secondResult = new CompletableFuture<>();
        List<CompletableFuture<ItemStack>> results = List.of(firstResult, secondResult);
        AtomicInteger requests = new AtomicInteger();
        Item item = Item.simple(context -> results.get(requests.getAndIncrement()));
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow firstWindow = new TestWindow(this.player);
        TestWindow secondWindow = new TestWindow(this.server.addPlayer());
        DisplayedSlotPath firstPath = new DisplayedSlotPath(firstWindow, 2, pane, 0);
        DisplayedSlotPath secondPath = new DisplayedSlotPath(secondWindow, 5, pane, 0);
        firstWindow.clearDirtySlots();
        secondWindow.clearDirtySlots();

        assertTrue(firstPath.render().isEmpty());
        assertTrue(secondPath.render().isEmpty());
        assertEquals(2, requests.get());
        ItemStack first = new ItemStack(Material.DIAMOND);
        firstResult.complete(first);

        assertEquals(Set.of(2), firstWindow.dirtySlots());
        assertTrue(secondWindow.dirtySlots().isEmpty());
        firstWindow.clearDirtySlots();

        assertSame(first, firstPath.render());
        ItemStack second = new ItemStack(Material.EMERALD);
        secondResult.complete(second);

        assertTrue(firstWindow.dirtySlots().isEmpty());
        assertEquals(Set.of(5), secondWindow.dirtySlots());
        secondWindow.clearDirtySlots();

        assertSame(second, secondPath.render());
        assertEquals(2, requests.get());
        firstPath.close();
        secondPath.close();
    }

    @Test
    void inventoryTransactionsMarkEveryObservingWindowSlotDirty() {
        VirtualInventory inventory = new VirtualInventory(1);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(window, 2, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(window, 8, pane, 0);
        window.clearDirtySlots();
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 1));

        assertEquals(Set.of(2, 8), window.dirtySlots());
        first.close();
        second.close();
        window.clearDirtySlots();
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));

        assertTrue(window.dirtySlots().isEmpty());
    }

    @Test
    void structuralChangeAwayFromInventoryLinkClosesOldSubscription() {
        VirtualInventory inventory = new VirtualInventory(1);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 5, pane, 0);
        pane.setElement(0, Element.Empty.INSTANCE);
        path.resolve();
        window.clearDirtySlots();
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 1));

        assertTrue(window.dirtySlots().isEmpty());
        path.close();
    }

    @Test
    void nestedPaneLinkReachesInventoryLink() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 4));
        NormalPane child = paneWith(Element.inventory(inventory, 0));
        NormalPane root = paneWith(new Element.PaneLink(child, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 6, root, 0);
        window.clearDirtySlots();

        assertEquals(4, path.render().getAmount());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 7));

        assertEquals(Set.of(6), window.dirtySlots());
        assertEquals(7, path.render().getAmount());
        path.close();
    }

    @Test
    void emptyInventorySlotDoesNotUsePaneBackground() {
        VirtualInventory inventory = new VirtualInventory(1);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        ItemStack backgroundStack = new ItemStack(Material.EMERALD);
        pane.setBackground(ItemProvider.sync(ignoredContext -> backgroundStack.clone()));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 7, pane, 0);

        assertTrue(path.render().isEmpty());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));

        assertEquals(Material.DIAMOND, path.render().getType());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, null);

        assertTrue(path.render().isEmpty());
        path.close();
    }

    @Test
    void emptySlotRendersOneStableInstanceAcrossFrames() {
        NormalPane pane = paneWith(Element.empty());
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        ItemStack first = path.render();
        ItemStack second = path.render();

        assertTrue(first.isEmpty());
        assertSame(first, second);
        path.close();
    }

    @Test
    void inventoryLinkOnlyObservesTransactionsOnItsOwnSlot() {
        VirtualInventory inventory = new VirtualInventory(3);
        NormalPane pane = paneWith(Element.inventory(inventory, 1));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 4, pane, 0);
        window.clearDirtySlots();
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.STONE));

        assertTrue(window.dirtySlots().isEmpty());
        inventory.setItem(UpdateReason.Program.INSTANCE, 2, new ItemStack(Material.DIAMOND));

        assertTrue(window.dirtySlots().isEmpty());
        inventory.setItem(UpdateReason.Program.INSTANCE, 1, new ItemStack(Material.EMERALD, 3));

        assertEquals(Set.of(4), window.dirtySlots());
        assertEquals(Material.EMERALD, path.render().getType());
        path.close();
    }

    @Test
    void inventoryVisualizerReplacesDisplayedItemUntilCleared() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        ImmediateItemProvider disguise = ItemProvider.sync(ignoredContext -> new ItemStack(Material.BARRIER));
        inventory.setVisualizerProvider(stack -> stack == null ? null : disguise);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.BARRIER, path.render().getType());
        inventory.setVisualizerProvider(null);

        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void asyncVisualizerShowsRealItemWhileComputingThenSwapsIn() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        inventory.visual().setVisualizerProvider(actual -> actual == null ? null : ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        });
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.DIAMOND, path.render().getType());
        assertEquals(1, submissions.size());
        window.clearDirtySlots();
        ItemStack computed = new ItemStack(Material.BARRIER);
        submissions.getFirst().complete(computed);

        assertEquals(Set.of(3), window.dirtySlots());
        assertSame(computed, path.render());
        assertEquals(1, submissions.size(), "配置未变不该重新起算");
        path.close();
    }

    @Test
    void asyncVisualizerFallsBackToItsPlaceholderWhileComputing() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        ItemStack placeholder = new ItemStack(Material.PAPER);
        inventory.visual().setVisualizerProvider(
                actual -> actual == null ? null : ignoredContext -> new CompletableFuture<>(),
                ItemProvider.constant(placeholder)
        );
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(placeholder, path.render());
        path.close();
    }

    @Test
    void asyncVisualizerResultIsAbandonedWhenTheSlotContentChanges() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        inventory.visual().setVisualizerProvider(actual -> actual == null ? null : ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        });
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();
        submissions.getFirst().complete(new ItemStack(Material.BARRIER));

        assertEquals(Material.BARRIER, path.render().getType());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.EMERALD, 1));

        assertEquals(Material.EMERALD, path.render().getType());
        assertEquals(2, submissions.size());
        path.close();
    }

    @Test
    void asyncVisualizerResultIsAbandonedWhenTheSlotMovesToAnotherLeaf() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        inventory.setItem(UpdateReason.Program.INSTANCE, 1, new ItemStack(Material.EMERALD, 5));
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        inventory.visual().setVisualizerProvider(actual -> actual == null ? null : ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        });
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();
        ItemStack firstVisual = new ItemStack(Material.BARRIER);
        submissions.getFirst().complete(firstVisual);

        assertSame(firstVisual, path.render());
        pane.setElement(0, Element.inventory(inventory, 1));

        assertEquals(Material.EMERALD, path.render().getType(), "换了终点就不能再显示上一个终点算出来的结果");
        assertEquals(2, submissions.size());
        ItemStack secondVisual = new ItemStack(Material.BEDROCK);
        submissions.get(1).complete(secondVisual);

        assertSame(secondVisual, path.render());
        path.close();
    }

    @Test
    void asyncVisualizerResultIsAbandonedWhenTheInventoryRetires() {
        MortalStorage storage = new MortalStorage(1);
        storage.contents[0] = new ItemStack(Material.DIAMOND, 5);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        inventory.visual().setVisualizerProvider(ignoredActual -> ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        });
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();
        submissions.getFirst().complete(new ItemStack(Material.BARRIER));

        assertEquals(Material.BARRIER, path.render().getType());
        inventory.retire();

        assertEquals(Material.AIR, path.render().getType(), "退役后不能再显示基于旧内容算出的结果");
        assertEquals(2, submissions.size(), "退役后应当按空内容重新起算");
        path.close();
    }

    @Test
    void inFlightAsyncVisualResultIsRefusedAfterTheInventoryRetires() {
        MortalStorage storage = new MortalStorage(1);
        storage.contents[0] = new ItemStack(Material.DIAMOND, 5);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        inventory.visual().setVisualizerProvider(ignoredActual -> ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        });
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        path.render();
        inventory.retire();
        path.render();
        submissions.getFirst().complete(new ItemStack(Material.BARRIER));

        assertEquals(Material.AIR, path.render().getType(), "退役前发起的在飞结果不能被采用");
        path.close();
    }

    @Test
    void asyncVisualizerPassesThroughToTheLowerLayerWhenItReturnsNull() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setBackgroundItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        inventory.visual().setVisualizerProvider(actual -> actual == null ? null : ignoredContext -> new CompletableFuture<>());
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, path.render().getType());
        path.close();
    }

    @Test
    void asyncPaneBackgroundShowsNothingUntilItCompletes() {
        CompletableFuture<ItemStack> result = new CompletableFuture<>();
        AtomicInteger submissions = new AtomicInteger();
        NormalPane root = NormalPane.builder(new PaneSize(1, 1))
                .setBackground(ignoredContext -> {
                    submissions.incrementAndGet();
                    return result;
                })
                .build();
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, root, 0);

        assertTrue(path.render().isEmpty());
        assertEquals(1, submissions.get());
        window.clearDirtySlots();
        ItemStack computed = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        result.complete(computed);

        assertEquals(Set.of(3), window.dirtySlots());
        assertSame(computed, path.render());
        assertEquals(1, submissions.get(), "背景来源没换就不该重算");
        path.close();
    }

    @Test
    void asyncInventoryBackgroundShowsNothingUntilItCompletes() {
        VirtualInventory inventory = new VirtualInventory(1);
        CompletableFuture<ItemStack> result = new CompletableFuture<>();
        inventory.setBackground(ignoredContext -> result);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertTrue(path.render().isEmpty());
        window.clearDirtySlots();
        ItemStack computed = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        result.complete(computed);

        assertEquals(Set.of(3), window.dirtySlots());
        assertSame(computed, path.render());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));

        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void inventoryBackgroundRendersOnlyWhileSlotIsEmpty() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setBackgroundItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, path.render().getType());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));

        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void inventoryBackgroundSurvivesVisualizerAsBottomLayer() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setBackgroundItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        ImmediateItemProvider disguise = ItemProvider.sync(ignoredContext -> new ItemStack(Material.BARRIER));
        inventory.setVisualizerProvider(stack -> stack == null ? null : disguise);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, path.render().getType());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));

        assertEquals(Material.BARRIER, path.render().getType());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, null);

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, path.render().getType());
        inventory.setBackground((ImmediateItemProvider) null);

        assertTrue(path.render().isEmpty());
        path.close();
    }

    @Test
    void visualizerChangesMarkOnlyLinkedWindowSlotsDirty() {
        VirtualInventory inventory = new VirtualInventory(2);
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 4, pane, 0);
        window.clearDirtySlots();
        inventory.setVisualizerProvider(1, ignoredStack -> null);

        assertTrue(window.dirtySlots().isEmpty());
        inventory.setVisualizerProvider(0, ignoredStack -> null);

        assertEquals(Set.of(4), window.dirtySlots());
        window.clearDirtySlots();
        inventory.setVisualizerProvider(ignoredStack -> null);

        assertEquals(Set.of(4), window.dirtySlots());
        path.close();
        window.clearDirtySlots();
        inventory.setVisualizerProvider(ignoredStack -> null);

        assertTrue(window.dirtySlots().isEmpty());
    }

    @Test
    void inventoryManualVisualDirtyMarksEveryWindowAndSameSlotAlias() {
        VirtualInventory inventory = new VirtualInventory(2);
        NormalPane firstSlot = paneWith(Element.inventory(inventory, 0));
        NormalPane secondSlot = paneWith(Element.inventory(inventory, 1));
        TestWindow firstWindow = new TestWindow(this.player);
        TestWindow secondWindow = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(firstWindow, 2, firstSlot, 0);
        DisplayedSlotPath alias = new DisplayedSlotPath(firstWindow, 6, firstSlot, 0);
        DisplayedSlotPath otherWindow = new DisplayedSlotPath(secondWindow, 4, firstSlot, 0);
        DisplayedSlotPath otherSlot = new DisplayedSlotPath(secondWindow, 8, secondSlot, 0);
        firstWindow.clearDirtySlots();
        secondWindow.clearDirtySlots();
        inventory.visual().dirty();

        assertEquals(Set.of(2, 6), firstWindow.dirtySlots());
        assertEquals(Set.of(4, 8), secondWindow.dirtySlots());
        first.close();
        alias.close();
        otherWindow.close();
        otherSlot.close();
    }

    @Test
    void paneVisualizerOverridesItemLeafUntilCleared() {
        Item item = Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build();
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.DIAMOND, path.render().getType());
        pane.visual().setVisualizerItem(0, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Material.BARRIER, path.render().getType());
        pane.visual().setVisualizerItem(0, null);

        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void paneVisualizerOverridesInventoryVisualizer() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        inventory.setVisualizerItem(ignoredActual -> new ItemStack(Material.EMERALD));
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.EMERALD, path.render().getType());
        pane.visual().setVisualizerItem(0, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Material.BARRIER, path.render().getType());
        pane.visual().setVisualizerItem(0, null);

        assertEquals(Material.EMERALD, path.render().getType());
        path.close();
    }

    @Test
    void outerPaneLayerAndPerSlotLayerWinTheCascade() {
        Item item = Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build();
        NormalPane child = paneWith(new Element.Item(item));
        NormalPane root = paneWith(new Element.PaneLink(child, 0));
        child.visual().setVisualizerProvider(ignoredActual -> ItemProvider.sync(ignoredContext -> new ItemStack(Material.GOLD_INGOT)));
        child.visual().setVisualizerItem(0, ignoredActual -> new ItemStack(Material.EMERALD));
        root.visual().setVisualizerItem(0, ignoredActual -> new ItemStack(Material.BARRIER));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, root, 0);

        assertEquals(Material.BARRIER, path.render().getType());
        root.visual().setVisualizerItem(0, null);

        assertEquals(Material.EMERALD, path.render().getType());
        child.visual().setVisualizerItem(0, null);

        assertEquals(Material.GOLD_INGOT, path.render().getType());
        child.visual().setVisualizerProvider(null);

        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void paneVisualizerReceivesLeafContentOnlyFromInventoryLink() {
        AtomicReference<ItemStack> lastInput = new AtomicReference<>(new ItemStack(Material.STONE));
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        NormalPane pane = NormalPane.empty(new PaneSize(3, 1));
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, new Element.Item(Item.builder().setItemProviderConstant(new ItemStack(Material.GOLD_INGOT)).build()));
        pane.visual().setVisualizerProvider(actual -> {
            lastInput.set(actual);
            return null;
        });
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath inventoryPath = new DisplayedSlotPath(window, 0, pane, 0);
        DisplayedSlotPath itemPath = new DisplayedSlotPath(window, 1, pane, 1);
        DisplayedSlotPath emptyPath = new DisplayedSlotPath(window, 2, pane, 2);
        inventoryPath.render();

        assertEquals(Material.DIAMOND, lastInput.get().getType());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, null);
        inventoryPath.render();

        assertNull(lastInput.get());
        lastInput.set(new ItemStack(Material.STONE));
        itemPath.render();

        assertNull(lastInput.get());
        lastInput.set(new ItemStack(Material.STONE));
        emptyPath.render();

        assertNull(lastInput.get());
        inventoryPath.close();
        itemPath.close();
        emptyPath.close();
    }

    @Test
    void paneVisualChangesBypassStructuralObserversButStillRender() {
        Item item = Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build();
        NormalPane pane = paneWith(new Element.Item(item));
        AtomicInteger structuralNotifications = new AtomicInteger();
        PaneSlotAttachment observer = pane.attach(0, ignoredPane -> structuralNotifications.incrementAndGet());
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        window.clearDirtySlots();
        pane.visual().setVisualizerItem(0, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Set.of(3), window.dirtySlots());
        assertEquals(Material.BARRIER, path.render().getType());
        window.clearDirtySlots();
        pane.visual().setVisualizerProvider(ignoredActual -> null);

        assertEquals(Set.of(3), window.dirtySlots());
        window.clearDirtySlots();
        pane.setBackground(ItemProvider.sync(ignoredContext -> new ItemStack(Material.EMERALD)));

        assertEquals(Set.of(3), window.dirtySlots());
        assertEquals(0, structuralNotifications.get());
        observer.close();
        path.close();
    }

    @Test
    void paneBackgroundAppliesImmediatelyAndDeepestLayerWins() {
        NormalPane child = paneWith(Element.empty());
        NormalPane root = paneWith(new Element.PaneLink(child, 0));
        root.setBackground(ItemProvider.sync(ignoredContext -> new ItemStack(Material.EMERALD)));
        child.setBackground(ItemProvider.sync(ignoredContext -> new ItemStack(Material.DIAMOND)));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, root, 0);

        assertEquals(Material.DIAMOND, path.render().getType());
        child.setBackground((ItemProvider) null);

        assertEquals(Material.EMERALD, path.render().getType());
        root.setBackground((ItemProvider) null);

        assertTrue(path.render().isEmpty());
        path.close();
    }

    @Test
    void paneLayerSwitchAbandonsInFlightComputation() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        pane.visual().setVisualizerProvider(0, ignoredActual -> ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        });
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.DIAMOND, path.render().getType());
        assertEquals(1, submissions.size());
        pane.visual().setVisualizerProvider(0, null);

        assertEquals(Material.DIAMOND, path.render().getType());
        window.clearDirtySlots();
        submissions.getFirst().complete(new ItemStack(Material.BARRIER));

        assertTrue(window.dirtySlots().isEmpty());
        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void paneVisualizerAppliesToEveryViewerOfTheSharedPane() {
        Item item = Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build();
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow firstWindow = new TestWindow(this.player);
        TestWindow secondWindow = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(firstWindow, 2, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(secondWindow, 6, pane, 0);
        firstWindow.clearDirtySlots();
        secondWindow.clearDirtySlots();
        pane.visual().setVisualizerItem(0, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Set.of(2), firstWindow.dirtySlots());
        assertEquals(Set.of(6), secondWindow.dirtySlots());
        assertEquals(Material.BARRIER, first.render().getType());
        assertEquals(Material.BARRIER, second.render().getType());
        first.close();
        second.close();
    }

    @Test
    void windowVisualizerOverridesEveryLowerLayer() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        inventory.setVisualizerItem(ignoredActual -> new ItemStack(Material.EMERALD));
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        pane.setVisualizerItem(0, ignoredActual -> new ItemStack(Material.GOLD_INGOT));
        TestWindow window = new TestWindow(this.player);
        AtomicReference<ItemStack> windowInput = new AtomicReference<>();
        window.visual().setVisualizerProvider(ignoredActual -> ItemProvider.sync(ignoredContext -> new ItemStack(Material.IRON_INGOT)));
        window.visual().setVisualizerItem(3, actual -> {
            windowInput.set(actual);
            return new ItemStack(Material.BARRIER);
        });
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);

        assertEquals(Material.BARRIER, path.render().getType());
        assertEquals(Material.DIAMOND, windowInput.get().getType());
        window.visual().setVisualizerItem(3, null);

        assertEquals(Material.IRON_INGOT, path.render().getType());
        window.visual().setVisualizerProvider(null);

        assertEquals(Material.GOLD_INGOT, path.render().getType());
        pane.setVisualizerItem(0, null);

        assertEquals(Material.EMERALD, path.render().getType());
        path.close();
    }

    @Test
    void windowVisualizerAffectsOnlyItsOwnWindow() {
        Item item = Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build();
        NormalPane pane = paneWith(new Element.Item(item));
        TestWindow firstWindow = new TestWindow(this.player);
        TestWindow secondWindow = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(firstWindow, 3, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(secondWindow, 3, pane, 0);
        firstWindow.clearDirtySlots();
        secondWindow.clearDirtySlots();
        firstWindow.visual().setVisualizerItem(3, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Set.of(3), firstWindow.dirtySlots());
        assertTrue(secondWindow.dirtySlots().isEmpty());
        assertEquals(Material.BARRIER, first.render().getType());
        assertEquals(Material.DIAMOND, second.render().getType());
        first.close();
        second.close();
    }

    @Test
    void windowVisualChangesMarkOnlyAttachedSlotsDirty() {
        Item item = Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build();
        NormalPane pane = NormalPane.empty(new PaneSize(2, 1));
        pane.setElement(0, new Element.Item(item));
        pane.setElement(1, new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(window, 2, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(window, 6, pane, 1);
        window.clearDirtySlots();
        window.visual().setVisualizerItem(2, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Set.of(2), window.dirtySlots());
        window.clearDirtySlots();
        window.visual().setVisualizerProvider(ignoredActual -> null);

        assertEquals(Set.of(2, 6), window.dirtySlots());
        first.close();
        second.close();
        window.clearDirtySlots();
        window.visual().setVisualizerProvider(ignoredActual -> null);

        assertTrue(window.dirtySlots().isEmpty());
    }

    @Test
    void animationCoversEveryVisualLayerAndReceivesLeafContent() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        inventory.setVisualizerItem(ignoredActual -> new ItemStack(Material.EMERALD));
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        pane.setVisualizerItem(0, ignoredActual -> new ItemStack(Material.GOLD_INGOT));
        TestWindow window = new TestWindow(this.player);
        window.visual().setVisualizerItem(3, ignoredActual -> new ItemStack(Material.IRON_INGOT));
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        AtomicReference<ItemStack> frameInput = new AtomicReference<>();
        AnimationHandle handle = window.visual().play(AnimationDefinition.of(new int[]{3}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> {
            frameInput.set(actual);
            return ItemProvider.constant(new ItemStack(Material.BARRIER));
        }));

        assertEquals(Material.BARRIER, path.render().getType());
        assertEquals(Material.DIAMOND, frameInput.get().getType());
        handle.cancel();

        assertEquals(Material.IRON_INGOT, path.render().getType());
        path.close();
    }

    @Test
    void animationFrameAdvancesThroughLeafInvalidation() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        AtomicReference<Material> frame = new AtomicReference<>(Material.GOLD_INGOT);
        window.visual().play(AnimationDefinition.of(new int[]{3}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> ItemProvider.constant(new ItemStack(frame.get()))));

        assertEquals(Material.GOLD_INGOT, path.render().getType());
        frame.set(Material.BARRIER);
        window.clearDirtySlots();
        window.visual().dirty();

        assertEquals(Set.of(3), window.dirtySlots(), "视觉失效应经既有路由到达展示槽位");
        assertEquals(Material.BARRIER, path.render().getType());
        path.close();
    }

    @Test
    void cancelledAnimationAbandonsInFlightComputation() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        List<CompletableFuture<ItemStack>> submissions = new java.util.ArrayList<>();
        NormalPane pane = paneWith(Element.inventory(inventory, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, pane, 0);
        AnimationHandle handle = pane.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> ignoredContext -> {
            CompletableFuture<ItemStack> submission = new CompletableFuture<>();
            submissions.add(submission);
            return submission;
        }));

        assertEquals(Material.DIAMOND, path.render().getType());
        assertEquals(1, submissions.size());
        handle.cancel();

        assertEquals(Material.DIAMOND, path.render().getType());
        window.clearDirtySlots();
        submissions.getFirst().complete(new ItemStack(Material.BARRIER));

        assertTrue(window.dirtySlots().isEmpty());
        assertEquals(Material.DIAMOND, path.render().getType());
        path.close();
    }

    @Test
    void staggeredFramesRevealInventoryResultsInOrder() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND));
        inventory.setItem(UpdateReason.Program.INSTANCE, 1, new ItemStack(Material.EMERALD));
        inventory.setItem(UpdateReason.Program.INSTANCE, 2, new ItemStack(Material.GOLD_INGOT));
        NormalPane pane = NormalPane.empty(new PaneSize(3, 1));
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.inventory(inventory, 1));
        pane.setElement(2, Element.inventory(inventory, 2));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(window, 0, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(window, 1, pane, 1);
        DisplayedSlotPath third = new DisplayedSlotPath(window, 2, pane, 2);
        List<AnimationHandle.FinishReason> reasons = new java.util.ArrayList<>();
        pane.visual().play(AnimationDefinition.staggeredFrames(
                SlotSequence.of(new PaneSize(3, 1), 0, 1, 2), 2, 2,
                List.of(new ItemStack(Material.TNT)), new ItemStack(Material.BARRIER)
        )).whenFinished(reasons::add);

        assertEquals(Material.TNT, first.render().getType(), "第一格开场即走帧");
        assertEquals(Material.BARRIER, second.render().getType(), "未轮到的格子显示盖层");
        assertEquals(Material.BARRIER, third.render().getType());
        TickingTestSupport.advance(2);

        assertEquals(Material.DIAMOND, first.render().getType(), "走完帧放行, 显示真实结果");
        assertEquals(Material.TNT, second.render().getType());
        assertEquals(Material.BARRIER, third.render().getType());
        TickingTestSupport.advance(2);

        assertEquals(Material.EMERALD, second.render().getType());
        assertEquals(Material.TNT, third.render().getType());
        TickingTestSupport.advance(2);

        assertEquals(Material.GOLD_INGOT, third.render().getType());
        assertEquals(List.of(AnimationHandle.FinishReason.COMPLETED), reasons, "最后一格走完即整体结束");
        first.close();
        second.close();
        third.close();
    }

    @Test
    void leafRoutesRenderingInteractionsAndRefreshPlan() {
        AtomicReference<Item> clickedItem = new AtomicReference<>();
        AtomicReference<ItemClick> receivedClick = new AtomicReference<>();
        AtomicReference<BundleSelectClick> receivedSelect = new AtomicReference<>();
        Item item = Item.builder()
                .setItemProviderConstant(new ItemStack(Material.DIAMOND, 3))
                .updatePeriodically(7)
                .addClickHandler((clicked, click) -> {
                    clickedItem.set(clicked);
                    receivedClick.set(click);
                })
                .addBundleSelectHandler((ignoredItem, select) -> receivedSelect.set(select))
                .build();
        NormalPane root = paneWith(new Element.Item(item));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 5, root, 0);
        ItemStack first = path.render();
        ItemStack second = path.render();
        ItemStack cursor = new ItemStack(Material.EMERALD, 2);
        ItemClick click = new ItemClick(this.player, ClickType.LEFT, window, cursor, 5);
        BundleSelectClick select = new BundleSelectClick(this.player, window, 5, 2);
        path.handleClick(click);
        path.handleBundleSelect(select);

        assertEquals(3, second.getAmount());
        assertSame(first, second);
        assertSame(item, clickedItem.get());
        assertSame(click, receivedClick.get());
        assertSame(select, receivedSelect.get());
        assertEquals(cursor, click.cursor());
        assertSame(window, select.window());
        assertEquals(5, select.windowSlot());
        path.close();
    }

    @Test
    void periodicLeafMarksItsOwnWindowSlotDirty() {
        TickingTestSupport.install();
        try {
            Item periodic = Item.builder()
                    .setItemProviderConstant(new ItemStack(Material.DIAMOND))
                    .updatePeriodically(7)
                    .build();
            Item plain = Item.builder()
                    .setItemProviderConstant(new ItemStack(Material.EMERALD))
                    .build();
            NormalPane root = paneWith(new Element.Item(periodic), new Element.Item(plain));
            TestWindow window = new TestWindow(this.player);
            DisplayedSlotPath periodicPath = new DisplayedSlotPath(window, 5, root, 0);
            DisplayedSlotPath plainPath = new DisplayedSlotPath(window, 6, root, 1);
            window.clearDirtySlots();
            TickingTestSupport.advance(6);

            assertTrue(window.dirtySlots().isEmpty(), "周期未到不该标脏");
            TickingTestSupport.advance(1);

            assertEquals(Set.of(5), window.dirtySlots());
            window.clearDirtySlots();
            periodicPath.close();
            TickingTestSupport.advance(7);

            assertTrue(window.dirtySlots().isEmpty(), "路径关闭后周期刷新应当停止");
            plainPath.close();
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void repeatedStructureSlotsShareThrottleForSamePlayerAndItem() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        Item item = Item.builder()
                .addClickGuard(ItemGuards.throttle(60_000), ignoredClick -> rejections.incrementAndGet())
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .build();
        NormalPane pane = NormalPane.builder(Structure.of("AABB"))
                .addIngredient("A", item)
                .build();
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath first = new DisplayedSlotPath(window, 0, pane, 0);
        DisplayedSlotPath second = new DisplayedSlotPath(window, 1, pane, 1);
        first.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 0));
        second.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 1));

        assertEquals(1, clicks.get());
        assertEquals(1, rejections.get());
        first.close();
        second.close();
    }

    @Test
    void emptyLeafUsesDeepestAvailablePaneBackground() {
        NormalPane child = NormalPane.builder(new PaneSize(1, 1))
                .setBackground(ItemProvider.constant(new ItemStack(Material.DIAMOND)))
                .build();
        NormalPane root = NormalPane.builder(new PaneSize(1, 1))
                .setBackground(ItemProvider.constant(new ItemStack(Material.STONE)))
                .build();
        root.setElement(0, new Element.PaneLink(child, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 3, root, 0);

        assertEquals(Material.DIAMOND, path.render().getType());
        child.setBackground(ItemProvider.constant(new ItemStack(Material.GOLD_INGOT)));

        assertEquals(Set.of(3), window.dirtySlots());
        path.resolve();

        assertEquals(Material.GOLD_INGOT, path.render().getType());
        child.setBackground((ItemProvider) null);
        path.resolve();

        assertEquals(Material.STONE, path.render().getType());
        path.close();
    }

    @Test
    void anyFrozenPaneInResolvedPathBlocksLeafInteractions() {
        AtomicInteger clicks = new AtomicInteger();
        Item item = Item.builder()
                .addClickHandler((ignoredItem, ignoredClick) -> clicks.incrementAndGet())
                .build();
        NormalPane child = paneWith(new Element.Item(item));
        NormalPane root = NormalPane.builder(new PaneSize(1, 1)).setFrozen(true).build();
        root.setElement(0, new Element.PaneLink(child, 0));
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 4, root, 0);
        ItemClick click = new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 4);
        path.handleClick(click);

        assertEquals(0, clicks.get());
        root.setFrozen(false);
        path.resolve();
        path.handleClick(click);

        assertEquals(1, clicks.get());
        child.setFrozen(true);
        path.resolve();
        path.handleClick(click);

        assertEquals(1, clicks.get());
        path.close();
    }

    @Test
    void emptyLeafRendersEmptyAndIgnoresInteractions() {
        NormalPane root = paneWith(Element.Empty.INSTANCE);
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 1, root, 0);

        assertTrue(path.render().isEmpty());
        path.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 1));
        path.handleBundleSelect(new BundleSelectClick(this.player, window, 1, -1));
        path.close();

        assertTrue(path.isClosed());
        assertThrows(IllegalStateException.class, path::render);
        assertThrows(IllegalStateException.class, path::resolve);
        path.close();
    }

    @Test
    void renderReusesContextCreatedForDisplayedSlot() {
        NormalPane root = paneWith(Element.Empty.INSTANCE);
        TestWindow window = new TestWindow(this.player);
        DisplayedSlotPath path = new DisplayedSlotPath(window, 1, root, 0);
        int viewerCallsAfterConstruction = window.viewerCallCount();
        path.render();
        path.render();

        assertEquals(viewerCallsAfterConstruction, window.viewerCallCount());
        path.close();
    }

    @Test
    void constructionFailureClosesPreparedAttachments() {
        ObservableItem item = Item.builder().build();
        NormalPane root = paneWith(new Element.Item(item));
        IllegalStateException failure = new IllegalStateException("dirty failed");
        TestWindow window = new TestWindow(this.player, failure);

        assertSame(
                failure,

                assertThrows(IllegalStateException.class, () -> new DisplayedSlotPath(window, 1, root, 0))
        );

        assertEquals(1, window.dirtyCallCount());
        item.notifyWindows();

        assertEquals(1, window.dirtyCallCount());
    }

    private static NormalPane paneWith(Element element) {
        NormalPane pane = NormalPane.empty(new PaneSize(1, 1));
        pane.setElement(0, element);
        return pane;
    }

    private static NormalPane paneWith(Element first, Element second) {
        NormalPane pane = NormalPane.empty(new PaneSize(2, 1));
        pane.setElement(0, first);
        pane.setElement(1, second);
        return pane;
    }

    private static final class MortalStorage implements ExternalStorage {
        private final ItemStack[] contents;
        private boolean alive = true;
        private MortalStorage(int size) {
            this.contents = new ItemStack[size];
        }
        @Override
        public int size() {
            return this.contents.length;
        }
        @Override
        public ItemStack read(int slot) {
            return this.contents[slot];
        }
        @Override
        public void write(int slot, ItemStack item) {
            this.contents[slot] = item;
        }
        @Override
        public int maxStackSize(int slot) {
            return 64;
        }
        @Override
        public boolean alive() {
            return this.alive;
        }
    }

    private static final class ValueItem implements Item {
        private final String id;
        private final ItemProvider provider;
        private final AtomicInteger clickCount = new AtomicInteger();
        private final AtomicInteger attachCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private ValueItem(String id, ItemProvider provider) {
            this.id = id;
            this.provider = provider;
        }
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return this.provider;
        }
        @Override
        public void handleClick(ItemClick click) {
            this.clickCount.incrementAndGet();
        }
        @Override
        public ItemAttachment attach(@NonNull RenderContext context, @NonNull Observer<? super Item> observer) {
            this.attachCount.incrementAndGet();
            return new ItemAttachment() {
                @Override
                public void close() {
                    ValueItem.this.closeCount.incrementAndGet();
                }
            };
        }
        @Override
        public boolean equals(Object object) {
            return object instanceof ValueItem other && this.id.equals(other.id);
        }
        @Override
        public int hashCode() {
            return this.id.hashCode();
        }
        private int clickCount() {
            return this.clickCount.get();
        }
        private int attachCount() {
            return this.attachCount.get();
        }
        private int closeCount() {
            return this.closeCount.get();
        }
    }

    private static final class CountingItem implements Item {
        private final AtomicInteger attachCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
        @Override
        public ItemAttachment attach(@NonNull RenderContext context, @NonNull Observer<? super Item> observer) {
            this.attachCount.incrementAndGet();
            return new ItemAttachment() {
                @Override
                public void close() {
                    CountingItem.this.closeCount.incrementAndGet();
                }
            };
        }
        private int attachCount() {
            return this.attachCount.get();
        }
        private int closeCount() {
            return this.closeCount.get();
        }
    }

    private static final class FailingAttachItem implements Item {
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
        @Override
        public ItemAttachment attach(@NonNull RenderContext context, @NonNull Observer<? super Item> observer) {
            throw new IllegalStateException("attach failed");
        }
    }

    private static final class LateNotifyingItem implements Item {
        private Observer<? super Item> observer;
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
        @Override
        public ItemAttachment attach(@NonNull RenderContext context, @NonNull Observer<? super Item> observer) {
            this.observer = observer;
            return new ItemAttachment() {
                @Override
                public void close() {
                }
            };
        }
        private void notifyLateFromAnotherThread() {
            runOnAnotherThread(() -> this.observer.onUpdate(this));
        }
    }

    private static final class ConcurrentlyInvalidatingItem implements Item {
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
        @Override
        public ItemAttachment attach(@NonNull RenderContext context, @NonNull Observer<? super Item> observer) {
            runOnAnotherThread(() -> observer.onUpdate(this));
            return ItemAttachment.PASSIVE;
        }
    }

    private static void runOnAnotherThread(Runnable task) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        }, "displayed-slot-path-test-notifier");
        thread.setDaemon(true);
        thread.start();
        try {
            if (!completed.await(5, TimeUnit.SECONDS)) {
                thread.interrupt();
                throw new AssertionError("concurrent notification timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting concurrent notification", exception);
        }
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new AssertionError("concurrent notification failed", throwable);
        }
    }

    private static final class RememberingWindow extends WindowStub {
        private DisplayedSlotPath path;
        private RememberingWindow(Player viewer) {
            super(viewer);
        }
        @Override
        public Object rememberedAt(int windowSlot) {
            return this.path == null ? null : this.path.remembered();
        }
    }

    private static final class TestWindow extends WindowStub {
        private final RuntimeException dirtyFailure;
        private final Set<Integer> dirtySlots = new HashSet<>();
        private int dirtyCallCount;
        private int viewerCallCount;
        private TestWindow(Player viewer) {
            this(viewer, null);
        }
        private TestWindow(Player viewer, RuntimeException dirtyFailure) {
            super(viewer);
            this.dirtyFailure = dirtyFailure;
        }
        @Override
        public synchronized @NonNull Player viewer() {
            this.viewerCallCount++;
            return super.viewer();
        }
        @Override
        public synchronized void notifyUpdate(int windowSlot) {
            this.dirtySlots.add(windowSlot);
            this.dirtyCallCount++;
            if (this.dirtyFailure != null) {
                throw this.dirtyFailure;
            }
        }
        private synchronized Set<Integer> dirtySlots() {
            return Set.copyOf(this.dirtySlots);
        }
        private synchronized int dirtyCallCount() {
            return this.dirtyCallCount;
        }
        private synchronized int viewerCallCount() {
            return this.viewerCallCount;
        }
        private synchronized void clearDirtySlots() {
            this.dirtySlots.clear();
            this.dirtyCallCount = 0;
        }
    }
}
