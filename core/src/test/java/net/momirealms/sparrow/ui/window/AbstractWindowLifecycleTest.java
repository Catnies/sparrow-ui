package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.PlayerStub;
import net.momirealms.sparrow.ui.window.handle.AnvilMenuHandle;
import net.momirealms.sparrow.ui.window.handle.BrewingMenuHandle;
import net.momirealms.sparrow.ui.window.handle.CartographyMenuHandle;
import net.momirealms.sparrow.ui.window.handle.CrafterMenuHandle;
import net.momirealms.sparrow.ui.window.handle.EnchantmentMenuHandle;
import net.momirealms.sparrow.ui.window.handle.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuInput;
import net.momirealms.sparrow.ui.window.handle.MerchantMenuHandle;
import net.momirealms.sparrow.ui.window.handle.RecipeBookMenuHandle;
import net.momirealms.sparrow.ui.window.handle.StonecutterMenuHandle;
import net.momirealms.sparrow.ui.inventory.InventorySequence;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.click.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.click.InteractionEdits;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SparrowInventoryClickEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.ObservableItem;
import net.momirealms.sparrow.ui.item.StaticItem;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.ui.state.GcSupport;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.TickingTestSupport;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.visual.VisualLayer;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.visual.animation.TitleAnimationDefinition;
import net.momirealms.sparrow.ui.window.click.EnchantSelectClick;
import net.momirealms.sparrow.ui.window.click.MerchantTradeSelectClick;
import net.momirealms.sparrow.ui.window.click.RecipeBookSelectClick;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.map.MapCursor;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractWindowLifecycleTest {

    private static OwnedDispatcher activeDispatcher;
    private ServerMock server;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin();
        SparrowUI.getInstance().fireBukkitInventoryEvents(true);
        SparrowUiTestRuntime.installOwnership(ignoredTarget -> true);
    }

    @AfterEach
    void tearDown() {
        activeDispatcher = null;
        SparrowUI.getInstance().fireBukkitInventoryEvents(true);
        SparrowUiTestRuntime.restoreOwnership();
        MockBukkit.unmock();
    }

    @Test
    void oneHundredOpenCloseCyclesReleaseMenusTasksAttachmentsAndRegistry() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingItem item = new TrackingItem();
        Pane pane = Pane.filled(9, 1, item);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        OwnedDispatcher dispatcher = new OwnedDispatcher(this.plugin);
        WindowManager manager = new WindowManager(this.plugin, menus, new FoliaExecutor(this.plugin));
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        for (int cycle = 0; cycle < 100; cycle++) {
            Component title = Component.text("cycle-" + cycle);
            AbstractWindow<?> window = new NormalWindowImpl(
                    manager,
                    player,
                    WindowLayout.split(pane, Pane.empty(9, 4)),
                    new AbstractWindow.Settings(
                            () -> title,
                            true,
                            List.of(opened::incrementAndGet),
                            List.of(ignoredReason -> closed.incrementAndGet()),
                            List.of(),
                            false,
                            null,
                            WindowSession.Kind.STACK,
                            List.of(),
                            0,
                            List.of(),
                            VisualLayer.NONE,
                            VisualLayer.NONE
                    )
            );

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            assertSame(window, manager.current(player));
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertTrue(manager.windows().isEmpty());
            assertEquals(0, menus.live.get());
            assertEquals(0, dispatcher.liveTasks.get());
            assertEquals(0, item.liveAttachments.get());
        }

        assertEquals(100, opened.get());
        assertEquals(100, closed.get());
        assertEquals(900, item.totalAttachments.get());
        assertEquals(100, menus.created.get());
    }

    @Test
    void closingWindowFinishesItsAnimationsButLeavesSharedPaneAnimations() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            Pane pane = Pane.empty(9, 1);
            new OwnedDispatcher(this.plugin);
            WindowManager manager = new WindowManager(this.plugin, new TrackingMenuFactory(), new FoliaExecutor(this.plugin));
            AbstractWindow<?> window = new NormalWindowImpl(
                    manager,
                    player,
                    WindowLayout.split(pane, Pane.empty(9, 4)),
                    new AbstractWindow.Settings(
                            () -> Component.text("animated"),
                            true,
                            List.of(),
                            List.of(),
                            List.of(),
                            false,
                            null,
                            WindowSession.Kind.STACK,
                            List.of(),
                            0,
                            List.of(),
                            VisualLayer.NONE,
                            VisualLayer.NONE
                    )
            );

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            List<AnimationHandle.FinishReason> windowReasons = new ArrayList<>();
            List<AnimationHandle.FinishReason> paneReasons = new ArrayList<>();
            window.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> null))
                    .whenFinished(windowReasons::add);
            AnimationHandle shared = pane.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> null));
            shared.whenFinished(paneReasons::add);

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), windowReasons, "窗口关闭终结它宿主上的动画");
            assertTrue(paneReasons.isEmpty(), "共享 Pane 宿主的动画不随单个窗口关闭");
            shared.cancel();

            assertEquals(List.of(AnimationHandle.FinishReason.CANCELLED), paneReasons);
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void failedReplacementRollsBackAndKeepsThePreviousWindowPublished() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 7));
        TrackingItem item = new TrackingItem();
        Pane pane = Pane.filled(9, 1, item);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        OwnedDispatcher dispatcher = new OwnedDispatcher(this.plugin);
        WindowManager manager = manager(dispatcher, menus);
        AbstractWindow<?> previous = window(manager, player, pane, "previous");
        AbstractWindow<?> replacement = window(manager, player, pane, "replacement");

        assertEquals(Window.OpenResult.OPENED, previous.open().toCompletableFuture().join());
        TrackingMenuHandle previousHandle = menus.currentHandle;
        menus.failNextOpen.set(true);

        assertThrows(CompletionException.class, () -> replacement.open().toCompletableFuture().join());
        assertSame(previous, manager.current(player));
        assertTrue(previous.isOpen());
        assertFalse(replacement.isOpen());
        assertEquals(1, menus.live.get());
        assertEquals(1, dispatcher.liveTasks.get());
        assertEquals(9, item.liveAttachments.get());
        assertEquals(new ItemStack(Material.DIAMOND, 7), previousHandle.cursor());
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, previous.close().toCompletableFuture().join());
        assertEquals(0, menus.live.get());
        assertEquals(0, dispatcher.liveTasks.get());
        assertEquals(0, item.liveAttachments.get());
    }

    @Test
    void failedOpenSuspendsBindingsUntilTheNextSuccessfulOpen() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        OwnedDispatcher dispatcher = new OwnedDispatcher(this.plugin);
        WindowManager manager = manager(dispatcher, menus);
        AbstractWindow<?> window = window(manager, player, Pane.empty(9, 1), "bound");
        MutableSignal<Integer> signal = Signal.of(0);
        List<Window> callbacks = new ArrayList<>();
        window.bind(signal, callbacks::add);
        menus.failNextOpen.set(true);

        assertThrows(CompletionException.class, () -> window.open().toCompletableFuture().join());
        assertFalse(window.isOpen());
        signal.set(1);

        assertEquals(List.of(), callbacks, "失败的打开结束后绑定保持挂起");
        assertEquals(0, TickingTestSupport.entryCountOf(signal), "失败的打开不该继续占着上游的订阅表");
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        signal.set(2);

        assertEquals(List.of(window), callbacks, "下一次成功打开后绑定恰好回调一次");
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void successfulReplacementRetiresOnlyThePreviousResources() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 7));
        TrackingItem item = new TrackingItem();
        Pane pane = Pane.filled(9, 1, item);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        OwnedDispatcher dispatcher = new OwnedDispatcher(this.plugin);
        WindowManager manager = manager(dispatcher, menus);
        AbstractWindow<?> previous = window(manager, player, pane, "previous");
        AbstractWindow<?> replacement = window(manager, player, pane, "replacement");

        assertEquals(Window.OpenResult.OPENED, previous.open().toCompletableFuture().join());
        TrackingMenuHandle previousHandle = menus.currentHandle;

        assertEquals(Window.OpenResult.OPENED, replacement.open().toCompletableFuture().join());
        assertSame(replacement, manager.current(player));
        assertFalse(previous.isOpen());
        assertTrue(replacement.isOpen());
        assertEquals(List.of(WindowCloseReason.OPEN_NEW), menus.closeReasons);
        assertEquals(1, menus.live.get());
        assertEquals(1, dispatcher.liveTasks.get());
        assertEquals(9, item.liveAttachments.get());
        assertTrue(previousHandle.cursor().isEmpty());
        assertEquals(new ItemStack(Material.DIAMOND, 7), menus.currentHandle.cursor());
        assertEquals(Window.CloseResult.CLOSED, replacement.close().toCompletableFuture().join());
        assertTrue(manager.windows().isEmpty());
        assertEquals(0, menus.live.get());
        assertEquals(0, dispatcher.liveTasks.get());
        assertEquals(0, item.liveAttachments.get());
    }

    @Test
    void rememberedAtFollowsTheSlotAcrossOpenCloseAndReopen() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        AtomicInteger renders = new AtomicInteger();
        AtomicReference<Object> seenByClick = new AtomicReference<>();
        Item item = Item.builder()
                .setItemProvider(context -> {
                    context.remember("slot" + context.windowSlot + "#" + renders.incrementAndGet());
                    return new ItemStack(Material.DIAMOND);
                })
                .addClickHandler(click -> seenByClick.set(click.remembered()))
                .build();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory());
        AbstractWindow<?> window = window(manager, player, Pane.filled(9, 1, item), "remember");

        assertNull(window.rememberedAt(0), "没打开就没渲染过, 什么都没记");
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        String first = (String) window.rememberedAt(0);

        assertNotNull(first, "打开时的首帧渲染已经记下");
        assertTrue(first.startsWith("slot0#"), "每个槽位记的是自己那份: " + first);
        assertTrue(((String) window.rememberedAt(8)).startsWith("slot8#"));
        assertNull(window.rememberedAt(99), "越界返回 null");
        window.dispatchItemClick(0, ClickType.LEFT);

        assertEquals(first, seenByClick.get(), "点击读到的就是这个槽位当前记下的");
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        assertNull(window.rememberedAt(0), "关窗即清");
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        String second = (String) window.rememberedAt(0);

        assertNotNull(second);
        assertNotEquals(first, second, "重开是新的一次渲染记下的");
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitBridgeAlwaysDispatchesAndReturnsCancellation() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        Pane pane = Pane.empty(9, 1);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = window(manager, player, pane, "events");
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger drags = new AtomicInteger();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                clicks.incrementAndGet();
                event.setCancelled(true);
            }
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                drags.incrementAndGet();
                event.setCancelled(true);
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        ClickInterpreter.Result.SingleClick click = assertInstanceOf(
                ClickInterpreter.Result.SingleClick.class,
                new ClickInterpreter().interpret(
                        new MenuInput.Common.Click(1, 0, 0, ClickType.LEFT, -1),
                        WindowLayout.split(pane, Pane.empty(9, 4)),
                        0
                )
        );
        SparrowUI.getInstance().fireBukkitInventoryEvents(false);

        assertFalse(manager.bukkitBridge().allowClick(window, click, InventoryAction.NOTHING));
        assertFalse(manager.bukkitBridge().allowDrag(
                window,
                ClickType.LEFT,
                ItemStack.empty(),
                Map.of(0, new ItemStack(Material.DIAMOND), 1, new ItemStack(Material.DIAMOND)),
                InteractionEdits.discarding()
        ));

        assertEquals(1, clicks.get());
        assertEquals(1, drags.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void paneDeclaredInventoryJoinsTargetsOnlyWhenObscuredSlotsParticipate() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory hiddenPage = new VirtualInventory(9);
        Pane pane = Pane.empty(9, 1);
        pane.linkInventory(hiddenPage);
        AbstractWindow<?> closed = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "declared-off");

        assertEquals(Window.OpenResult.OPENED, closed.open().toCompletableFuture().join());
        assertTrue(linkedEntry(closed, hiddenPage).isEmpty());
        assertEquals(Window.CloseResult.CLOSED, closed.close().toCompletableFuture().join());
        hiddenPage.includeObscuredSlots(true);
        AbstractWindow<?> opened = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "declared-on");

        assertEquals(Window.OpenResult.OPENED, opened.open().toCompletableFuture().join());
        assertEquals(hiddenPage.size(), linkedEntry(opened, hiddenPage).orElseThrow().visibleSlots().cardinality());
        assertEquals(Window.CloseResult.CLOSED, opened.close().toCompletableFuture().join());
    }

    @Test
    void paneLinkedSequenceFollowsItsMembers() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory first = new VirtualInventory(9);
        VirtualInventory later = new VirtualInventory(9);
        first.includeObscuredSlots(true);
        later.includeObscuredSlots(true);
        InventorySequence sequence = InventorySequence.of(first);
        Pane pane = Pane.empty(9, 1);
        pane.linkInventory(sequence);
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "linked-sequence");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertFalse(linkedEntry(window, first).isEmpty());
        assertTrue(linkedEntry(window, later).isEmpty());
        sequence.add(later);

        assertFalse(linkedEntry(window, later).isEmpty());
        sequence.remove(later);

        assertTrue(linkedEntry(window, later).isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void retiredInventoryLeavesThePaneDeclaration() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory declared = ReferencingInventory.fromContents(chest);
        Pane pane = Pane.empty(9, 1);
        pane.linkInventory(declared);

        assertEquals(List.of(declared), pane.linkedInventories());
        declared.retire();

        assertTrue(pane.linkedInventories().isEmpty());
    }

    @Test
    void retiredInventoryLeavesTheTargetList() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory linked = ReferencingInventory.fromContents(chest);
        Pane pane = Pane.empty(9, 1);
        for (int slot = 0; slot < linked.size(); slot++) {
            pane.setElement(slot, Element.inventory(linked, slot));
        }
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "retired-target");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertFalse(linkedEntry(window, linked).isEmpty());
        linked.retire();

        assertTrue(linkedEntry(window, linked).isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void paneDeclaredInventoryCountsFromNestedPanesAndOnlyOnce() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory hiddenPage = new VirtualInventory(9);
        hiddenPage.includeObscuredSlots(true);
        Pane child = Pane.empty(1, 1);
        child.linkInventory(hiddenPage);
        Pane root = Pane.empty(9, 1);
        root.setElement(0, Element.pane(child, 0));
        root.linkInventory(hiddenPage);
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, root, "declared-nested");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(1, linkedInventoriesOf(window).stream().filter(linked -> linked.inventory() == hiddenPage).count());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void declaredInventoryIsRefreshedAlongWithTheDisplayedOnes() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory hiddenPage = ReferencingInventory.fromContents(chest);
        hiddenPage.includeObscuredSlots(true);
        Pane pane = Pane.empty(9, 1);
        pane.linkInventory(hiddenPage);
        List<UpdateReason> reasons = new ArrayList<>();
        Subscription watch = hiddenPage.subscribePostUpdate(event -> reasons.add(event.reason()));
        chest.setItem(0, new ItemStack(Material.COAL, 3));
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "declared-refresh");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(List.of(UpdateReason.External.INSTANCE), reasons);
        watch.close();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void refreshTargetsFollowStructureAndDeclarationChanges() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        org.bukkit.inventory.Inventory linkedChest = Bukkit.createInventory(null, 9);
        org.bukkit.inventory.Inventory declaredChest = Bukkit.createInventory(null, 9);
        ReferencingInventory linked = ReferencingInventory.fromContents(linkedChest);
        ReferencingInventory declared = ReferencingInventory.fromContents(declaredChest);
        declared.includeObscuredSlots(true);
        Pane pane = Pane.empty(9, 1);
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "refresh-targets");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        List<UpdateReason> linkedReasons = new ArrayList<>();
        List<UpdateReason> declaredReasons = new ArrayList<>();
        Subscription linkedWatch = linked.subscribePostUpdate(event -> linkedReasons.add(event.reason()));
        Subscription declaredWatch = declared.subscribePostUpdate(event -> declaredReasons.add(event.reason()));
        linkedChest.setItem(0, new ItemStack(Material.COAL, 1));
        declaredChest.setItem(0, new ItemStack(Material.COAL, 1));
        window.tick();

        assertTrue(linkedReasons.isEmpty());
        assertTrue(declaredReasons.isEmpty());
        pane.setElement(0, Element.inventory(linked, 0));
        window.tick();
        window.tick();

        assertEquals(List.of(UpdateReason.External.INSTANCE), linkedReasons);
        pane.linkInventory(declared);
        window.tick();

        assertEquals(List.of(UpdateReason.External.INSTANCE), declaredReasons);
        linkedWatch.close();
        declaredWatch.close();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void refreshTargetsFollowSequenceMemberChanges() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory joining = ReferencingInventory.fromContents(chest);
        joining.includeObscuredSlots(true);
        InventorySequence sequence = InventorySequence.of();
        Pane pane = Pane.empty(9, 1);
        pane.linkInventory(sequence);
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "sequence-refresh");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        List<UpdateReason> reasons = new ArrayList<>();
        Subscription watch = joining.subscribePostUpdate(event -> reasons.add(event.reason()));
        chest.setItem(0, new ItemStack(Material.COAL, 1));
        window.tick();

        assertTrue(reasons.isEmpty());
        sequence.add(joining);
        window.tick();

        assertEquals(List.of(UpdateReason.External.INSTANCE), reasons);
        watch.close();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void swappingItemLeavesRefreshTargetsAlone() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory linked = ReferencingInventory.fromContents(chest);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(linked, 0));
        pane.setItem(1, Item.builder().build());
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, pane, "refresh-targets-item");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.tick();
        Object collectedTargets = refreshTargetsOf(window);

        assertNotNull(collectedTargets);
        List<UpdateReason> reasons = new ArrayList<>();
        Subscription watch = linked.subscribePostUpdate(event -> reasons.add(event.reason()));
        pane.setItem(1, Item.builder().build());
        chest.setItem(0, new ItemStack(Material.COAL, 1));
        window.tick();
        window.tick();

        assertSame(collectedTargets, refreshTargetsOf(window));
        assertEquals(List.of(UpdateReason.External.INSTANCE), reasons);
        watch.close();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void pendingDirtySlotIsRenderedOncePerTickEvenWhenAClickArrives() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger renders = new AtomicInteger();
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        ObservableItem counted = Item.builder()
                .setItemProvider(ignoredContext -> {
                    renders.incrementAndGet();
                    return new ItemStack(Material.STONE);
                })
                .build();
        pane.setItem(8, counted);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "dirty-once");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        renders.set(0);
        counted.notifyWindows();
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(
                        menus.lastContainerId,
                        stateId,
                        0,
                        ClickType.LEFT,
                        -1
                )
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(
                        menus.lastContainerId,
                        stateId,
                        0,
                        ClickType.LEFT,
                        -1
                )
        );
        window.tick();

        assertEquals(1, renders.get());
        assertEquals(2, menus.eventViewRenderedSlots.size());
        assertTrue(menus.eventViewRenderedSlots.get(0).get(8));
        assertFalse(menus.eventViewRenderedSlots.get(1).get(8));
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void disabledBukkitEventsSkipTheEventViewRenderEntirely() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        AtomicInteger renders = new AtomicInteger();
        Pane pane = Pane.empty(9, 1);
        ObservableItem counted = Item.builder()
                .setItemProvider(ignoredContext -> {
                    renders.incrementAndGet();
                    return new ItemStack(Material.STONE);
                })
                .build();
        pane.setItem(0, counted);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "no-bukkit-events");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        SparrowUI.getInstance().fireBukkitInventoryEvents(false);
        try {
            renders.set(0);
            counted.notifyWindows();
            menus.offerInput(
                    menus.lastGeneration,
                    new MenuInput.Common.Click(
                            menus.lastContainerId,
                            menus.currentHandle.stateId(),
                            0,
                            ClickType.LEFT,
                            -1
                    )
            );
            window.tick();

            assertEquals(1, renders.get());
        } finally {
            SparrowUI.getInstance().fireBukkitInventoryEvents(true);
        }

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void unlistenedBukkitClickSkipsTheEventViewAndStillCommits() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "unlistened-click");
        AtomicInteger dragCalls = new AtomicInteger();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                dragCalls.incrementAndGet();
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertTrue(menus.eventViewRenderedSlots.isEmpty());
        assertEquals(5, inventory.itemAmount(0));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(0, dragCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void unlistenedBukkitDragSkipsTheEventViewEvenWithAClickListener() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        VirtualInventory inventory = new VirtualInventory(2);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.inventory(inventory, 1));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "unlistened-drag");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.START));
        for (int windowSlot = 0; windowSlot < 2; windowSlot++) {
            menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                    menus.lastContainerId, stateId, windowSlot, ClickType.LEFT, MenuInput.Common.DragPhase.ADD));
        }
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.END));
        window.tick();

        assertTrue(menus.eventViewRenderedSlots.isEmpty());
        assertEquals(4, inventory.itemAmount(0));
        assertEquals(4, inventory.itemAmount(1));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void registeringAClickListenerReopensTheBukkitGateForTheNextInteraction() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "listener-later");
        AtomicInteger bukkitCalls = new AtomicInteger();

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertTrue(menus.eventViewRenderedSlots.isEmpty());
        assertEquals(5, inventory.itemAmount(0));
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitCalls.incrementAndGet();
            }
        }, this.plugin);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(1, bukkitCalls.get());
        assertEquals(1, menus.eventViewRenderedSlots.size());
        assertNull(inventory.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void preparedPlacementCandidateIsUsedOnceAcrossBukkitSparrowAndCommit() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = window(manager, player, pane, "estimated-action");
        AtomicReference<InventoryAction> action = new AtomicReference<>();
        AtomicReference<ItemStack> sourceDuringEvent = new AtomicReference<>();
        AtomicReference<ItemStack> cursorDuringEvent = new AtomicReference<>();
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicReference<Player> rulePlayer = new AtomicReference<>();
        AtomicReference<Window> ruleWindow = new AtomicReference<>();
        List<String> order = new ArrayList<>();
        inventory.setAccessRule(placement -> {
            order.add("rule");
            rulePlayer.set(placement.player());
            ruleWindow.set(placement.window());
            return ruleCalls.incrementAndGet() == 1;
        });
        inventory.subscribeClick(event -> order.add("sparrow"));
        inventory.subscribePreUpdate(event -> order.add("pre"));
        inventory.subscribePostUpdate(event -> order.add("post"));
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                order.add("bukkit");
                action.set(event.getAction());
                sourceDuringEvent.set(inventory.itemAt(0));
                cursorDuringEvent.set(menus.currentHandle.cursor());
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(
                        menus.lastContainerId,
                        menus.currentHandle.stateId(),
                        0,
                        ClickType.LEFT,
                        -1
                )
        );
        window.tick();

        assertEquals(InventoryAction.PLACE_ALL, action.get());
        assertNull(sourceDuringEvent.get());
        assertEquals(5, cursorDuringEvent.get().getAmount());
        assertEquals(5, inventory.itemAmount(0));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(1, ruleCalls.get());
        assertSame(player, rulePlayer.get());
        assertSame(window, ruleWindow.get());
        assertEquals(List.of("rule", "bukkit", "sparrow", "pre", "post"), order);
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitClickSeesReferencingInventoryReconciledDuringCandidatePlanning() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory inventory = ReferencingInventory.fromContents(chest);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "referencing-click");
        AtomicReference<ItemStack> currentDuringEvent = new AtomicReference<>();
        AtomicReference<InventoryAction> action = new AtomicReference<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                currentDuringEvent.set(ItemUtils.copyOrEmpty(event.getCurrentItem()));
                action.set(event.getAction());
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        chest.setItem(0, new ItemStack(Material.DIAMOND, 2));
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(InventoryAction.PICKUP_ALL, action.get());
        assertEquals(new ItemStack(Material.DIAMOND, 2), currentDuringEvent.get());
        assertNull(chest.getItem(0));
        assertEquals(new ItemStack(Material.DIAMOND, 2), menus.currentHandle.cursor());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void staleStateIdClickStillTakesEffectWhenNothingStructuralChanged() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "stale-click");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int staleStateId = menus.currentHandle.stateId();
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 3));
        window.tick();

        assertNotEquals(staleStateId, menus.currentHandle.stateId(), "内容同步应当推进 state id");
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, staleStateId, 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertNull(inventory.itemAt(0), "落后一拍的点击照常生效, 与原版一致");
        assertEquals(new ItemStack(Material.DIAMOND, 3), menus.currentHandle.cursor());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void staleStateIdClickIsDroppedWhenTheSlotChangedStructure() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory first = new VirtualInventory(1);
        first.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 2));
        VirtualInventory second = new VirtualInventory(1);
        second.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.EMERALD, 2));
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(first, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "structure-swap");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int staleStateId = menus.currentHandle.stateId();
        pane.setElement(0, Element.inventory(second, 0));
        window.tick();

        assertNotEquals(staleStateId, menus.currentHandle.stateId(), "结构同步应当推进 state id");
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, staleStateId, 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(new ItemStack(Material.EMERALD, 2), second.itemAt(0), "跨过结构变化的点击不得落到新终点上");
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertTrue(menus.synchronizations.getLast().forceFull(), "丢弃后必须全量恢复客户端");
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void consecutiveBukkitEventsSeeAuthorityInsteadOfThePreviousEventSandbox() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 2));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "event-sandbox");
        List<InventoryAction> actions = new ArrayList<>();
        List<ItemStack> currents = new ArrayList<>();
        List<ItemStack> cursors = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                actions.add(event.getAction());
                currents.add(ItemUtils.copyOrEmpty(event.getCurrentItem()));
                cursors.add(ItemUtils.copyOrEmpty(event.getCursor()));
                if (actions.size() == 1) {
                    event.setCurrentItem(new ItemStack(Material.EMERALD, 9));
                    event.setCursor(new ItemStack(Material.GOLD_INGOT, 9));
                }
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, stateId, 0, ClickType.LEFT, -1)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, stateId, 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(List.of(InventoryAction.PLACE_ALL, InventoryAction.PICKUP_ALL), actions);
        assertTrue(currents.get(0).isEmpty());
        assertEquals(new ItemStack(Material.DIAMOND, 2), currents.get(1));
        assertEquals(new ItemStack(Material.DIAMOND, 2), cursors.get(0));
        assertTrue(cursors.get(1).isEmpty());
        assertNull(inventory.itemAt(0));
        assertEquals(new ItemStack(Material.DIAMOND, 2), menus.currentHandle.cursor());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitCancellationStopsBeforeSparrowAndPre() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "bukkit-cancel");
        AtomicInteger bukkitCalls = new AtomicInteger();
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribeClick(event -> sparrowCalls.incrementAndGet());
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitCalls.incrementAndGet();
                event.setCancelled(true);
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(1, bukkitCalls.get());
        assertEquals(0, sparrowCalls.get());
        assertEquals(0, preCalls.get());
        assertEquals(0, postCalls.get());
        assertNull(inventory.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void sparrowCancellationRunsAfterBukkitAndStopsPre() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "sparrow-cancel");
        List<String> order = new ArrayList<>();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribeClick(event -> {
            order.add("sparrow");
            event.cancel();
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                order.add("bukkit");
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(List.of("bukkit", "sparrow"), order);
        assertEquals(0, preCalls.get());
        assertEquals(0, postCalls.get());
        assertNull(inventory.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitRootWriteReplansTheCandidateAgainstTheNewScene() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "root-write");
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger playerPreCalls = new AtomicInteger();
        inventory.subscribeClick(event -> sparrowCalls.incrementAndGet());
        inventory.subscribePreUpdate(event -> {
            if (event.reason() instanceof PlayerUpdateReason) {
                playerPreCalls.incrementAndGet();
            }
        });
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.EMERALD, 2));
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(Material.DIAMOND, inventory.itemAt(0).getType());
        assertEquals(5, inventory.itemAmount(0));
        assertEquals(Material.EMERALD, menus.currentHandle.cursor().getType());
        assertEquals(2, menus.currentHandle.cursor().getAmount());
        assertEquals(1, sparrowCalls.get());
        assertEquals(1, playerPreCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void windowFrozenSlotSilencesClicksUntilUnfrozen() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "window-frozen-slot");
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger bukkitCalls = new AtomicInteger();
        inventory.subscribeClick(event -> sparrowCalls.incrementAndGet());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitCalls.incrementAndGet();
            }
        }, this.plugin);

        assertThrows(IndexOutOfBoundsException.class, () -> window.frozenAt(-1, true));
        assertThrows(IndexOutOfBoundsException.class, () -> window.frozenAt(-1));
        window.frozenAt(0, true);

        assertTrue(window.frozenAt(0));
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertNull(inventory.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(0, sparrowCalls.get());
        assertEquals(0, bukkitCalls.get());
        window.frozenAt(0, false);

        assertFalse(window.frozenAt(0));
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(0, menus.currentHandle.cursor().getAmount());
        assertEquals(1, sparrowCalls.get());
        assertEquals(1, bukkitCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void windowFrozenOffhandSilencesSwapUntilUnfrozen() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.getInventory().setItemInOffHand(new ItemStack(Material.EMERALD, 2));
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 4));
        AtomicInteger itemCalls = new AtomicInteger();
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.item(Item.builder()
                .setItemProviderConstant(new ItemStack(Material.STONE))
                .addClickHandler(ignoredClick -> itemCalls.incrementAndGet())
                .build()));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "window-frozen-offhand");
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger bukkitCalls = new AtomicInteger();
        inventory.subscribeClick(event -> sparrowCalls.incrementAndGet());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitCalls.incrementAndGet();
            }
        }, this.plugin);
        window.offhandFrozen(true);

        assertTrue(window.offhandFrozen());
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.SWAP_OFFHAND, -1)
        );
        window.tick();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 1, ClickType.SWAP_OFFHAND, -1)
        );
        window.tick();

        assertEquals(new ItemStack(Material.DIAMOND, 4), inventory.itemAt(0));
        assertEquals(new ItemStack(Material.EMERALD, 2), player.getInventory().getItemInOffHand());
        assertEquals(0, sparrowCalls.get());
        assertEquals(0, bukkitCalls.get());
        assertEquals(0, itemCalls.get());
        window.offhandFrozen(false);

        assertFalse(window.offhandFrozen());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.SWAP_OFFHAND, -1)
        );
        window.tick();

        assertEquals(new ItemStack(Material.EMERALD, 2), inventory.itemAt(0));
        assertEquals(new ItemStack(Material.DIAMOND, 4), player.getInventory().getItemInOffHand());
        assertEquals(1, sparrowCalls.get());
        assertEquals(1, bukkitCalls.get());
        assertEquals(0, itemCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitInventoryLinkReplacementInvalidatesPreparedCandidate() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory first = new VirtualInventory(1);
        VirtualInventory replacement = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(first, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "bukkit-link-replace");
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        first.subscribeClick(event -> sparrowCalls.incrementAndGet());
        first.subscribePreUpdate(event -> preCalls.incrementAndGet());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                pane.setElement(0, Element.inventory(replacement, 0));
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertNull(first.itemAt(0));
        assertNull(replacement.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(0, sparrowCalls.get());
        assertEquals(0, preCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void preInventoryLinkReplacementConflictsBeforeCommit() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory first = new VirtualInventory(1);
        VirtualInventory replacement = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(first, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "pre-link-replace");
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        first.subscribePreUpdate(event -> {
            preCalls.incrementAndGet();
            pane.setElement(0, Element.inventory(replacement, 0));
        });
        first.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(1, preCalls.get());
        assertEquals(0, postCalls.get());
        assertNull(first.itemAt(0));
        assertNull(replacement.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void preClosingWindowConflictsBeforeInventoryCommit() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "pre-close");
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> assertEquals(
                Window.CloseResult.CLOSED,
                window.close().toCompletableFuture().join()
        ));
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertFalse(window.isOpen());
        assertNull(inventory.itemAt(0));
        assertEquals(0, postCalls.get());
    }

    @Test
    void bukkitPaneDirtyDoesNotInvalidateUnchangedInventoryLink() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "bukkit-pane-dirty");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                pane.dirty(0);
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(5, inventory.itemAmount(0));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void ruleRegisteredDuringBukkitAffectsOnlyTheNextOperation() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "new-rule");
        AtomicInteger bukkitCalls = new AtomicInteger();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitCalls.incrementAndGet();
                inventory.setAccessRule(placement -> false);
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(5, inventory.itemAmount(0));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, null);
        menus.currentHandle.cursor(new ItemStack(Material.DIAMOND, 5));
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertEquals(2, bukkitCalls.get());
        assertNull(inventory.itemAt(0));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitDragReceivesFilteredRedistributedCandidate() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        VirtualInventory inventory = new VirtualInventory(4);
        for (int slot = 0; slot < 4; slot++) {
            int acceptedSlot = slot;
            inventory.setAccessRule(slot, placement -> acceptedSlot == 0 || acceptedSlot == 2);
        }
        Pane pane = Pane.empty(9, 1);
        for (int slot = 0; slot < 4; slot++) {
            pane.setElement(slot, Element.inventory(inventory, slot));
        }
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "drag-candidate");
        AtomicReference<Map<Integer, ItemStack>> newItems = new AtomicReference<>();
        AtomicReference<ItemStack> newCursor = new AtomicReference<>();
        AtomicInteger sparrowClicks = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicBoolean unchangedDuringEvent = new AtomicBoolean();
        inventory.subscribeClick(event -> sparrowClicks.incrementAndGet());
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                newItems.set(event.getNewItems());
                newCursor.set(event.getCursor());
                unchangedDuringEvent.set(inventory.isEmpty() && menus.currentHandle.cursor().getAmount() == 8);
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.START));
        for (int slot = 0; slot < 4; slot++) {
            menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                    menus.lastContainerId, stateId, slot, ClickType.LEFT, MenuInput.Common.DragPhase.ADD));
        }
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.END));
        window.tick();

        assertTrue(unchangedDuringEvent.get());
        assertEquals(Set.of(0, 2), newItems.get().keySet());
        assertEquals(4, newItems.get().get(0).getAmount());
        assertEquals(4, newItems.get().get(2).getAmount());
        assertTrue(newCursor.get().isEmpty());
        assertEquals(4, inventory.itemAmount(0));
        assertEquals(0, inventory.itemAmount(1));
        assertEquals(4, inventory.itemAmount(2));
        assertEquals(0, inventory.itemAmount(3));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(0, sparrowClicks.get());
        assertEquals(1, preCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitDragSeesReferencingInventoryReconciledDuringCandidatePlanning() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory inventory = ReferencingInventory.fromContents(chest);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.inventory(inventory, 1));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "referencing-drag");
        AtomicReference<ItemStack> currentDuringEvent = new AtomicReference<>();
        AtomicReference<Map<Integer, ItemStack>> newItems = new AtomicReference<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                currentDuringEvent.set(ItemUtils.copyOrEmpty(event.getView().getItem(0)));
                newItems.set(event.getNewItems());
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        chest.setItem(0, new ItemStack(Material.DIAMOND, 2));
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.START));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, 0, ClickType.LEFT, MenuInput.Common.DragPhase.ADD));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, 1, ClickType.LEFT, MenuInput.Common.DragPhase.ADD));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.END));
        window.tick();

        assertEquals(new ItemStack(Material.DIAMOND, 6), newItems.get().get(0));
        assertEquals(new ItemStack(Material.DIAMOND, 4), newItems.get().get(1));
        assertEquals(new ItemStack(Material.DIAMOND, 2), currentDuringEvent.get());
        assertEquals(new ItemStack(Material.DIAMOND, 6), chest.getItem(0));
        assertEquals(new ItemStack(Material.DIAMOND, 4), chest.getItem(1));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void bukkitDragLinkReplacementInvalidatesWholeCandidate() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        VirtualInventory inventory = new VirtualInventory(2);
        VirtualInventory replacement = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.inventory(inventory, 1));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "drag-link-replace");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                pane.setElement(1, Element.inventory(replacement, 0));
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.START));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, 0, ClickType.LEFT, MenuInput.Common.DragPhase.ADD));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, 1, ClickType.LEFT, MenuInput.Common.DragPhase.ADD));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, ClickType.LEFT, MenuInput.Common.DragPhase.END));
        window.tick();

        assertTrue(inventory.isEmpty());
        assertTrue(replacement.isEmpty());
        assertEquals(8, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void mixedDragListsEveryStopAndSkipsFrozenItems() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        List<ItemDrag> drags = new ArrayList<>();
        List<ItemDrag> frozenDrags = new ArrayList<>();
        VirtualInventory inventory = new VirtualInventory(1);
        Pane frozenPane = Pane.empty(9, 1);
        frozenPane.setElement(0, Element.item(draggableItem(Material.GLASS, frozenDrags)));
        frozenPane.setFrozen(true);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.item(draggableItem(Material.STONE, drags)));
        pane.setElement(1, Element.inventory(inventory, 0));
        pane.setElement(3, Element.pane(frozenPane, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "mixed-drag");
        AtomicReference<Map<Integer, ItemStack>> newItems = new AtomicReference<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                newItems.set(event.getNewItems());
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        dragOver(menus, ClickType.LEFT, 0, 1, 2, 3);
        window.tick();

        assertEquals(Set.of(1), newItems.get().keySet());
        assertEquals(8, inventory.itemAmount(0));
        assertEquals(1, drags.size());
        assertTrue(frozenDrags.isEmpty());
        assertEquals(0, drags.getFirst().windowSlot());
        assertEquals(
                List.of(0, 1, 2, 3),
                drags.getFirst().path().stream().map(ItemDrag.Stop::windowSlot).toList()
        );

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void dragDoesNotAddRecipientsAfterDispatchStarts() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        List<ItemDrag> firstDrags = new ArrayList<>();
        List<ItemDrag> addedDrags = new ArrayList<>();
        Pane pane = Pane.empty(9, 1);
        Item added = draggableItem(Material.DIRT, addedDrags);
        pane.setElement(0, Element.item(Item.builder()
                .setItemProviderConstant(new ItemStack(Material.STONE))
                .addDragHandler(drag -> {
                    firstDrags.add(drag);
                    pane.setElement(1, Element.item(added));
                })
                .build()));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "drag-recipient-snapshot");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        dragOver(menus, ClickType.LEFT, 0, 1);
        window.tick();

        assertEquals(1, firstDrags.size());
        assertTrue(addedDrags.isEmpty());
        assertEquals(
                List.of(0, 1),
                firstDrags.getFirst().path().stream().map(ItemDrag.Stop::windowSlot).toList()
        );

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void cancelledBukkitDragStillReachesTheItem() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        List<ItemDrag> drags = new ArrayList<>();
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.item(draggableItem(Material.STONE, drags)));
        pane.setElement(1, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "cancelled-drag");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleDrag(InventoryDragEvent event) {
                event.setCancelled(true);
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        dragOver(menus, ClickType.LEFT, 0, 1);
        window.tick();

        assertTrue(inventory.isEmpty());
        assertEquals(8, menus.currentHandle.cursor().getAmount());
        assertEquals(1, drags.size());
        assertEquals(0, drags.getFirst().windowSlot());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void emptyCursorDragReachesNoItem() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        List<ItemDrag> drags = new ArrayList<>();
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.item(draggableItem(Material.STONE, drags)));
        pane.setElement(1, Element.item(draggableItem(Material.DIRT, drags)));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "empty-cursor-drag");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        dragOver(menus, ClickType.LEFT, 0, 1);
        window.tick();

        assertTrue(drags.isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void middleDragOutsideCreativeReachesNoItem() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
        player.setGameMode(GameMode.SURVIVAL);
        List<ItemDrag> drags = new ArrayList<>();
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.item(draggableItem(Material.STONE, drags)));
        pane.setElement(1, Element.item(draggableItem(Material.DIRT, drags)));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "middle-drag");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        dragOver(menus, ClickType.MIDDLE, 0, 1);
        window.tick();

        assertTrue(drags.isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void shiftClickOnInvisibleItemReachesHandlerWhileTrueEmptySlotStaysSilent() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        List<ItemClick> clicks = new ArrayList<>();
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.item(Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickHandler(click -> clicks.add(click))
                .build()));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "invisible-item");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.SHIFT_LEFT, -1));
        window.tick();
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 1, ClickType.SHIFT_LEFT, -1));
        window.tick();

        assertEquals(1, clicks.size());
        assertEquals(0, clicks.getFirst().windowSlot());
        assertEquals(ClickType.SHIFT_LEFT, clicks.getFirst().clickType());
        assertTrue(window.displayedAt(0).isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void doubleClickOnItemSlotOrPaneBackgroundNeverCollects() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 10));
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.item(Item.simple(new ItemStack(Material.STONE))));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "double-click-item");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.currentHandle.cursor(new ItemStack(Material.DIAMOND, 5));
        for (int windowSlot : new int[]{1, 2, 20}) {
            menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                    menus.lastContainerId, menus.currentHandle.stateId(), windowSlot, ClickType.DOUBLE_CLICK, -1));
            window.tick();

            assertEquals(10, inventory.itemAmount(0));
            assertEquals(5, menus.currentHandle.cursor().getAmount());
        }

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void shiftClickOnEmptyInventorySlotReachesSparrowClickEvent() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "shift-empty-slot");
        List<SparrowInventoryClickEvent> events = new ArrayList<>();
        Subscription subscription = inventory.subscribeClick(events::add);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.SHIFT_LEFT, -1));
        window.tick();

        assertEquals(1, events.size());
        assertSame(inventory, events.getFirst().inventory());
        assertEquals(0, events.getFirst().slot());
        assertEquals(ClickType.SHIFT_LEFT, events.getFirst().clickType());
        assertEquals(InventoryAction.NOTHING, events.getFirst().action());
        subscription.close();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void writesFromSparrowClickEventOnUnplannedClickStillCommit() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "unplanned-write");
        Subscription subscription = inventory.subscribeClick(event -> event.edits().slot(0, new ItemStack(Material.COAL, 3)));

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.SHIFT_LEFT, -1));
        window.tick();

        assertEquals(new ItemStack(Material.COAL, 3), inventory.itemAt(0));
        subscription.close();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void doubleClickOnLoadedInventorySlotSkipsCollectButEmptyOneCollects() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 10));
        inventory.setItem(UpdateReason.Program.INSTANCE, 1, new ItemStack(Material.DIAMOND, 10));
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.inventory(inventory, 1));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "double-click-inventory");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.currentHandle.cursor(new ItemStack(Material.DIAMOND, 5));
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.DOUBLE_CLICK, -1));
        window.tick();

        assertEquals(10, inventory.itemAmount(0));
        assertEquals(10, inventory.itemAmount(1));
        assertEquals(5, menus.currentHandle.cursor().getAmount());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, null);
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.DOUBLE_CLICK, -1));
        window.tick();

        assertNull(inventory.itemAt(1));
        assertEquals(15, menus.currentHandle.cursor().getAmount());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void noopInventoryClicksReachBothListenersAndBundleSelectionStillPublishes() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "inventory-events");
        AtomicReference<SparrowInventoryClickEvent> clickEvent = new AtomicReference<>();
        AtomicReference<net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent> bundleEvent = new AtomicReference<>();
        AtomicBoolean cancelClick = new AtomicBoolean();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger bukkitClicks = new AtomicInteger();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitClicks.incrementAndGet();
            }
        }, this.plugin);
        inventory.subscribeClick(event -> {
            clickEvent.set(event);
            if (cancelClick.get()) {
                event.cancel();
            }
        });
        inventory.subscribeBundleSelect(bundleEvent::set);
        inventory.subscribePreUpdate(ignoredEvent -> preCalls.incrementAndGet());

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.BundleSelection(menus.lastContainerId, 0, 2)
        );
        window.tick();

        assertEquals(InventoryAction.NOTHING, clickEvent.get().action());
        assertSame(inventory, clickEvent.get().inventory());
        assertEquals(1, bukkitClicks.get());
        assertEquals(0, preCalls.get());
        assertSame(inventory, bundleEvent.get().inventory());
        assertSame(player, bundleEvent.get().player());
        assertSame(window, bundleEvent.get().window());
        assertEquals(0, bundleEvent.get().inventorySlot());
        assertEquals(0, bundleEvent.get().windowSlot());
        assertEquals(2, bundleEvent.get().bundleSlot());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(Material.DIAMOND, 5));
        preCalls.set(0);
        clickEvent.set(null);
        cancelClick.set(true);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertTrue(clickEvent.get().cancelled());
        assertEquals(2, bukkitClicks.get());
        assertEquals(0, preCalls.get());
        assertEquals(5, inventory.itemAmount(0));
        assertTrue(menus.currentHandle.cursor().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void inventoryBukkitEventSwitchAndGlobalMasterApplyToNoopClicks() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        VirtualInventory inventory = new VirtualInventory(1);
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "inventory-event-switch");
        AtomicInteger bukkitCalls = new AtomicInteger();
        AtomicInteger sparrowCalls = new AtomicInteger();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                bukkitCalls.incrementAndGet();
            }
        }, this.plugin);
        inventory.subscribeClick(event -> sparrowCalls.incrementAndGet());

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        inventory.fireBukkitInventoryEvents(false);
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1));
        window.tick();

        assertEquals(0, bukkitCalls.get());
        assertEquals(1, sparrowCalls.get());
        inventory.fireBukkitInventoryEvents(true);
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1));
        window.tick();

        assertEquals(1, bukkitCalls.get());
        assertEquals(2, sparrowCalls.get());
        SparrowUI.getInstance().fireBukkitInventoryEvents(false);
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.Click(
                menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.LEFT, -1));
        window.tick();

        assertEquals(1, bukkitCalls.get());
        assertEquals(3, sparrowCalls.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void bundleSelectionDoesNotSynchronizeClientLocalState() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta bundleMeta = (BundleMeta) bundle.getItemMeta();
        bundleMeta.addItem(new ItemStack(Material.DIAMOND));
        bundle.setItemMeta(bundleMeta);
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{bundle});
        AtomicInteger selectedBundleSlot = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribeBundleSelect(event -> selectedBundleSlot.set(event.bundleSlot()));
        inventory.subscribePostUpdate(ignoredEvent -> postCalls.incrementAndGet());
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "bundle-selection");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        int synchronizationCount = menus.synchronizations.size();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.BundleSelection(menus.lastContainerId, 0, 0)
        );
        window.tick();

        assertEquals(0, selectedBundleSlot.get());
        assertEquals(synchronizationCount, menus.synchronizations.size());
        assertEquals(List.of(new ItemStack(Material.DIAMOND)), ((BundleMeta) inventory.itemAt(0).getItemMeta()).getItems());
        assertEquals(0, postCalls.get());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.BundleSelection(menus.lastContainerId, 0, -1)
        );
        window.tick();

        assertEquals(-1, selectedBundleSlot.get());
        assertEquals(synchronizationCount, menus.synchronizations.size());
        assertEquals(0, postCalls.get());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void selectedBundleItemIsTakenByFollowingRightClick() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta bundleMeta = (BundleMeta) bundle.getItemMeta();
        bundleMeta.setItems(List.of(new ItemStack(Material.DIAMOND), new ItemStack(Material.EMERALD, 4)));
        bundle.setItemMeta(bundleMeta);
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{bundle, null});
        AtomicReference<InventoryAction> clickAction = new AtomicReference<>();
        AtomicReference<UpdateReason> postReason = new AtomicReference<>();
        inventory.subscribeClick(event -> clickAction.set(event.action()));
        inventory.subscribePostUpdate(event -> postReason.set(event.reason()));
        Pane pane = Pane.empty(9, 1);
        pane.setElement(0, Element.inventory(inventory, 0));
        pane.setElement(1, Element.inventory(inventory, 1));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "bundle-take");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.BundleSelection(menus.lastContainerId, 0, 1)
        );
        window.tick();

        assertNull(postReason.get());
        inventory.setItem(UpdateReason.Program.INSTANCE, 1, new ItemStack(Material.PAPER));
        window.tick();
        postReason.set(null);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 0, ClickType.RIGHT, -1)
        );
        window.tick();

        assertEquals(InventoryAction.PICKUP_FROM_BUNDLE, clickAction.get());
        assertEquals(new ItemStack(Material.EMERALD, 4), menus.currentHandle.cursor());
        assertEquals(List.of(new ItemStack(Material.DIAMOND)), ((BundleMeta) inventory.itemAt(0).getItemMeta()).getItems());
        PlayerUpdateReason.Click reason = assertInstanceOf(PlayerUpdateReason.Click.class, postReason.get());

        assertEquals(ClickType.RIGHT, reason.clickType());
        assertEquals(-1, reason.hotbarButton());
    }

    @Test
    void bukkitListenerClosingTheWindowInvalidatesTheOriginalClick() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        AtomicInteger itemClicks = new AtomicInteger();
        StaticItem item = new StaticItem(ItemProvider.EMPTY, (ignoredItem, ignoredClick) -> itemClicks.incrementAndGet());
        Pane pane = Pane.filled(9, 1, item);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = window(manager, player, pane, "reentrant-close");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                window.close();
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                0,
                new MenuInput.Common.Click(menus.lastContainerId, 1, 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertFalse(window.isOpen());
        assertTrue(manager.windows().isEmpty());
        assertEquals(0, itemClicks.get());
        assertEquals(0, menus.live.get());
    }

    @Test
    void titleUpdateDuringBukkitListenerIsCoalescedWithoutInvalidatingClick() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        AtomicInteger itemClicks = new AtomicInteger();
        StaticItem item = new StaticItem(ItemProvider.EMPTY, (ignoredItem, ignoredClick) -> itemClicks.incrementAndGet());
        Pane pane = Pane.filled(9, 1, item);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = window(manager, player, pane, "before-event");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            private void handleClick(InventoryClickEvent event) {
                window.setTitle(Component.text("during-event"));
            }
        }, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                0,
                new MenuInput.Common.Click(menus.lastContainerId, 1, 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertTrue(window.isOpen());
        assertEquals(Component.text("during-event"), window.title());
        assertEquals(1, itemClicks.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void outsideClickBuilderHandlersReceiveEachConcreteWindow() {
        manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory());
        AtomicReference<AnvilWindow> setHandlerWindow = new AtomicReference<>();
        AtomicReference<AnvilWindow> addedHandlerWindow = new AtomicReference<>();
        AnvilWindow.Builder builder = AnvilWindow.builder()
                .setOutsideClickHandlers(List.of((window, ignoredClick) -> {
                    window.getRenameText();
                    setHandlerWindow.set(window);
                }))
                .addOutsideClickHandler((window, ignoredClick) -> {
                    window.getRenameText();
                    addedHandlerWindow.set(window);
                });
        AnvilWindow first = builder.build(connectedPlayer(PlayerStub.addTo(this.server)));
        WindowOutsideClick firstClick = new WindowOutsideClick(first.viewer(), first, ClickType.LEFT, ItemStack.empty(), -1);
        first.getOutsideClickHandlers().forEach(handler -> handler.accept(firstClick));

        assertSame(first, setHandlerWindow.get());
        assertSame(first, addedHandlerWindow.get());
        AnvilWindow second = builder.build(connectedPlayer(PlayerStub.addTo(this.server)));
        WindowOutsideClick secondClick = new WindowOutsideClick(second.viewer(), second, ClickType.LEFT, ItemStack.empty(), -1);
        second.getOutsideClickHandlers().forEach(handler -> handler.accept(secondClick));

        assertSame(second, setHandlerWindow.get());
        assertSame(second, addedHandlerWindow.get());
    }

    @Test
    void outsideClickCancellationPreventsCursorDrop() {
        List<ItemStack> drops = new ArrayList<>();
        Player player = dropTrackingPlayer(this.server.addPlayer(), drops);
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 7));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "outside-cancel"
        );
        AtomicInteger outsideClicks = new AtomicInteger();
        AtomicReference<WindowOutsideClick> outsideClick = new AtomicReference<>();
        window.addOutsideClickHandler(event -> {
            outsideClicks.incrementAndGet();
            outsideClick.set(event);
            event.setCancelled(true);
        });

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        TrackingMenuHandle handle = menus.currentHandle;
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(
                        menus.lastContainerId,
                        handle.stateId(),
                        -999,
                        ClickType.WINDOW_BORDER_LEFT,
                        -1
                )
        );
        window.tick();

        assertEquals(1, outsideClicks.get());
        assertSame(player, outsideClick.get().getPlayer());
        assertSame(window, outsideClick.get().getWindow());
        assertEquals(new ItemStack(Material.DIAMOND, 7), outsideClick.get().getCursor());
        assertEquals(new ItemStack(Material.DIAMOND, 7), handle.cursor());
        assertEquals(new ItemStack(Material.DIAMOND, 7), menus.lastSynchronizedCursor);
        assertTrue(drops.isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void creativeMiddleClickOutsideTheWindowIsANoOp() {
        List<ItemStack> drops = new ArrayList<>();
        Player player = dropTrackingPlayer(this.server.addPlayer(), drops);
        player.setGameMode(GameMode.CREATIVE);
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 7));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "outside-middle"
        );
        AtomicInteger outsideClicks = new AtomicInteger();
        window.addOutsideClickHandler(event -> outsideClicks.incrementAndGet());

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        TrackingMenuHandle handle = menus.currentHandle;
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(
                        menus.lastContainerId,
                        handle.stateId(),
                        -999,
                        ClickType.MIDDLE,
                        -1
                )
        );
        window.tick();

        assertEquals(1, outsideClicks.get(), "容器外点击的通知契约保留, 与丢物语义无关");
        assertEquals(new ItemStack(Material.DIAMOND, 7), handle.cursor(), "光标保持原样");
        assertTrue(drops.isEmpty(), "中键点击窗外没有原版丢物语义");
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void itemClickCapturesWindowSlotAndAuthoritativeCursor() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        ItemStack cursor = new ItemStack(Material.EMERALD, 4);
        player.setItemOnCursor(cursor);
        AtomicReference<ItemClick> itemClick = new AtomicReference<>();
        StaticItem item = new StaticItem(ItemProvider.EMPTY, (ignoredItem, click) -> itemClick.set(click));
        Pane pane = Pane.empty(9, 1);
        pane.setItem(4, item);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, pane, "item-contexts");

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(menus.lastContainerId, menus.currentHandle.stateId(), 4, ClickType.LEFT, -1)
        );
        window.tick();

        assertSame(player, itemClick.get().player());
        assertSame(window, itemClick.get().window());
        assertEquals(4, itemClick.get().windowSlot());
        assertEquals(cursor, itemClick.get().cursor());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void normalWindowExposesRootPaneMappings() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        Pane pane = Pane.empty(9, 1);
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()),
                player,
                pane,
                "mappings"
        );

        assertEquals(2, window.panes().size());
        assertSame(pane, window.panes().getFirst());
        assertSame(pane, window.paneAt(8).pane());
        assertEquals(8, window.paneAt(8).slot());
        assertSame(window.panes().get(1), window.paneAt(9).pane());
        assertEquals(0, window.paneAt(9).slot());
        assertSame(window.panes().get(1), window.paneAtHotbar(0).pane());
        assertEquals(27, window.paneAtHotbar(0).slot());
        assertThrows(IndexOutOfBoundsException.class, () -> window.paneAtHotbar(9));
    }

    @Test
    void titleChangesAreCoalescedIntoOneReopenPerTick() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "initial"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.setTitle(Component.text("first"));
        window.setTitle(Component.text("second"));

        assertTrue(menus.titleUpdates.isEmpty());
        window.tick();

        assertEquals(List.of(Component.text("second")), menus.titleUpdates);
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void titleEqualToLastSentTitleDoesNotReopen() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "initial"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.setTitle(Component.text("initial"));
        window.tick();

        assertTrue(menus.titleUpdates.isEmpty());
        window.setTitle(Component.text("changed"));
        window.setTitle(Component.text("initial"));
        window.tick();

        assertTrue(menus.titleUpdates.isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void failedTitleReopenIsRetriedAfterReturningToLastSentTitle() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "initial"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.failNextTitleUpdate.set(true);
        window.setTitle(Component.text("changed"));
        window.tick();

        assertTrue(menus.titleUpdates.isEmpty());
        window.setTitle(Component.text("initial"));
        window.tick();

        assertEquals(List.of(Component.text("initial")), menus.titleUpdates);
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void titleAnimationFrameAdvancesReopenAndEqualFramesAreDebounced() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "initial");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            AnimationHandle handle = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("frame-" + elapsedTicks / 2)));
            window.tick();

            assertEquals(List.of(Component.text("frame-0")), menus.titleUpdates, "入场帧经重开发送");
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(List.of(Component.text("frame-0")), menus.titleUpdates, "帧内容没变的拍被等值去抖吞掉");
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(List.of(Component.text("frame-0"), Component.text("frame-1")), menus.titleUpdates, "帧变化的拍经重开发送新帧");
            handle.cancel();

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void titleAnimationNullFrameFallsThroughAndCoversSetTitleMeanwhile() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "initial");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            AnimationHandle handle = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> elapsedTicks < 1 ? Component.text("cover") : null));
            window.tick();

            assertEquals(List.of(Component.text("cover")), menus.titleUpdates);
            window.setTitle(Component.text("changed"));
            window.tick();

            assertEquals(List.of(Component.text("cover")), menus.titleUpdates, "播放期间 setTitle 被动画帧盖住");
            assertEquals(Component.text("changed"), window.title(), "配置标题快照不被动画帧污染");
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(List.of(Component.text("cover"), Component.text("changed")), menus.titleUpdates, "null 帧放行显示配置标题");
            handle.cancel();
            window.tick();

            assertEquals(2, menus.titleUpdates.size(), "摘层时已显示配置标题, 不再重开");
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void stackedTitleAnimationsNewestOutermostAndFallThrough() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "initial");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            AnimationHandle first = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("inner")));
            window.tick();

            assertEquals(List.of(Component.text("inner")), menus.titleUpdates);
            AnimationHandle second = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> elapsedTicks < 2 ? Component.text("outer") : null));
            window.tick();

            assertEquals(List.of(Component.text("inner"), Component.text("outer")), menus.titleUpdates, "后开始的播放盖住先开始的");
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(2, menus.titleUpdates.size());
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(List.of(Component.text("inner"), Component.text("outer"), Component.text("inner")), menus.titleUpdates, "外层放行时逐层下落到更早开始的播放");
            second.cancel();
            first.cancel();

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void titleAnimationCompletesRestoresConfiguredTitleExactlyOnce() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "initial");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            List<AnimationHandle.FinishReason> reasons = new ArrayList<>();
            AnimationHandle handle = window.playTitleAnimation(TitleAnimationDefinition.of(1, 2, elapsedTicks -> Component.text("boom-" + elapsedTicks)));
            handle.whenFinished(reasons::add);
            window.tick();
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(List.of(Component.text("boom-0"), Component.text("boom-1")), menus.titleUpdates);
            TickingTestSupport.advance(1);

            assertEquals(List.of(AnimationHandle.FinishReason.COMPLETED), reasons, "到点自然播完恰好结束一次");
            assertFalse(TickingTestSupport.scheduled(), "播完后时钟解绑停摆");
            window.tick();

            assertEquals(List.of(Component.text("boom-0"), Component.text("boom-1"), Component.text("initial")), menus.titleUpdates, "结束摘层后配置标题回归");
            handle.cancel();

            assertEquals(List.of(AnimationHandle.FinishReason.COMPLETED), reasons, "终态一经落定不再触发");
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void cancelledTitleAnimationRestoresConfiguredTitleExactlyOnce() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "initial");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            List<AnimationHandle.FinishReason> reasons = new ArrayList<>();
            AnimationHandle handle = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("covering")));
            handle.whenFinished(reasons::add);
            window.tick();

            assertEquals(List.of(Component.text("covering")), menus.titleUpdates);
            handle.cancel();

            assertEquals(List.of(AnimationHandle.FinishReason.CANCELLED), reasons);
            assertFalse(TickingTestSupport.scheduled(), "取消后时钟解绑停摆");
            window.tick();

            assertEquals(List.of(Component.text("covering"), Component.text("initial")), menus.titleUpdates, "取消摘层后配置标题回归");
            handle.cancel();

            assertEquals(List.of(AnimationHandle.FinishReason.CANCELLED), reasons, "重复取消不再触发");
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void closingWindowFinishesTitleAndSlotAnimationsTogether() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "initial");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            List<AnimationHandle.FinishReason> slotReasons = new ArrayList<>();
            List<AnimationHandle.FinishReason> titleReasons = new ArrayList<>();
            window.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> null))
                    .whenFinished(slotReasons::add);
            window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("closing")))
                    .whenFinished(titleReasons::add);

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), slotReasons, "关窗终结槽位动画");
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), titleReasons, "关窗一并终结标题动画");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void titleAnimationPlayedBeforeOpenIsTheFirstOpenedTitle() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "configured");
            List<AnimationHandle.FinishReason> reasons = new ArrayList<>();
            window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("preopen")))
                    .whenFinished(reasons::add);

            assertTrue(TickingTestSupport.scheduled(), "打开前播放时间轴照走");
            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            assertEquals(List.of(Component.text("preopen")), menus.openedTitles, "打开首帧即当前动画帧");
            assertTrue(menus.titleUpdates.isEmpty(), "打开本身不算重开");
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), reasons);
            assertFalse(TickingTestSupport.scheduled(), "关窗终结后时钟停摆");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void collectedWindowStopsTheTitleAnimationClock() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            WindowManager manager = manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory());
            List<AnimationHandle> handles = new ArrayList<>();
            WeakReference<Object> probe = playTitleAnimationAndDropWindow(manager, player, handles);

            assertTrue(TickingTestSupport.scheduled(), "播放中时钟在走");
            GcSupport.awaitCollected(probe);
            TickingTestSupport.advance(1);

            assertFalse(TickingTestSupport.scheduled(), "宿主回收后时钟停摆");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void titleAnimationStartedWhileFinishingEndsAtOnceAndLeavesTheChannelEmpty() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "configured");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            List<AnimationHandle.FinishReason> introReasons = new ArrayList<>();
            List<AnimationHandle.FinishReason> chainedReasons = new ArrayList<>();
            window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("intro")))
                    .whenFinished(reason -> {
                        introReasons.add(reason);
                        window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("chained")))
                                .whenFinished(chainedReasons::add);
                    });

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), introReasons);
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), chainedReasons, "终结期间入场的播放当场以同一原因结束");
            assertFalse(TickingTestSupport.scheduled(), "它没有挂上时钟, 关窗后不留空转");
            menus.openedTitles.clear();

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            assertEquals(List.of(Component.text("configured")), menus.openedTitles, "重开的标题不被上一次的播放劫持");
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void slotAnimationStartedWhileFinishingEndsAtOnceAndLeavesTheChannelEmpty() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, Pane.empty(9, 1), "configured");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            ItemProvider chainedFrame = ItemProvider.constant(new ItemStack(Material.STONE));
            List<AnimationHandle.FinishReason> introReasons = new ArrayList<>();
            List<AnimationHandle.FinishReason> chainedReasons = new ArrayList<>();
            window.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> null))
                    .whenFinished(reason -> {
                        introReasons.add(reason);
                        window.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> chainedFrame))
                                .whenFinished(chainedReasons::add);
                    });

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), introReasons);
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), chainedReasons, "终结期间入场的播放当场以同一原因结束");
            assertFalse(TickingTestSupport.scheduled(), "它没有挂上时钟, 关窗后不留空转");
            assertNull(window.visual().visualize(0, null), "槽位通道已空, 重开不会显示上一次的动画帧");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void slotAnimationStartedFromTitleFinishCallbackEndsAtOnceAsWell() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, Pane.empty(9, 1), "configured");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            ItemProvider chainedFrame = ItemProvider.constant(new ItemStack(Material.STONE));
            List<AnimationHandle.FinishReason> chainedReasons = new ArrayList<>();
            window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("intro")))
                    .whenFinished(reason -> window.visual().play(AnimationDefinition.of(new int[]{0}, 1, -1, (orderIndex, slot, elapsedTicks, actual) -> chainedFrame))
                            .whenFinished(chainedReasons::add));

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
            assertEquals(List.of(AnimationHandle.FinishReason.WINDOW_CLOSED), chainedReasons, "跨通道续播同样当场以关闭原因结束");
            assertFalse(TickingTestSupport.scheduled(), "关窗后不留空转时钟");
            assertNull(window.visual().visualize(0, null), "槽位通道已空");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void zeroTickTitleAnimationCompletesAtOnceWithoutScheduling() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()), player, Pane.empty(9, 1), "configured");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            List<AnimationHandle.FinishReason> reasons = new ArrayList<>();
            window.playTitleAnimation(TitleAnimationDefinition.of(1, 0, elapsedTicks -> Component.text("flash")))
                    .whenFinished(reasons::add);

            assertEquals(List.of(AnimationHandle.FinishReason.COMPLETED), reasons, "零时长播放出生即到点, 回调同步完成");
            assertFalse(TickingTestSupport.scheduled(), "没有挂上时钟");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void throwingTitleFrameYieldsToTheLayerBelowAndKeepsSynchronizing() {
        TickingTestSupport.install();
        try {
            Player player = connectedPlayer(PlayerStub.addTo(this.server));
            TrackingMenuFactory menus = new TrackingMenuFactory();
            AbstractWindow<?> window = window(manager(new OwnedDispatcher(this.plugin), menus), player, Pane.empty(9, 1), "configured");

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            AtomicBoolean explode = new AtomicBoolean();
            AnimationHandle inner = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("inner")));
            AnimationHandle outer = window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> {
                if (explode.get()) {
                    throw new IllegalStateException("frame boom");
                }
                return Component.text("outer");
            }));
            window.tick();

            assertEquals(List.of(Component.text("outer")), menus.titleUpdates);
            explode.set(true);
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(List.of(Component.text("outer"), Component.text("inner")), menus.titleUpdates, "抛出的那一层按放行处理");
            inner.cancel();
            TickingTestSupport.advance(1);
            window.tick();

            assertEquals(
                    List.of(Component.text("outer"), Component.text("inner"), Component.text("configured")),
                    menus.titleUpdates,
                    "全部放行后落到配置标题"
            );
            outer.cancel();

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        } finally {
            TickingTestSupport.restore();
        }
    }

    private static WeakReference<Object> playTitleAnimationAndDropWindow(WindowManager manager, Player player, List<AnimationHandle> handles) {
        AbstractWindow<?> window = window(manager, player, Pane.empty(9, 1), "gc");
        handles.add(window.playTitleAnimation(TitleAnimationDefinition.of(1, -1, elapsedTicks -> Component.text("gc"))));
        return new WeakReference<>(window);
    }

    @Test
    void handlerRemovalUsesTheRegisteredConsumerIdentity() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory()),
                player,
                Pane.empty(9, 1),
                "handlers"
        );
        Consumer<WindowCloseReason> closeHandler = ignoredReason -> { };
        Consumer<WindowOutsideClick> outsideClickHandler = ignoredClick -> { };
        Consumer<Integer> stateHandler = ignoredState -> { };
        window.addCloseHandler(closeHandler);
        window.addOutsideClickHandler(outsideClickHandler);
        window.addWindowStateChangeHandler(stateHandler);

        assertSame(closeHandler, window.getCloseHandlers().getFirst());
        assertSame(outsideClickHandler, window.getOutsideClickHandlers().getFirst());
        assertSame(stateHandler, window.getWindowStateChangeHandlers().getFirst());
        window.removeCloseHandler(closeHandler);
        window.removeOutsideClickHandler(outsideClickHandler);
        window.removeWindowStateChangeHandler(stateHandler);

        assertTrue(window.getCloseHandlers().isEmpty());
        assertTrue(window.getOutsideClickHandlers().isEmpty());
        assertTrue(window.getWindowStateChangeHandlers().isEmpty());
    }

    @Test
    void builderBoundHandlersReceiveTheWindowBuiltForThatViewer() {
        Player first = connectedPlayer(PlayerStub.addTo(this.server));
        Player second = connectedPlayer(PlayerStub.addTo(this.server));
        manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory());
        List<Window> opened = new ArrayList<>();
        List<Window> closed = new ArrayList<>();
        List<WindowCloseReason> reasons = new ArrayList<>();
        NormalWindow.Builder builder = NormalWindow.builder()
                .setUpperPane(Pane.empty(9, 1))
                .addOpenHandler(window -> opened.add(window))
                .addCloseHandler((window, reason) -> {
                    closed.add(window);
                    reasons.add(reason);
                });
        NormalWindow firstWindow = builder.build(first);
        NormalWindow secondWindow = builder.build(second);

        assertEquals(Window.OpenResult.OPENED, firstWindow.open().toCompletableFuture().join());
        assertEquals(Window.CloseResult.CLOSED, firstWindow.close().toCompletableFuture().join());
        assertEquals(Window.OpenResult.OPENED, secondWindow.open().toCompletableFuture().join());
        assertEquals(Window.CloseResult.CLOSED, secondWindow.close().toCompletableFuture().join());
        assertEquals(2, opened.size());
        assertSame(firstWindow, opened.get(0), "可复用 Builder 上的处理器收到自己那一次 build 的 Window");
        assertSame(secondWindow, opened.get(1));
        assertSame(first, opened.get(0).viewer());
        assertSame(second, opened.get(1).viewer());
        assertEquals(2, closed.size());
        assertSame(firstWindow, closed.get(0));
        assertSame(secondWindow, closed.get(1));
        assertEquals(List.of(WindowCloseReason.PLUGIN, WindowCloseReason.PLUGIN), reasons);
    }

    @Test
    void builderRunsOpenAndCloseHandlersInDeclaredOrder() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        manager(new OwnedDispatcher(this.plugin), new TrackingMenuFactory());
        List<String> events = new ArrayList<>();
        List<Consumer<? super NormalWindow>> openHandlers = List.of(ignoredWindow -> events.add("open-first"));
        List<BiConsumer<? super NormalWindow, ? super WindowCloseReason>> closeHandlers =
                List.of((ignoredWindow, ignoredReason) -> events.add("close-first"));
        NormalWindow window = NormalWindow.builder()
                .setUpperPane(Pane.empty(9, 1))
                .setOpenHandlers(openHandlers)
                .addOpenHandler(ignoredWindow -> events.add("open-second"))
                .setCloseHandlers(closeHandlers)
                .addCloseHandler((ignoredWindow, ignoredReason) -> events.add("close-second"))
                .build(player);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(List.of("open-first", "open-second"), events, "整列替换与逐个追加共用一条声明顺序");
        events.clear();

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        assertEquals(List.of("close-first", "close-second"), events);
    }

    @Test
    void backOnPlayerCloseDefaultsFalseAndRoundTrips() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "back-on-player-close"
        );

        assertFalse(window.backOnPlayerClose());
        window.backOnPlayerClose(true);

        assertTrue(window.backOnPlayerClose());
        window.backOnPlayerClose(false);

        assertFalse(window.backOnPlayerClose());
    }

    @Test
    void clientCloseRespectsCloseable() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "closeable"
        );
        List<WindowCloseReason> reasons = new ArrayList<>();
        window.addCloseHandler(reasons::add);
        window.setCloseable(false);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(0, new MenuInput.Common.Close(menus.lastContainerId));
        window.tick();

        assertTrue(window.isOpen());
        assertEquals(1, menus.titleUpdates.size());
        window.setCloseable(true);
        menus.offerInput(0, new MenuInput.Common.Close(menus.lastContainerId));
        window.tick();

        assertFalse(window.isOpen());
        assertEquals(List.of(WindowCloseReason.PLAYER), reasons);
        assertEquals(List.of(WindowCloseReason.PLAYER), menus.closeReasons);
    }

    @Test
    void externalInventoryCloseCannotBeVetoedByCloseable() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 7));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager windowManager = manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = window(
                windowManager,
                player,
                Pane.empty(9, 1),
                "external-close"
        );
        List<WindowCloseReason> reasons = new ArrayList<>();
        window.setCloseable(false);
        window.addCloseHandler(reasons::add);
        Bukkit.getPluginManager().registerEvents(windowManager, this.plugin);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.completeExternalClose();
        Bukkit.getPluginManager().callEvent(
                new InventoryCloseEvent(menus.currentHandle.view(), InventoryCloseEvent.Reason.PLUGIN)
        );

        assertFalse(window.isOpen());
        assertEquals(List.of(WindowCloseReason.PLUGIN), reasons);
        assertEquals(List.of(WindowCloseReason.PLUGIN), menus.closeReasons);
        assertEquals(new ItemStack(Material.DIAMOND, 7), player.getItemOnCursor());
        assertTrue(menus.currentHandle.cursor().isEmpty());
    }

    @Test
    void pongAcknowledgesWindowStateAndInvokesHandlers() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "state"
        );
        List<Integer> acknowledgements = new ArrayList<>();
        window.addWindowStateChangeHandler(acknowledgements::add);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.setWindowState(42);
        int pingId = menus.pingIds.getLast();
        menus.offerInput(0, new MenuInput.Common.Pong(pingId));
        window.tick();

        assertEquals(42, window.serverWindowState());
        assertEquals(42, window.clientWindowState());
        assertEquals(List.of(42), acknowledgements);
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void windowVisualizerAppliesFromTheFirstFrame() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        Pane pane = Pane.empty(9, 1);
        pane.setItem(0, Item.builder().setItemProviderConstant(new ItemStack(Material.DIAMOND)).build());
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                pane,
                "window-visual"
        );
        window.setVisualizerItem(0, ignoredActual -> new ItemStack(Material.BARRIER));

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(Material.BARRIER, window.displayedAt(0).getType());
        assertTrue(menus.lastInitialCursor.isEmpty());
        window.setVisualizerItem(0, null);
        window.tick();

        assertEquals(Material.DIAMOND, window.displayedAt(0).getType());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void cursorVisualizerUsesCursorRenderContextInFullSnapshots() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor"
        );
        AtomicBoolean cursorContextSeen = new AtomicBoolean();
        window.setCursorVisualizerProvider(actual -> {
            assertNull(actual);
            return ItemProvider.sync(context -> {
                cursorContextSeen.set(context.kind == RenderContext.Kind.CURSOR);

                assertSame(window, context.window);
                return new ItemStack(Material.DIAMOND);
            });
        });

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertTrue(cursorContextSeen.get());
        assertEquals(Material.DIAMOND, menus.lastInitialCursor.getType());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void asynchronousCursorVisualizerPublishesThroughCursorDirty() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "async-cursor"
        );
        player.setItemOnCursor(new ItemStack(Material.STONE, 2));
        CompletableFuture<ItemStack> result = new CompletableFuture<>();
        window.cursorVisual().setVisualizerProvider(actual -> actual == null ? null : ignoredContext -> result);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(Material.STONE, menus.lastInitialCursor.getType());
        result.complete(new ItemStack(Material.DIAMOND));
        window.tick();

        assertEquals(Material.DIAMOND, menus.lastSynchronizedVisualCursor.getType());
        assertTrue(menus.synchronizations.getLast().cursorDirty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void cursorVisualizationNeverBecomesTheAuthoritativeCarriedItem() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 7));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor-authority"
        );
        AtomicReference<ItemStack> firstActual = new AtomicReference<>();
        Function<@Nullable ItemStack, @Nullable ItemStack> cursorVisualizer = actual -> {
            firstActual.set(actual);
            return new ItemStack(Material.BARRIER);
        };
        window.cursorVisual().setVisualizerItem(cursorVisualizer);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(new ItemStack(Material.DIAMOND, 7), firstActual.get());
        assertEquals(new ItemStack(Material.DIAMOND, 7), menus.lastInitialActualCursor);
        assertEquals(Material.BARRIER, menus.lastInitialCursor.getType());
        window.setCursorVisualizerItem(ignoredActual -> new ItemStack(Material.EMERALD));
        window.tick();

        assertEquals(Material.EMERALD, menus.lastSynchronizedVisualCursor.getType());
        window.setCursorVisualizerItem(ignoredActual -> null);
        window.tick();

        assertEquals(new ItemStack(Material.DIAMOND, 7), menus.lastSynchronizedCursor);
        assertEquals(new ItemStack(Material.DIAMOND, 7), menus.lastSynchronizedVisualCursor);
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        assertEquals(new ItemStack(Material.DIAMOND, 7), player.getItemOnCursor());
        assertTrue(menus.currentHandle.cursor().isEmpty());
    }

    @Test
    void cursorVisualIsStableAndCoalescesBackgroundDirtyIntoCursorOnlyFlush() throws InterruptedException {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor-visual-dirty"
        );
        AtomicInteger visualizations = new AtomicInteger();
        Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer = ignoredCursor -> {
            visualizations.incrementAndGet();
            return ItemProvider.EMPTY;
        };
        window.cursorVisual().setVisualizerProvider(cursorVisualizer);

        assertSame(window.cursorVisual(), window.cursorVisual());
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertSame(cursorVisualizer, window.cursorVisualizerProvider());
        Thread notifier = new Thread(window.cursorVisual()::dirty, "cursor-visual-test-notifier");
        notifier.start();
        notifier.join();
        window.cursorVisual().dirty();
        window.cursorVisual().dirty();

        assertEquals(1, visualizations.get(), "dirty 调用线程不应执行 mapper");
        assertTrue(menus.synchronizations.isEmpty());
        window.tick();

        assertEquals(2, visualizations.get());
        assertEquals(1, menus.synchronizations.size());
        TrackingSynchronization synchronization = menus.synchronizations.getLast();

        assertTrue(synchronization.dirtySlots().isEmpty());
        assertTrue(synchronization.cursorDirty());
        assertFalse(synchronization.forceFull());
        window.tick();

        assertEquals(2, visualizations.get());
        assertEquals(1, menus.synchronizations.size(), "同一批 dirty 只应产生一次同步");
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void cursorDirtyArrivingDuringFlushSurvivesSuccessfulSynchronization() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor-visual-race"
        );
        AtomicInteger visualizations = new AtomicInteger();
        AtomicBoolean invalidateDuringRender = new AtomicBoolean();
        window.setCursorVisualizerProvider(ignoredCursor -> {
            visualizations.incrementAndGet();
            if (invalidateDuringRender.compareAndSet(true, false)) {
                window.cursorVisual().dirty();
            }
            return ItemProvider.EMPTY;
        });

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        invalidateDuringRender.set(true);
        window.cursorVisual().dirty();
        window.tick();
        window.tick();

        assertEquals(3, visualizations.get(), "flush 中到达的 dirty 应留给下一 tick");
        assertEquals(2, menus.synchronizations.size());
        assertTrue(menus.synchronizations.get(0).cursorDirty());
        assertTrue(menus.synchronizations.get(1).cursorDirty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void closedCursorDirtyIsConsumedByTheNextInitialSnapshot() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor-visual-reopen"
        );
        MutableSignal<Boolean> highlighted = Signal.of(false);
        AtomicInteger visualizations = new AtomicInteger();
        window.setCursorVisualizerProvider(ignoredCursor -> {
            visualizations.incrementAndGet();
            Material material = highlighted.get() ? Material.DIAMOND : Material.STONE;
            return ItemProvider.constant(new ItemStack(material));
        });
        Subscription binding = window.cursorVisual().bind(highlighted);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(Material.STONE, menus.lastInitialCursor.getType());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        int synchronizations = menus.synchronizations.size();
        highlighted.set(true);

        assertFalse(binding.isClosed(), "关窗不应解除 cursor visual binding");
        window.tick();

        assertEquals(synchronizations, menus.synchronizations.size(), "关闭期间不得发包");
        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(Material.DIAMOND, menus.lastInitialCursor.getType());
        window.tick();

        assertEquals(synchronizations, menus.synchronizations.size(), "首帧应消费关闭期间积累的 dirty");
        assertEquals(2, visualizations.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        binding.close();
    }

    @Test
    void initialOpenRendersEachPaneSlotOnceAndKeepsTheProviderSnapshot() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AtomicInteger renders = new AtomicInteger();
        AtomicReference<ItemStack> provided = new AtomicReference<>();
        Pane pane = Pane.empty(9, 1);
        pane.setItem(0, new StaticItem(ItemProvider.sync(ignoredContext -> {
            ItemStack snapshot = new ItemStack(Material.STONE);
            provided.set(snapshot);
            renders.incrementAndGet();
            return snapshot;
        })));
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                pane,
                "provider-snapshot"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(1, renders.get());
        assertSame(provided.get(), menus.lastInitialFirstSlot);
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void slotSynchronizationReusesTheCurrentCursorSnapshot() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AtomicInteger visualizations = new AtomicInteger();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor-refresh"
        );
        window.setCursorVisualizerProvider(ignoredCursor -> {
            visualizations.incrementAndGet();
            return ItemProvider.EMPTY;
        });

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.notifyUpdate(0);
        window.tick();

        assertEquals(1, visualizations.get());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void explicitFullResendRefreshesTheCursorSnapshot() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AtomicInteger visualizations = new AtomicInteger();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "cursor-full-resend"
        );
        window.setCursorVisualizerProvider(ignoredCursor -> {
            visualizations.incrementAndGet();
            return ItemProvider.EMPTY;
        });

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.notifyUpdateAll();

        assertEquals(2, visualizations.get());
        assertTrue(menus.synchronizations.getLast().forceFull());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void idleTickSkipsContainerSynchronization() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "idle"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.tick();

        assertTrue(menus.synchronizations.isEmpty());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void dirtySlotIsForwardedWithoutForcingAFullSynchronization() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "dirty"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.notifyUpdate(0);
        window.tick();
        TrackingSynchronization synchronization = menus.synchronizations.getLast();

        assertEquals(BitSet.valueOf(new long[]{1}), synchronization.dirtySlots());
        assertFalse(synchronization.cursorDirty());
        assertFalse(synchronization.forceFull());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void staleInteractionRequestsAFullSynchronization() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        AbstractWindow<?> window = window(
                manager(new OwnedDispatcher(this.plugin), menus),
                player,
                Pane.empty(9, 1),
                "stale"
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(
                0,
                new MenuInput.Common.Click(menus.lastContainerId, 0, 0, ClickType.LEFT, -1)
        );
        window.tick();

        assertTrue(menus.synchronizations.getLast().forceFull());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void viewerStorageRefreshFindsExternalPlayerInventoryChangesImmediately() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = (AbstractWindow<?>) NormalWindow.builder()
                .setUpperPane(Pane.empty(9, 1))
                .setTitle("inventory-audit")
                .build(player);

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.tick();
        menus.synchronizations.clear();
        player.getInventory().setItem(0, new ItemStack(Material.STONE));
        window.tick();
        BitSet expected = new BitSet();
        expected.set(36);

        assertEquals(expected, menus.synchronizations.getLast().dirtySlots());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void defaultLowerRefreshesChangesMadeBetweenBuildAndOpen() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        manager(new OwnedDispatcher(this.plugin), menus);
        AbstractWindow<?> window = (AbstractWindow<?>) NormalWindow.builder()
                .setUpperPane(Pane.empty(9, 1))
                .build(player);

        assertEquals(2, window.panes().size());
        assertSame(window.panes().get(1), window.paneAtHotbar(0).pane());
        player.getInventory().setItem(0, new ItemStack(Material.STONE));

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(Material.STONE, menus.lastInitialSlots[36].getType());
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void hopperAndAnvilWindowsSelectTheirExplicitMenuFactories() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        HopperWindow hopper = new HopperWindowImpl(
                manager,
                player,
                WindowLayout.split(Pane.empty(5, 1), Pane.empty(9, 4)),
                settings()
        );
        AnvilWindow anvil = new AnvilWindowImpl(
                manager,
                player,
                WindowLayout.split(Pane.empty(3, 1), Pane.empty(9, 4)),
                settings(),
                4,
                true,
                false,
                List.of()
        );

        assertEquals(Window.OpenResult.OPENED, hopper.open().toCompletableFuture().join());
        assertEquals(MenuKind.HOPPER, menus.lastKind);
        assertEquals(41, menus.lastInitialSlotCount);
        assertEquals(Window.CloseResult.CLOSED, hopper.close().toCompletableFuture().join());
        assertEquals(Window.OpenResult.OPENED, anvil.open().toCompletableFuture().join());
        assertEquals(MenuKind.ANVIL, menus.lastKind);
        assertEquals(39, menus.lastInitialSlotCount);
        TrackingAnvilMenuHandle handle = assertInstanceOf(TrackingAnvilMenuHandle.class, menus.lastHandle);

        assertEquals(4, handle.enchantmentCost);
        assertTrue(handle.textFieldAlwaysEnabled);
        assertFalse(handle.resultAlwaysValid);
        assertEquals(Window.CloseResult.CLOSED, anvil.close().toCompletableFuture().join());
    }

    @Test
    void anvilRenameAndPropertiesConvergeOnEntityTick() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        List<String> renamed = new ArrayList<>();
        AnvilWindowImpl window = new AnvilWindowImpl(
                manager,
                player,
                WindowLayout.split(Pane.empty(3, 1), Pane.empty(9, 4)),
                settings(),
                0,
                true,
                false,
                List.of(renamed::add)
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        menus.offerInput(menus.lastGeneration, new MenuInput.WindowSpecific.Rename("Sparrow"));
        window.tick();

        assertEquals("Sparrow", window.getRenameText());
        assertEquals(List.of("Sparrow"), renamed);
        TrackingAnvilMenuHandle handle = assertInstanceOf(TrackingAnvilMenuHandle.class, menus.lastHandle);

        assertEquals("Sparrow", handle.lastRenameText);
        assertTrue(menus.synchronizations.getLast().dirtySlots().get(2));
        window.setEnchantmentCost(7);
        window.tick();

        assertEquals(7, handle.enchantmentCost);
        assertTrue(menus.synchronizations.getLast().dirtySlots().isEmpty());
        window.setTextFieldAlwaysEnabled(false);
        window.setResultAlwaysValid(true);
        window.tick();

        assertFalse(handle.textFieldAlwaysEnabled);
        assertTrue(handle.resultAlwaysValid);
        BitSet dirty = menus.synchronizations.getLast().dirtySlots();

        assertTrue(dirty.get(0));
        assertTrue(dirty.get(2));
        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
    }

    @Test
    void simpleWindowTypesSelectTheirExplicitFactoriesAndProtocolSizes() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        OwnedDispatcher dispatcher = new OwnedDispatcher(this.plugin);
        WindowManager manager = manager(dispatcher, menus);
        AbstractWindow<?>[] windows = new AbstractWindow<?>[]{
                new DispenserWindowImpl(
                        manager,
                        player,
                        WindowLayout.split(Pane.empty(3, 3), Pane.empty(9, 4)),
                        settings()
                ),
                new DropperWindowImpl(
                        manager,
                        player,
                        WindowLayout.split(Pane.empty(3, 3), Pane.empty(9, 4)),
                        settings()
                ),
                new GrindstoneWindowImpl(
                        manager,
                        player,
                        WindowLayout.of(
                                WindowLayout.Region.upper(Pane.empty(1, 2)),
                                WindowLayout.Region.upper(Pane.empty(1, 1)),
                                WindowLayout.Region.lower(Pane.empty(9, 4))
                        ),
                        settings()
                ),
                new SmithingWindowImpl(
                        manager,
                        player,
                        WindowLayout.split(Pane.empty(4, 1), Pane.empty(9, 4)),
                        settings()
                )
        };
        MenuKind[] expectedKinds = {
                MenuKind.DISPENSER,
                MenuKind.DROPPER,
                MenuKind.GRINDSTONE,
                MenuKind.SMITHING
        };
        int[] expectedSlotCounts = {45, 45, 39, 40};
        for (int index = 0; index < windows.length; index++) {
            AbstractWindow<?> window = windows[index];

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            assertEquals(expectedKinds[index], menus.lastKind);
            assertEquals(expectedSlotCounts[index], menus.lastInitialSlotCount);
            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        }
    }

    @Test
    void brewingAndCartographyWindowsDriveTheirTypedMenuState() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        BrewingWindowImpl brewing = new BrewingWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(3, 1)),
                        WindowLayout.Region.upper(Pane.empty(1, 1)),
                        WindowLayout.Region.upper(Pane.empty(1, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                0.25,
                0.5
        );
        Set<CartographyWindow.MapIcon> initialIcons = Set.of(new CartographyWindow.MapIcon(
                MapCursor.Type.RED_X,
                128,
                128,
                3,
                Component.text("center")
        ));
        CartographyWindowImpl cartography = new CartographyWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(1, 2)),
                        WindowLayout.Region.upper(Pane.empty(1, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                new byte[CartographyWindow.MAP_SIZE * CartographyWindow.MAP_SIZE],
                initialIcons,
                CartographyWindow.View.SMALL
        );

        assertEquals(Window.OpenResult.OPENED, brewing.open().toCompletableFuture().join());
        assertEquals(MenuKind.BREWING, menus.lastKind);
        assertEquals(41, menus.lastInitialSlotCount);
        TrackingBrewingMenuHandle brewingHandle = assertInstanceOf(
                TrackingBrewingMenuHandle.class,
                menus.lastHandle
        );

        assertEquals(0.25, brewingHandle.brewProgress);
        assertEquals(0.5, brewingHandle.fuelProgress);
        brewing.setBrewProgress(0.75);
        brewing.setFuelProgress(1.0);
        brewing.tick();

        assertEquals(0.75, brewingHandle.brewProgress);
        assertEquals(1.0, brewingHandle.fuelProgress);
        assertTrue(menus.synchronizations.getLast().dirtySlots().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, brewing.close().toCompletableFuture().join());
        assertEquals(Window.OpenResult.OPENED, cartography.open().toCompletableFuture().join());
        assertEquals(MenuKind.CARTOGRAPHY, menus.lastKind);
        assertEquals(39, menus.lastInitialSlotCount);
        TrackingCartographyMenuHandle cartographyHandle = assertInstanceOf(
                TrackingCartographyMenuHandle.class,
                menus.lastHandle
        );

        assertEquals(CartographyWindow.View.SMALL, cartographyHandle.view);
        assertEquals(initialIcons, cartographyHandle.icons);
        assertEquals(128, cartographyHandle.lastPatch.width());
        CartographyWindow.MapPatch patch = new CartographyWindow.MapPatch(2, 3, 2, 1, new byte[]{4, 5});
        cartography.applyPatch(patch);
        cartography.setView(CartographyWindow.View.LOCK);
        cartography.setIcons(Set.of());
        cartography.tick();

        assertEquals(patch, cartographyHandle.lastPatch);
        assertEquals(CartographyWindow.View.LOCK, cartographyHandle.view);
        assertTrue(cartographyHandle.icons.isEmpty());
        cartography.resetMap();
        cartography.tick();

        assertEquals(1, cartographyHandle.resetCount);
        assertTrue(cartography.getIcons().isEmpty());
        assertEquals(Window.CloseResult.CLOSED, cartography.close().toCompletableFuture().join());
    }

    @Test
    void crafterAndStonecutterWindowsDriveTheirTypedInputs() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AtomicReference<String> toggledSlot = new AtomicReference<>();
        Pane crafterResult = Pane.empty(1, 1);
        crafterResult.setItem(
                0,
                new StaticItem(ItemProvider.constant(new ItemStack(Material.DIAMOND)))
        );
        CrafterWindowImpl crafter = new CrafterWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(3, 3)),
                        WindowLayout.Region.lower(Pane.empty(9, 4)),
                        WindowLayout.Region.upper(crafterResult)
                ),
                settings(),
                1 << 4,
                List.of((slot, disabled) -> toggledSlot.set(slot + ":" + disabled))
        );

        assertEquals(Window.OpenResult.OPENED, crafter.open().toCompletableFuture().join());
        assertEquals(MenuKind.CRAFTER, menus.lastKind);
        assertEquals(46, menus.lastInitialSlotCount);
        TrackingCrafterMenuHandle crafterHandle = assertInstanceOf(
                TrackingCrafterMenuHandle.class,
                menus.lastHandle
        );

        assertTrue(crafterHandle.disabledSlots[4]);
        assertTrue(menus.lastInitialSlots[9].isEmpty());
        assertEquals(Material.DIAMOND, menus.lastInitialSlots[45].getType());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.CrafterSlotState(menus.lastContainerId, 4, true)
        );
        crafter.tick();

        assertFalse(crafter.isSlotDisabled(4));
        assertFalse(crafterHandle.disabledSlots[4]);
        assertEquals("4:false", toggledSlot.get());
        assertEquals(Window.CloseResult.CLOSED, crafter.close().toCompletableFuture().join());
        AtomicInteger diamondClicks = new AtomicInteger();
        AtomicInteger emptyItemClicks = new AtomicInteger();
        AtomicReference<String> itemClick = new AtomicReference<>();
        Pane stonecutterUpper = Pane.empty(2, 1);
        Pane buttons = Pane.empty(4, 1);
        buttons.setItem(0, Item.simple(new ItemStack(Material.STONE_BRICKS)));
        buttons.setItem(2, new StaticItem(ItemProvider.EMPTY, (ignoredItem, click) -> {
            emptyItemClicks.incrementAndGet();
            itemClick.set(click.windowSlot() + ":" + click.clickType() + ":" + click.hotbarButton()
                    + ":" + ((StonecutterWindow) click.window()).getSelectedRecipeIndex());
        }));
        buttons.setItem(3, new StaticItem(
                ItemProvider.constant(new ItemStack(Material.DIAMOND)),
                (ignoredItem, click) -> {
                    diamondClicks.incrementAndGet();
                    itemClick.set(click.windowSlot() + ":" + click.clickType() + ":" + click.hotbarButton()
                            + ":" + ((StonecutterWindow) click.window()).getSelectedRecipeIndex());
                }
        ));
        WindowLayout stonecutterLayout = WindowLayout.of(
                WindowLayout.Region.upper(stonecutterUpper),
                WindowLayout.Region.lower(Pane.empty(9, 4)),
                WindowLayout.Region.virtual(buttons)
        );
        StonecutterWindowImpl stonecutter = new StonecutterWindowImpl(
                manager,
                player,
                stonecutterLayout,
                settings(),
                buttons.area(),
                -1
        );

        assertEquals(Window.OpenResult.OPENED, stonecutter.open().toCompletableFuture().join());
        assertEquals(MenuKind.STONECUTTER, menus.lastKind);
        assertEquals(38, menus.lastInitialSlotCount);
        TrackingStonecutterMenuHandle stonecutterHandle = assertInstanceOf(
                TrackingStonecutterMenuHandle.class,
                menus.lastHandle
        );

        assertEquals(4, stonecutterHandle.recipeButtons.size());
        assertEquals(Material.STONE_BRICKS, stonecutterHandle.recipeButtons.get(0).getType());
        assertTrue(stonecutterHandle.recipeButtons.get(1).isEmpty());
        assertTrue(stonecutterHandle.recipeButtons.get(2).isEmpty());
        assertEquals(Material.DIAMOND, stonecutterHandle.recipeButtons.get(3).getType());
        assertEquals(buttons, stonecutter.paneAt(38).pane());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 3)
        );
        stonecutter.tick();

        assertEquals(3, stonecutter.getSelectedRecipeIndex());
        assertEquals(3, stonecutterHandle.selectedRecipeIndex);
        assertEquals(1, diamondClicks.get());
        assertEquals("41:LEFT:-1:3", itemClick.get());
        assertEquals(1, stonecutterHandle.clientSelectionCount);
        buttons.setItem(0, Item.simple(new ItemStack(Material.GOLD_BLOCK)));
        stonecutter.tick();

        assertEquals(3, stonecutter.getSelectedRecipeIndex());
        assertEquals(3, stonecutterHandle.selectedRecipeIndex);
        assertEquals(Material.GOLD_BLOCK, stonecutterHandle.recipeButtons.getFirst().getType());
        assertEquals(2, stonecutterHandle.recipeButtonUpdates);
        assertTrue(menus.synchronizations.getLast().dirtySlots().isEmpty());
        buttons.setItem(0, Item.simple(new ItemStack(Material.GOLD_BLOCK)));
        stonecutter.tick();

        assertEquals(2, stonecutterHandle.recipeButtonUpdates);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 3)
        );
        stonecutter.tick();

        assertEquals(2, diamondClicks.get());
        assertEquals(2, stonecutterHandle.clientSelectionCount);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 1)
        );
        stonecutter.tick();

        assertEquals(1, stonecutter.getSelectedRecipeIndex());
        assertEquals(0, emptyItemClicks.get());
        assertEquals(2, diamondClicks.get());
        assertEquals("41:LEFT:-1:3", itemClick.get());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 2)
        );
        stonecutter.tick();

        assertEquals(2, stonecutter.getSelectedRecipeIndex());
        assertEquals(1, emptyItemClicks.get());
        assertEquals("40:LEFT:-1:2", itemClick.get());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 4)
        );
        stonecutter.tick();

        assertEquals(2, stonecutter.getSelectedRecipeIndex());
        buttons.setElement(2, Element.empty());
        buttons.setElement(3, Element.empty());
        stonecutter.tick();

        assertEquals(-1, stonecutter.getSelectedRecipeIndex());
        assertEquals(-1, stonecutterHandle.selectedRecipeIndex);
        assertEquals(1, stonecutterHandle.recipeButtons.size());
        assertEquals(Material.GOLD_BLOCK, stonecutterHandle.recipeButtons.getFirst().getType());
        assertTrue(menus.synchronizations.getLast().dirtySlots().isEmpty());
        stonecutter.setSelectedRecipeIndex(0);
        stonecutter.tick();

        assertEquals(0, stonecutter.getSelectedRecipeIndex());
        assertEquals(0, stonecutterHandle.selectedRecipeIndex);
        AtomicInteger invalidSelectionReports = new AtomicInteger();
        net.momirealms.sparrow.ui.SparrowUI.getInstance().setExceptionHandler(
                (ignoredMessage, ignoredThrowable) -> invalidSelectionReports.incrementAndGet()
        );
        stonecutter.setSelectedRecipeIndex(1);
        stonecutter.tick();

        assertEquals(0, stonecutter.getSelectedRecipeIndex());
        assertEquals(1, invalidSelectionReports.get());
        assertEquals(Window.CloseResult.CLOSED, stonecutter.close().toCompletableFuture().join());
        stonecutter.setSelectedRecipeIndex(3);

        assertEquals(Window.OpenResult.OPENED, stonecutter.open().toCompletableFuture().join());
        assertEquals(-1, stonecutter.getSelectedRecipeIndex());
        assertThrows(IndexOutOfBoundsException.class, () -> stonecutter.setSelectedRecipeIndex(4));
        assertEquals(Window.CloseResult.CLOSED, stonecutter.close().toCompletableFuture().join());
    }

    @Test
    void enchantmentWindowSynchronizesOptionsAndDispatchesValidatedButtonSnapshots() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        EnchantmentWindow.EnchantOption original = new EnchantmentWindow.EnchantOption(1, null, 1);
        EnchantmentWindow.EnchantOption replacement = new EnchantmentWindow.EnchantOption(2, null, 5);
        EnchantmentWindow.EnchantOption third = new EnchantmentWindow.EnchantOption(3, null, 10);
        AtomicReference<EnchantmentWindowImpl> reentrantWindow = new AtomicReference<>();
        AtomicInteger observedIndex = new AtomicInteger(-1);
        AtomicReference<EnchantmentWindow.EnchantOption> observedOption = new AtomicReference<>();
        AtomicReference<EnchantSelectClick> observedClick = new AtomicReference<>();
        ArrayList<Integer> handlerOrder = new ArrayList<>();
        EnchantmentWindowImpl enchantment = new EnchantmentWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(2, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                new EnchantmentWindow.EnchantOption[] {original, null, null},
                41,
                List.of(
                        ignoredClick -> {
                            handlerOrder.add(0);
                            reentrantWindow.get().setOption(0, replacement);
                        },
                        ignoredClick -> {
                            handlerOrder.add(1);
                            throw new IllegalStateException("injected enchantment selection handler failure");
                        },
                        click -> {
                            handlerOrder.add(2);
                            observedIndex.set(click.index());
                            observedOption.set(click.option());
                            observedClick.set(click);
                        }
                )
        );
        reentrantWindow.set(enchantment);

        assertEquals(Window.OpenResult.OPENED, enchantment.open().toCompletableFuture().join());
        assertEquals(MenuKind.ENCHANTMENT, menus.lastKind);
        assertEquals(38, menus.lastInitialSlotCount);
        TrackingEnchantmentMenuHandle handle = assertInstanceOf(
                TrackingEnchantmentMenuHandle.class,
                menus.lastHandle
        );

        assertSame(original, handle.options[0]);
        assertNull(handle.options[1]);
        assertEquals(41, handle.enchantmentSeed);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId + 1, 0)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 3)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 1)
        );
        enchantment.tick();

        assertNull(observedOption.get());
        assertTrue(handlerOrder.isEmpty());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.ButtonClick(menus.lastContainerId, 0)
        );
        enchantment.tick();

        assertEquals(List.of(0, 1, 2), handlerOrder);
        assertEquals(0, observedIndex.get());
        assertSame(original, observedOption.get());
        assertSame(player, observedClick.get().player());
        assertSame(enchantment, observedClick.get().window());
        assertSame(replacement, enchantment.getOption(0));
        assertSame(replacement, handle.options[0]);
        assertTrue(menus.synchronizations.getLast().dirtySlots().isEmpty());
        int synchronizationsBeforeUpdate = menus.synchronizations.size();
        enchantment.setOption(2, third);
        enchantment.setEnchantmentSeed(73);
        enchantment.tick();

        assertSame(third, handle.options[2]);
        assertEquals(73, handle.enchantmentSeed);
        assertEquals(synchronizationsBeforeUpdate + 1, menus.synchronizations.size());
        assertEquals(Window.CloseResult.CLOSED, enchantment.close().toCompletableFuture().join());
    }

    @Test
    void craftingWindowRoutesSlotsValidatesRecipeSelectionsAndCompletesGhostCommands() {
        AtomicReference<GameMode> gameMode = new AtomicReference<>(GameMode.SURVIVAL);
        Set<NamespacedKey> discoveredRecipes = new HashSet<>();
        Player delegate = PlayerStub.addTo(this.server);
        delegate.getInventory().setItem(9, new ItemStack(Material.APPLE));
        Player player = recipePlayer(delegate, gameMode, discoveredRecipes);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AtomicInteger reportedFailures = new AtomicInteger();
        net.momirealms.sparrow.ui.SparrowUI.getInstance().setExceptionHandler(
                (ignoredMessage, ignoredThrowable) -> reportedFailures.incrementAndGet()
        );
        Pane result = Pane.single(new StaticItem(
                ItemProvider.constant(new ItemStack(Material.DIAMOND))
        ));
        Pane craftingGrid = Pane.empty(3, 3);
        craftingGrid.setItem(
                0,
                new StaticItem(ItemProvider.constant(new ItemStack(Material.OAK_PLANKS)))
        );
        ArrayList<String> handlerOrder = new ArrayList<>();
        AtomicReference<RecipeBookSelectClick> lastSelection = new AtomicReference<>();
        CraftingWindowImpl window = new CraftingWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(result),
                        WindowLayout.Region.upper(craftingGrid),
                        WindowLayout.Region.lower(viewerStoragePane(player))
                ),
                settings(),
                List.of(
                        selection -> {
                            handlerOrder.add("first");
                            lastSelection.set(selection);
                        },
                        ignoredSelection -> {
                            throw new IllegalStateException("injected recipe handler failure");
                        },
                        ignoredSelection -> handlerOrder.add("third")
                )
        );
        Key recipeId = Key.key("minecraft:crafting_table");
        Key failingRecipe = Key.key("sparrow:test_failure");

        assertSame(result, window.paneAt(0).pane());
        assertSame(craftingGrid, window.paneAt(1).pane());
        assertSame(craftingGrid, window.paneAt(9).pane());
        assertSame(window.panes().get(2), window.paneAt(10).pane());
        assertEquals(0, window.paneAt(10).slot());
        assertSame(window.panes().get(2), window.paneAt(45).pane());
        assertEquals(35, window.paneAt(45).slot());
        assertThrows(IndexOutOfBoundsException.class, () -> window.paneAt(46));
        assertEquals(
                GhostRecipeResult.WINDOW_CLOSED,
                window.sendGhostRecipe(recipeId).toCompletableFuture().join()
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        assertEquals(MenuKind.CRAFTING, menus.lastKind);
        assertEquals(46, menus.lastInitialSlotCount);
        assertEquals(Material.DIAMOND, menus.lastInitialSlots[0].getType());
        assertEquals(Material.OAK_PLANKS, menus.lastInitialSlots[1].getType());
        assertEquals(Material.APPLE, menus.lastInitialSlots[10].getType());
        TrackingCraftingMenuHandle handle = assertInstanceOf(
                TrackingCraftingMenuHandle.class,
                menus.lastHandle
        );
        handle.recipeKeys.put(7, recipeId);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId + 1, 7, true)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId, 999, true)
        );
        gameMode.set(GameMode.SPECTATOR);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId, 7, true)
        );
        window.tick();

        assertTrue(handlerOrder.isEmpty());
        gameMode.set(GameMode.SURVIVAL);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId, 7, false)
        );
        window.tick();

        assertTrue(handlerOrder.isEmpty());
        discoveredRecipes.add(NamespacedKey.fromString(recipeId.asString()));
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId, 7, true)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId, 7, false)
        );
        window.tick();

        assertEquals(List.of("first", "third", "first", "third"), handlerOrder);
        assertEquals(recipeId, lastSelection.get().recipeId());
        assertFalse(lastSelection.get().makeAll());
        assertEquals(2, reportedFailures.get());
        assertEquals(
                GhostRecipeResult.RECIPE_NOT_FOUND,
                window.sendGhostRecipe(Key.key("minecraft:missing")).toCompletableFuture().join()
        );

        assertEquals(
                GhostRecipeResult.SENT,
                window.sendGhostRecipe(recipeId).toCompletableFuture().join()
        );

        assertEquals(List.of(recipeId), handle.sentGhostRecipes);
        assertThrows(
                CompletionException.class,
                () -> window.sendGhostRecipe(failingRecipe).toCompletableFuture().join()
        );

        assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        assertEquals(
                GhostRecipeResult.WINDOW_CLOSED,
                window.sendGhostRecipe(recipeId).toCompletableFuture().join()
        );
        manager.shutdown();

        assertEquals(
                GhostRecipeResult.VIEWER_UNAVAILABLE,
                window.sendGhostRecipe(recipeId).toCompletableFuture().join()
        );
    }

    @Test
    void furnaceFamilyDrivesTypedProgressAndSharedRecipeBookBehavior() {
        AtomicReference<GameMode> gameMode = new AtomicReference<>(GameMode.SURVIVAL);
        Key recipeId = Key.key("minecraft:cooked_beef");
        Set<NamespacedKey> discoveredRecipes = new HashSet<>();
        discoveredRecipes.add(NamespacedKey.fromString(recipeId.asString()));
        Player player = recipePlayer(this.server.addPlayer(), gameMode, discoveredRecipes);
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        Pane input = Pane.empty(1, 1);
        Pane fuel = Pane.empty(1, 1);
        Pane result = Pane.empty(1, 1);
        FurnaceWindow.Builder originalBuilder = FurnaceWindow.builder()
                .setInputPane(input)
                .setFuelPane(fuel)
                .setResultPane(result)
                .setCookProgress(0.25)
                .setFuelProgress(0.5);
        FurnaceWindow builtFurnace = originalBuilder.build(player);
        FurnaceWindow clonedFurnace = originalBuilder.clone().setCookProgress(0.75).build(player);
        SmokerWindow builtSmoker = SmokerWindow.builder().build(player);
        BlastFurnaceWindow builtBlastFurnace = BlastFurnaceWindow.builder().build(player);
        AtomicInteger selectionCount = new AtomicInteger();
        AtomicReference<RecipeBookSelectClick> lastSelection = new AtomicReference<>();
        List<Consumer<RecipeBookSelectClick>> handlers = List.of(selection -> {
            selectionCount.incrementAndGet();
            lastSelection.set(selection);
        });
        AbstractFurnaceWindow[] windows = {
                new FurnaceWindowImpl(
                        manager,
                        player,
                        furnaceLayout(),
                        settings(),
                        handlers,
                        0.25,
                        0.5
                ),
                new SmokerWindowImpl(
                        manager,
                        player,
                        furnaceLayout(),
                        settings(),
                        handlers,
                        0.25,
                        0.5
                ),
                new BlastFurnaceWindowImpl(
                        manager,
                        player,
                        furnaceLayout(),
                        settings(),
                        handlers,
                        0.25,
                        0.5
                )
        };
        MenuKind[] expectedKinds = {
                MenuKind.FURNACE,
                MenuKind.SMOKER,
                MenuKind.BLAST_FURNACE
        };

        assertSame(input, builtFurnace.paneAt(0).pane());
        assertSame(fuel, builtFurnace.paneAt(1).pane());
        assertSame(result, builtFurnace.paneAt(2).pane());
        assertSame(builtFurnace.panes().get(3), builtFurnace.paneAt(3).pane());
        assertEquals(0, builtFurnace.paneAt(3).slot());
        assertSame(builtFurnace.panes().get(3), builtFurnace.paneAt(38).pane());
        assertEquals(35, builtFurnace.paneAt(38).slot());
        assertThrows(IndexOutOfBoundsException.class, () -> builtFurnace.paneAt(39));
        assertEquals(0.25, builtFurnace.getCookProgress());
        assertEquals(0.5, builtFurnace.getFuelProgress());
        assertEquals(0.75, clonedFurnace.getCookProgress());
        assertEquals(0.0, builtSmoker.getCookProgress());
        assertEquals(0.0, builtBlastFurnace.getFuelProgress());
        for (int index = 0; index < windows.length; index++) {
            AbstractFurnaceWindow window = windows[index];

            assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
            assertEquals(expectedKinds[index], menus.lastKind);
            assertEquals(39, menus.lastInitialSlotCount);
            TrackingFurnaceMenuHandle handle = assertInstanceOf(
                    TrackingFurnaceMenuHandle.class,
                    menus.lastHandle
            );

            assertEquals(0.25, handle.cookProgress);
            assertEquals(0.5, handle.fuelProgress);
            handle.recipeKeys.put(7, recipeId);
            menus.offerInput(
                    menus.lastGeneration,
                    new MenuInput.WindowSpecific.RecipePlace(menus.lastContainerId, 7, true)
            );
            window.setCookProgress(0.75);
            window.setFuelProgress(1.0);

            assertThrows(IllegalArgumentException.class, () -> window.setCookProgress(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> window.setFuelProgress(-0.01));
            window.tick();

            assertEquals(index + 1, selectionCount.get());
            assertSame(window, lastSelection.get().window());
            assertTrue(lastSelection.get().makeAll());
            assertEquals(0.75, window.getCookProgress());
            assertEquals(1.0, window.getFuelProgress());
            assertEquals(0.75, handle.cookProgress);
            assertEquals(1.0, handle.fuelProgress);
            assertTrue(menus.synchronizations.getLast().dirtySlots().isEmpty());
            assertEquals(
                    GhostRecipeResult.SENT,
                    window.sendGhostRecipe(recipeId).toCompletableFuture().join()
            );

            assertEquals(Window.CloseResult.CLOSED, window.close().toCompletableFuture().join());
        }
    }

    @Test
    void merchantWindowDrivesMetadataSelectionsAndInSessionSelectionReset() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        ArrayList<Integer> clickedSlots = new ArrayList<>();
        ArrayList<MerchantTradeSelectClick> selections = new ArrayList<>();
        Item first = new StaticItem(ItemProvider.EMPTY, (ignoredItem, click) -> clickedSlots.add(click.windowSlot()));
        Item second = new StaticItem(ItemProvider.EMPTY, (ignoredItem, click) -> clickedSlots.add(click.windowSlot()));
        Item result = new StaticItem(ItemProvider.EMPTY, (ignoredItem, click) -> clickedSlots.add(click.windowSlot()));
        MerchantWindow.Trade firstTrade = MerchantWindow.Trade.builder()
                .setFirstInput(first)
                .setSecondInput(second)
                .setResult(result)
                .build();
        MerchantWindow.Trade unavailableTrade = MerchantWindow.Trade.builder()
                .setFirstInput(first)
                .setSecondInput(second)
                .setResult(result)
                .setAvailable(false)
                .build();
        MerchantWindowImpl window = new MerchantWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(3, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                2,
                0.5,
                true,
                List.of(firstTrade, unavailableTrade),
                List.of(selections::add)
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        TrackingMerchantMenuHandle handle = assertInstanceOf(
                TrackingMerchantMenuHandle.class,
                menus.lastHandle
        );

        assertEquals(MenuKind.MERCHANT, menus.lastKind);
        assertEquals(39, menus.lastInitialSlotCount);
        assertEquals(2, handle.level);
        assertEquals(0.5, handle.progress);
        assertTrue(handle.restockMessageEnabled);
        assertEquals(List.of(firstTrade, unavailableTrade), handle.trades);
        window.tick();

        assertEquals(1, handle.lastPeriodicTick);
        assertEquals(1, handle.offerSynchronizationAttempts);
        assertEquals(1, handle.offerSynchronizations);
        handle.protocolPackets.clear();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        window.tick();

        assertTrue(clickedSlots.isEmpty(), "Trade 的三个 Item 是纯展示的, 选择交易不给它们分派点击");
        assertEquals(1, handle.selectionReconciliations);
        assertTrue(menus.synchronizations.getLast().forceFull());
        assertEquals(List.of("empty-offers", "contents", "current-offers"), handle.protocolPackets);
        assertEquals(1, handle.offerSynchronizationAttempts);
        assertEquals(1, handle.offerSynchronizations);
        assertEquals(1, selections.size());
        assertEquals(-1, selections.getFirst().previousIndex());
        assertNull(selections.getFirst().previousTrade());
        assertEquals(0, selections.getFirst().selectedIndex());
        assertSame(firstTrade, selections.getFirst().selectedTrade());
        assertSame(window, selections.getFirst().window());
        assertSame(player, selections.getFirst().player());
        handle.protocolPackets.clear();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.Common.Click(
                        menus.lastContainerId,
                        handle.stateId(),
                        -999,
                        ClickType.WINDOW_BORDER_LEFT,
                        -1
                )
        );
        window.tick();

        assertEquals(List.of("empty-offers", "rootChanges", "current-offers"), handle.protocolPackets);
        assertEquals(1, handle.offerSynchronizationAttempts);
        assertEquals(1, handle.offerSynchronizations);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 1)
        );
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 99)
        );
        window.tick();

        assertTrue(clickedSlots.isEmpty(), "重复选择与越界索引同样不分派点击");
        assertEquals(4, handle.selectionReconciliations);
        assertEquals(1, handle.offerSynchronizationAttempts);
        assertEquals(1, handle.offerSynchronizations);
        assertEquals(2, selections.size());
        assertEquals(0, selections.getLast().previousIndex());
        assertSame(firstTrade, selections.getLast().previousTrade());
        assertSame(unavailableTrade, selections.getLast().selectedTrade());
        MerchantWindow.Trade replacementFirst = MerchantWindow.Trade.builder().build();
        MerchantWindow.Trade replacementSecond = MerchantWindow.Trade.builder().build();
        window.setLevel(5);
        window.setProgress(1.0);
        window.setRestockMessageEnabled(false);
        window.setTrades(List.of(replacementFirst, replacementSecond));
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        window.tick();

        assertEquals(5, window.getLevel());
        assertEquals(1.0, window.getProgress());
        assertFalse(window.isRestockMessageEnabled());
        assertEquals(5, handle.level);
        assertEquals(1.0, handle.progress);
        assertFalse(handle.restockMessageEnabled);
        assertEquals(2, handle.offerSynchronizations);
        assertEquals(3, selections.size());
        assertSame(replacementSecond, selections.getLast().previousTrade());
        assertSame(replacementFirst, selections.getLast().selectedTrade());
        int containerId = menus.lastContainerId;
        long generation = menus.lastGeneration;
        int created = menus.created.get();
        int titleUpdates = menus.titleUpdates.size();
        window.setTrades(List.of(replacementFirst));
        window.tick();

        assertEquals(containerId, menus.lastContainerId);
        assertEquals(generation, menus.lastGeneration);
        assertEquals(created, menus.created.get());
        assertSame(handle, menus.lastHandle);
        assertTrue(window.isOpen());
        assertEquals(titleUpdates + 1, menus.titleUpdates.size());
        assertEquals(List.of(replacementFirst), handle.trades);
        assertEquals(3, handle.offerSynchronizations);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        window.tick();

        assertEquals(4, selections.size());
        assertEquals(-1, selections.getLast().previousIndex());
        assertNull(selections.getLast().previousTrade());
        assertSame(replacementFirst, selections.getLast().selectedTrade());
        assertEquals(3, handle.offerSynchronizations);
    }

    @Test
    void merchantOfferDirtyGateRetriesFailureAndRetainsInvalidationDuringSend() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AtomicInteger reportedFailures = new AtomicInteger();
        net.momirealms.sparrow.ui.SparrowUI.getInstance().setExceptionHandler(
                (ignoredMessage, ignoredThrowable) -> reportedFailures.incrementAndGet()
        );
        MerchantWindowImpl window = new MerchantWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(3, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                0,
                -1.0,
                false,
                List.of(),
                List.of()
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        TrackingMerchantMenuHandle handle = assertInstanceOf(
                TrackingMerchantMenuHandle.class,
                menus.lastHandle
        );
        window.tick();
        int attempts = handle.offerSynchronizationAttempts;
        int successes = handle.offerSynchronizations;
        handle.failNextOfferSynchronization = true;
        window.setLevel(1);
        window.tick();

        assertEquals(attempts + 1, handle.offerSynchronizationAttempts);
        assertEquals(successes, handle.offerSynchronizations);
        assertEquals(1, reportedFailures.get());
        window.tick();

        assertEquals(attempts + 2, handle.offerSynchronizationAttempts);
        assertEquals(successes + 1, handle.offerSynchronizations);
        handle.invalidateDuringNextOfferSynchronization = true;
        window.setLevel(2);
        window.tick();

        assertEquals(attempts + 3, handle.offerSynchronizationAttempts);
        assertEquals(successes + 2, handle.offerSynchronizations);
        window.tick();

        assertEquals(attempts + 4, handle.offerSynchronizationAttempts);
        assertEquals(successes + 3, handle.offerSynchronizations);
    }

    @Test
    void merchantSelectionIsolatesHandlerFailuresAndReentrantShrinkResetsSelection() {
        Player player = connectedPlayer(PlayerStub.addTo(this.server));
        TrackingMenuFactory menus = new TrackingMenuFactory();
        WindowManager manager = manager(new OwnedDispatcher(this.plugin), menus);
        AtomicInteger reportedFailures = new AtomicInteger();
        net.momirealms.sparrow.ui.SparrowUI.getInstance().setExceptionHandler(
                (ignoredMessage, ignoredThrowable) -> reportedFailures.incrementAndGet()
        );
        ArrayList<MerchantTradeSelectClick> selections = new ArrayList<>();
        MerchantWindow.Trade firstTrade = MerchantWindow.Trade.builder().build();
        MerchantWindow.Trade secondTrade = MerchantWindow.Trade.builder().build();
        MerchantWindowImpl window = new MerchantWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(3, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                0,
                -1.0,
                false,
                List.of(firstTrade, secondTrade),
                List.of(
                        ignoredSelection -> {
                            throw new IllegalStateException("injected Merchant handler failure");
                        },
                        selections::add
                )
        );

        assertEquals(Window.OpenResult.OPENED, window.open().toCompletableFuture().join());
        window.tick();
        menus.synchronizations.clear();
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        window.tick();

        assertEquals(1, reportedFailures.get());
        assertEquals(1, selections.size());
        assertEquals(-1, selections.getFirst().previousIndex());
        assertEquals(0, selections.getFirst().selectedIndex());
        assertTrue(menus.synchronizations.getLast().forceFull());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 1)
        );
        window.tick();

        assertEquals(2, reportedFailures.get());
        assertEquals(2, selections.size());
        assertEquals(0, selections.getLast().previousIndex());
        assertSame(firstTrade, selections.getLast().previousTrade());
        assertSame(secondTrade, selections.getLast().selectedTrade());
        MerchantWindow.Trade replacement = MerchantWindow.Trade.builder().build();
        AtomicBoolean shrinkOnSelect = new AtomicBoolean();
        window.addTradeSelectHandler(ignoredSelect -> {
            if (shrinkOnSelect.compareAndSet(true, false)) {
                window.setTrades(List.of(replacement));
            }
        });
        window.setTrades(List.of(replacement, firstTrade));
        shrinkOnSelect.set(true);
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        window.tick();

        assertEquals(3, selections.size());
        assertSame(replacement, selections.getLast().selectedTrade());
        assertEquals(List.of(replacement), window.getTrades());
        menus.offerInput(
                menus.lastGeneration,
                new MenuInput.WindowSpecific.TradeSelect(menus.lastContainerId, 0)
        );
        window.tick();

        assertEquals(4, selections.size());
        assertEquals(-1, selections.getLast().previousIndex());
        assertNull(selections.getLast().previousTrade());
        assertSame(replacement, selections.getLast().selectedTrade());
    }

    private static void dragOver(TrackingMenuFactory menus, ClickType clickType, int... windowSlots) {
        int stateId = menus.currentHandle.stateId();
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, clickType, MenuInput.Common.DragPhase.START));
        for (int index = 0; index < windowSlots.length; index++) {
            menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                    menus.lastContainerId, stateId, windowSlots[index], clickType, MenuInput.Common.DragPhase.ADD));
        }
        menus.offerInput(menus.lastGeneration, new MenuInput.Common.DragStep(
                menus.lastContainerId, stateId, -999, clickType, MenuInput.Common.DragPhase.END));
    }

    private static Item draggableItem(Material material, List<ItemDrag> drags) {
        return Item.builder()
                .setItemProviderConstant(new ItemStack(material))
                .addDragHandler(drag -> drags.add(drag))
                .build();
    }

    private static WindowManager manager(OwnedDispatcher dispatcher, TrackingMenuFactory menus) {
        WindowManager manager = new WindowManager(dispatcher.plugin, menus, new FoliaExecutor(dispatcher.plugin));
        SparrowUiTestRuntime.install(manager);
        return manager;
    }

    private static AbstractWindow.Settings settings() {
        return new AbstractWindow.Settings(
                Component::empty,
                true,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                WindowSession.Kind.STACK,
                List.of(),
                0,
                List.of(),
                VisualLayer.NONE,
                VisualLayer.NONE
        );
    }

    private static WindowLayout furnaceLayout() {
        return WindowLayout.of(
                WindowLayout.Region.upper(Pane.empty(1, 1)),
                WindowLayout.Region.upper(Pane.empty(1, 1)),
                WindowLayout.Region.upper(Pane.empty(1, 1)),
                WindowLayout.Region.lower(Pane.empty(9, 4))
        );
    }

    private static Pane viewerStoragePane(Player player) {
        ReferencingInventory inventory = ReferencingInventory.fromPlayerStorageContents(player.getInventory());
        Pane pane = Pane.empty(9, 4);
        for (int slot = 0; slot < inventory.size(); slot++) {
            pane.setElement(slot, Element.inventory(inventory, slot));
        }
        return pane;
    }

    private static List<ClickSemantics.LinkedInventory> linkedInventoriesOf(AbstractWindow<?> window) {
        try {
            java.lang.reflect.Field field = AbstractWindow.class.getDeclaredField("semanticsContext");
            field.setAccessible(true);
            return ((ClickSemantics.Context) field.get(window)).linkedInventories();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object refreshTargetsOf(AbstractWindow<?> window) {
        try {
            java.lang.reflect.Field field = AbstractWindow.class.getDeclaredField("refreshInventories");
            field.setAccessible(true);
            return field.get(window);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Optional<ClickSemantics.LinkedInventory> linkedEntry(AbstractWindow<?> window, SparrowInventory inventory) {
        return linkedInventoriesOf(window).stream()
                .filter(candidate -> candidate.inventory() == inventory)
                .findFirst();
    }

    private static AbstractWindow<?> window(WindowManager manager, Player player, Pane pane, String title) {
        return new NormalWindowImpl(
                manager,
                player,
                WindowLayout.split(pane, Pane.empty(9, 4)),
                new AbstractWindow.Settings(
                        () -> Component.text(title),
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        null,
                        WindowSession.Kind.STACK,
                        List.of(),
                        0,
                        List.of(),
                        VisualLayer.NONE,
                        VisualLayer.NONE
                )
        );
    }

    private static Player connectedPlayer(Player delegate) {
        AtomicReference<ItemStack> cursor = new AtomicReference<>(ItemStack.empty());
        return (Player) Proxy.newProxyInstance(
                AbstractWindowLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (ignoredProxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "isConnected", "isValid" -> {
                            return true;
                        }
                        case "isSleeping" -> {
                            return false;
                        }
                        case "getItemOnCursor" -> {
                            return cursor.get().clone();
                        }
                        case "setItemOnCursor" -> {
                            ItemStack item = (ItemStack) arguments[0];
                            cursor.set(item == null ? ItemStack.empty() : item.clone());
                            return null;
                        }
                        case "getScheduler" -> {
                            if (activeDispatcher == null) {
                                throw new IllegalStateException("No entity scheduler installed");
                            }
                            return activeDispatcher;
                        }
                        default -> {
                            try {
                                return method.invoke(delegate, arguments);
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        }
                    }
                }
        );
    }

    private static Player dropTrackingPlayer(Player delegate, List<ItemStack> drops) {
        Player connected = connectedPlayer(delegate);
        return (Player) Proxy.newProxyInstance(
                AbstractWindowLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("dropItem") && arguments[0] instanceof ItemStack item) {
                        drops.add(item.clone());
                        return null;
                    }
                    try {
                        return method.invoke(connected, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    private static Player recipePlayer(
            Player delegate,
            AtomicReference<GameMode> gameMode,
            Set<NamespacedKey> discoveredRecipes
    ) {
        Player connected = connectedPlayer(delegate);
        return (Player) Proxy.newProxyInstance(
                AbstractWindowLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("getGameMode")) {
                        return gameMode.get();
                    }
                    if (method.getName().equals("hasDiscoveredRecipe")) {
                        return discoveredRecipes.contains(arguments[0]);
                    }
                    try {
                        return method.invoke(connected, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    private static InventoryView supportedView(InventoryView delegate, int topSlots) {
        return (InventoryView) Proxy.newProxyInstance(
                AbstractWindowLifecycleTest.class.getClassLoader(),
                new Class<?>[]{InventoryView.class},
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("getSlotType")) {
                        int rawSlot = (int) arguments[0];
                        if (rawSlot == InventoryView.OUTSIDE) {
                            return InventoryType.SlotType.OUTSIDE;
                        }
                        return rawSlot >= topSlots + 27
                                ? InventoryType.SlotType.QUICKBAR
                                : InventoryType.SlotType.CONTAINER;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    private static final class TrackingItem implements Item {
        private final AtomicInteger liveAttachments = new AtomicInteger();
        private final AtomicInteger totalAttachments = new AtomicInteger();
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
        @Override
        public ItemAttachment attach(@NonNull Window window, @NonNull Observer<? super Item> observer) {
            this.liveAttachments.incrementAndGet();
            this.totalAttachments.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean();
            return new ItemAttachment() {
                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        TrackingItem.this.liveAttachments.decrementAndGet();
                    }
                }
            };
        }
    }

    private static final class TrackingMenuFactory implements MenuFactory {
        private static final int INCOMING_CAPACITY = 256;
        private final AtomicInteger nextContainerId = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger live = new AtomicInteger();
        private final AtomicBoolean failNextOpen = new AtomicBoolean();
        private final AtomicBoolean failNextTitleUpdate = new AtomicBoolean();
        private final List<WindowCloseReason> closeReasons = new ArrayList<>();
        private final List<Component> titleUpdates = new ArrayList<>();
        private final List<Component> openedTitles = new ArrayList<>();
        private final List<Integer> pingIds = new ArrayList<>();
        private final List<TrackingSynchronization> synchronizations = new ArrayList<>();
        private final List<BitSet> eventViewRenderedSlots = new ArrayList<>();
        private ItemStack lastInitialCursor;
        private ItemStack lastInitialActualCursor;
        private ItemStack lastSynchronizedCursor;
        private ItemStack lastSynchronizedVisualCursor;
        private ItemStack lastInitialFirstSlot;
        private ItemStack[] lastInitialSlots;
        private MenuKind lastKind;
        private MenuHandle lastHandle;
        private TrackingMenuHandle currentHandle;
        private TrackingMenuHandle activeHandle;
        private long lastGeneration;
        private int lastInitialSlotCount;
        private int lastContainerId;
        @Override
        public @NonNull MenuHandle normal(@NonNull Player viewer, int rows, long generation) {
            this.lastKind = MenuKind.NORMAL;
            this.lastGeneration = generation;
            return this.create(viewer, rows * 9, generation);
        }
        @Override
        public @NonNull MenuHandle hopper(@NonNull Player viewer, long generation) {
            this.lastKind = MenuKind.HOPPER;
            this.lastGeneration = generation;
            return this.create(viewer, 5, generation);
        }
        @Override
        public @NonNull AnvilMenuHandle anvil(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    3
            );
            this.lastKind = MenuKind.ANVIL;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingMenuHandle previousHandle = this.activeHandle;
            TrackingAnvilMenuHandle handle = new TrackingAnvilMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    previousHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public MenuHandle dispenser(@NonNull Player viewer, long generation) {
            this.lastKind = MenuKind.DISPENSER;
            this.lastGeneration = generation;
            return this.create(viewer, 9, generation);
        }
        @Override
        @NonNull
        public MenuHandle dropper(@NonNull Player viewer, long generation) {
            this.lastKind = MenuKind.DROPPER;
            this.lastGeneration = generation;
            return this.create(viewer, 9, generation);
        }
        @Override
        @NonNull
        public MenuHandle grindstone(@NonNull Player viewer, long generation) {
            this.lastKind = MenuKind.GRINDSTONE;
            this.lastGeneration = generation;
            return this.create(viewer, 3, generation);
        }
        @Override
        @NonNull
        public MenuHandle smithing(@NonNull Player viewer, long generation) {
            this.lastKind = MenuKind.SMITHING;
            this.lastGeneration = generation;
            return this.create(viewer, 4, generation);
        }
        @Override
        @NonNull
        public BrewingMenuHandle brewing(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    5
            );
            this.lastKind = MenuKind.BREWING;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingBrewingMenuHandle handle = new TrackingBrewingMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public CartographyMenuHandle cartography(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    3
            );
            this.lastKind = MenuKind.CARTOGRAPHY;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingCartographyMenuHandle handle = new TrackingCartographyMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public CrafterMenuHandle crafter(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 18)),
                    10
            );
            this.lastKind = MenuKind.CRAFTER;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingCrafterMenuHandle handle = new TrackingCrafterMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public RecipeBookMenuHandle crafting(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 18)),
                    10
            );
            this.lastKind = MenuKind.CRAFTING;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingCraftingMenuHandle handle = new TrackingCraftingMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public FurnaceMenuHandle furnace(@NonNull Player viewer, long generation) {
            return this.createFurnace(viewer, generation, MenuKind.FURNACE);
        }
        @Override
        @NonNull
        public FurnaceMenuHandle smoker(@NonNull Player viewer, long generation) {
            return this.createFurnace(viewer, generation, MenuKind.SMOKER);
        }
        @Override
        @NonNull
        public FurnaceMenuHandle blastFurnace(@NonNull Player viewer, long generation) {
            return this.createFurnace(viewer, generation, MenuKind.BLAST_FURNACE);
        }
        @Override
        @NonNull
        public EnchantmentMenuHandle enchantment(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    2
            );
            this.lastKind = MenuKind.ENCHANTMENT;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingEnchantmentMenuHandle handle = new TrackingEnchantmentMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public StonecutterMenuHandle stonecutter(@NonNull Player viewer, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    2
            );
            this.lastKind = MenuKind.STONECUTTER;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingStonecutterMenuHandle handle = new TrackingStonecutterMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        @Override
        @NonNull
        public MerchantMenuHandle merchant(
                @NonNull Player viewer,
                long generation,
                @NonNull MerchantWindow window,
                @NonNull BiConsumer<? super String, ? super Throwable> reporter
        ) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    3
            );
            this.lastKind = MenuKind.MERCHANT;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingMerchantMenuHandle handle = new TrackingMerchantMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        private TrackingMenuHandle create(Player viewer, int topSlots, long generation) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            var inventory = switch (topSlots) {
                case 3 -> Bukkit.createInventory(null, InventoryType.GRINDSTONE);
                case 4 -> Bukkit.createInventory(null, InventoryType.SMITHING);
                case 5 -> Bukkit.createInventory(null, InventoryType.HOPPER);
                default -> Bukkit.createInventory(null, topSlots);
            };
            InventoryView view = supportedView(
                    viewer.openInventory(inventory),
                    topSlots
            );
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingMenuHandle previousHandle = this.activeHandle;
            TrackingMenuHandle handle = new TrackingMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    previousHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        private TrackingFurnaceMenuHandle createFurnace(Player viewer, long generation, MenuKind kind) {
            this.created.incrementAndGet();
            this.live.incrementAndGet();
            InventoryView view = supportedView(
                    viewer.openInventory(Bukkit.createInventory(null, 9)),
                    3
            );
            this.lastKind = kind;
            this.lastGeneration = generation;
            this.lastContainerId = this.nextContainerId.incrementAndGet();
            TrackingFurnaceMenuHandle handle = new TrackingFurnaceMenuHandle(
                    this,
                    viewer,
                    this.lastContainerId,
                    view,
                    generation,
                    this.activeHandle
            );
            this.lastHandle = handle;
            this.currentHandle = handle;
            return handle;
        }
        private void offerInput(long generation, MenuInput input) {
            this.activeHandle.offerInput(generation, input);
        }
        private void completeExternalClose() {
            TrackingMenuHandle active = this.activeHandle;
            if (active == null) {
                return;
            }
            active.viewer.setItemOnCursor(active.actualCarried);
            active.actualCarried = ItemStack.empty();
            this.activeHandle = null;
        }
    }

    private static class TrackingMenuHandle implements MenuHandle {
        private final TrackingMenuFactory owner;
        private final Player viewer;
        private final int containerId;
        private final InventoryView view;
        private final long generation;
        private final TrackingMenuHandle previousHandle;
        private final ArrayDeque<QueuedInput> incoming = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ItemStack actualCarried = ItemStack.empty();
        private int stateId;
        private boolean prepared;
        private boolean committed;
        private boolean inputClosed;
        private boolean inputOverflowed;
        private TrackingMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            this.owner = owner;
            this.viewer = viewer;
            this.containerId = containerId;
            this.view = view;
            this.generation = generation;
            this.previousHandle = previousHandle;
        }
        @Override
        public int containerId() {
            return this.containerId;
        }
        @Override
        public @NonNull InventoryView view() {
            return this.view;
        }
        @Override
        public void resetBukkitEventView(ItemStack @NonNull [] slots, @NonNull BitSet renderedSlots, @NonNull ItemStack cursor) {
            this.owner.eventViewRenderedSlots.add((BitSet) renderedSlots.clone());
            this.replaceBukkitEventView(slots, cursor);
        }
        private void replaceBukkitEventView(ItemStack[] slots, ItemStack cursor) {
            for (int rawSlot = 0; rawSlot < slots.length; rawSlot++) {
                this.view.setItem(rawSlot, slots[rawSlot]);
            }
            this.view.setCursor(cursor);
        }
        @Override
        public @Nullable ItemStack takeBukkitEventCursor() {
            return null;
        }
        @Override
        public void drainBukkitEventSlots(@NonNull BitSet destination) {
            destination.clear();
        }
        @Override
        public int stateId() {
            return this.stateId;
        }
        @Override
        public boolean accepts(MenuInput.Common.@NonNull Interaction interaction) {
            return interaction.containerId() == this.containerId;
        }
        @Override
        public synchronized boolean hasInputOverflowed() {
            return this.inputOverflowed;
        }
        @Override
        public synchronized @NonNull List<MenuInput> drainInputs(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            int count = Math.min(limit, this.incoming.size());
            ArrayList<MenuInput> inputs = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                QueuedInput queued = this.incoming.removeFirst();
                if (queued.generation() == this.generation) {
                    inputs.add(queued.input());
                }
            }
            return List.copyOf(inputs);
        }
        @Override
        public void prepareOpen(boolean replacingWindow) {
            if (replacingWindow && this.previousHandle != null) {
                this.actualCarried = this.previousHandle.actualCarried;
                this.previousHandle.actualCarried = ItemStack.empty();
            } else {
                this.actualCarried = this.viewer.getItemOnCursor();
            }
            this.prepared = true;
        }
        @Override
        public @NonNull ItemStack cursor() {
            return this.actualCarried.clone();
        }
        @Override
        public void cursor(@NonNull ItemStack cursor) {
            this.actualCarried = cursor.clone();
        }
        @Override
        public void open(
                @NonNull Component title,
                ItemStack @NonNull [] slots,
                @NonNull CursorSnapshot cursor
        ) {
            if (this.owner.failNextOpen.compareAndSet(true, false)) {
                throw new IllegalStateException("injected menu open failure");
            }
            this.owner.lastInitialActualCursor = cursor.actual().clone();
            this.owner.lastInitialCursor = cursor.visual().clone();
            this.owner.lastInitialFirstSlot = slots[0];
            this.owner.lastInitialSlots = slots.clone();
            this.owner.lastInitialSlotCount = slots.length;
            this.owner.openedTitles.add(title);
            this.replaceBukkitEventView(slots, cursor.actual());
            this.viewer.setItemOnCursor(ItemStack.empty());
            this.owner.activeHandle = this;
            this.stateId = 1;
            this.committed = true;
            this.prepared = false;
        }
        @Override
        public void synchronize(
                ItemStack @NonNull [] slots,
                @NonNull BitSet dirtySlots,
                @NonNull CursorSnapshot cursor,
                boolean cursorDirty,
                boolean forceFull
        ) {
            this.owner.lastSynchronizedCursor = cursor.actual().clone();
            this.owner.lastSynchronizedVisualCursor = cursor.visual().clone();
            this.owner.synchronizations.add(new TrackingSynchronization(
                    (BitSet) dirtySlots.clone(),
                    cursorDirty,
                    forceFull
            ));
            this.replaceBukkitEventView(slots, cursor.actual());
            if (forceFull) {
                this.stateId = (this.stateId + 1) & 32767;
            } else {
                this.stateId = (this.stateId + dirtySlots.cardinality()) & 32767;
            }
        }
        @Override
        public void reopenWithTitle(
                @NonNull Component title,
                ItemStack @NonNull [] slots,
                @NonNull CursorSnapshot cursor
        ) {
            if (this.owner.failNextTitleUpdate.compareAndSet(true, false)) {
                throw new IllegalStateException("injected title update failure");
            }
            this.owner.titleUpdates.add(title);
            this.replaceBukkitEventView(slots, cursor.actual());
            this.stateId = (this.stateId + 1) & 32767;
        }
        @Override
        public void sendPing(int id) {
            this.owner.pingIds.add(id);
        }
        @Override
        public void close(@NonNull WindowCloseReason reason) {
            this.owner.closeReasons.add(reason);
            if (!this.committed && this.prepared) {
                if (this.previousHandle != null) {
                    this.previousHandle.actualCarried = this.actualCarried;
                } else {
                    this.viewer.setItemOnCursor(this.actualCarried);
                }
                this.actualCarried = ItemStack.empty();
                this.prepared = false;
            } else if (this.owner.activeHandle == this) {
                this.viewer.setItemOnCursor(this.actualCarried);
                this.actualCarried = ItemStack.empty();
                this.owner.activeHandle = null;
            }
            this.release();
        }
        @Override
        public void retire() {
            this.release();
        }
        private void release() {
            if (this.closed.compareAndSet(false, true)) {
                this.closeInputs();
                this.owner.live.decrementAndGet();
            }
        }
        private synchronized void offerInput(long generation, MenuInput input) {
            if (this.inputClosed) {
                return;
            }
            if (this.incoming.size() == TrackingMenuFactory.INCOMING_CAPACITY) {
                this.inputOverflowed = true;
                return;
            }
            this.incoming.addLast(new QueuedInput(generation, input));
        }
        private synchronized void closeInputs() {
            this.inputClosed = true;
            this.incoming.clear();
        }
        private record QueuedInput(long generation, MenuInput input) {
        }
    }

    private static final class TrackingAnvilMenuHandle extends TrackingMenuHandle implements AnvilMenuHandle {
        private String lastRenameText = "";
        private int enchantmentCost;
        private boolean textFieldAlwaysEnabled;
        private boolean resultAlwaysValid;
        private TrackingAnvilMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void handleRename(String text) {
            this.lastRenameText = text;
        }
        @Override
        public void setEnchantmentCost(int enchantmentCost) {
            this.enchantmentCost = enchantmentCost;
        }
        @Override
        public void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled) {
            this.textFieldAlwaysEnabled = textFieldAlwaysEnabled;
        }
        @Override
        public void setResultAlwaysValid(boolean resultAlwaysValid) {
            this.resultAlwaysValid = resultAlwaysValid;
        }
    }

    private static final class TrackingBrewingMenuHandle extends TrackingMenuHandle
            implements BrewingMenuHandle {
        private double brewProgress;
        private double fuelProgress;
        private TrackingBrewingMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void setBrewProgress(double progress) {
            this.brewProgress = progress;
        }
        @Override
        public void setFuelProgress(double progress) {
            this.fuelProgress = progress;
        }
    }

    private static final class TrackingCartographyMenuHandle extends TrackingMenuHandle
            implements CartographyMenuHandle {
        private CartographyWindow.MapPatch lastPatch;
        private Set<CartographyWindow.MapIcon> icons = Set.of();
        private CartographyWindow.View view = CartographyWindow.View.NORMAL;
        private int resetCount;
        private TrackingCartographyMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void applyPatch(CartographyWindow.@NonNull MapPatch patch) {
            this.lastPatch = patch;
        }
        @Override
        public void setIcons(@NonNull Set<CartographyWindow.MapIcon> icons) {
            this.icons = Set.copyOf(new LinkedHashSet<>(icons));
        }
        @Override
        public void resetMap() {
            this.resetCount++;
            this.icons = Set.of();
        }
        @Override
        public void setView(CartographyWindow.@NonNull View view) {
            this.view = view;
        }
    }

    private static final class TrackingCrafterMenuHandle extends TrackingMenuHandle
            implements CrafterMenuHandle {
        private final boolean[] disabledSlots = new boolean[9];
        private TrackingCrafterMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void setSlotDisabled(int slot, boolean disabled) {
            this.disabledSlots[slot] = disabled;
        }
    }

    private static class TrackingRecipeBookMenuHandle extends TrackingMenuHandle
            implements RecipeBookMenuHandle {
        final Map<Integer, Key> recipeKeys = new HashMap<>();
        final List<Key> sentGhostRecipes = new ArrayList<>();
        private TrackingRecipeBookMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public Key recipeKey(int displayId) {
            return this.recipeKeys.get(displayId);
        }
        @Override
        public boolean sendGhostRecipe(@NonNull Key recipeId) {
            if (recipeId.equals(Key.key("sparrow:test_failure"))) {
                throw new IllegalStateException("injected ghost failure");
            }
            if (!this.recipeKeys.containsValue(recipeId)) {
                return false;
            }
            this.sentGhostRecipes.add(recipeId);
            return true;
        }
    }

    private static final class TrackingCraftingMenuHandle extends TrackingRecipeBookMenuHandle {
        private TrackingCraftingMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
    }

    private static final class TrackingFurnaceMenuHandle extends TrackingRecipeBookMenuHandle
            implements FurnaceMenuHandle {
        private double cookProgress;
        private double fuelProgress;
        private TrackingFurnaceMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void setCookProgress(double progress) {
            this.cookProgress = progress;
        }
        @Override
        public void setFuelProgress(double progress) {
            this.fuelProgress = progress;
        }
    }

    private static final class TrackingEnchantmentMenuHandle extends TrackingMenuHandle
            implements EnchantmentMenuHandle {
        private final EnchantmentWindow.EnchantOption[] options = new EnchantmentWindow.EnchantOption[3];
        private int enchantmentSeed;
        private TrackingEnchantmentMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void setOption(int index, EnchantmentWindow.EnchantOption option) {
            this.options[index] = option;
        }
        @Override
        public void setEnchantmentSeed(int seed) {
            this.enchantmentSeed = seed;
        }
    }

    private static final class TrackingStonecutterMenuHandle extends TrackingMenuHandle
            implements StonecutterMenuHandle {
        private List<ItemStack> recipeButtons = List.of();
        private int selectedRecipeIndex = -1;
        private int clientSelectionCount;
        private int recipeButtonUpdates;
        private TrackingStonecutterMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void setRecipeButtons(ItemStack @NonNull [] buttons) {
            ArrayList<ItemStack> copy = new ArrayList<>(buttons.length);
            for (int index = 0; index < buttons.length; index++) {
                copy.add(buttons[index].clone());
            }
            List<ItemStack> snapshot = List.copyOf(copy);
            if (this.recipeButtons.equals(snapshot)) {
                return;
            }
            this.recipeButtons = snapshot;
            this.recipeButtonUpdates++;
            if (this.selectedRecipeIndex >= buttons.length) {
                this.selectedRecipeIndex = -1;
            }
        }
        @Override
        public void setSelectedRecipeIndex(int index) {
            this.selectedRecipeIndex = index;
        }
        @Override
        public void reconcileClientSelection(int index) {
            this.selectedRecipeIndex = index;
            this.clientSelectionCount++;
        }
    }

    private static final class TrackingMerchantMenuHandle extends TrackingMenuHandle
            implements MerchantMenuHandle {
        private int level;
        private double progress = -1.0;
        private boolean restockMessageEnabled;
        private List<MerchantWindow.Trade> trades = List.of();
        private long lastPeriodicTick;
        private long offerRevision;
        private long committedOfferRevision = -1;
        private long queuedOfferRevision;
        private boolean offersQueued;
        private int offerSynchronizationAttempts;
        private int offerSynchronizations;
        private int selectionReconciliations;
        private final ArrayList<String> protocolPackets = new ArrayList<>();
        private boolean selectionReconciliationPending;
        private boolean resultReconciliationPending;
        private boolean failNextOfferSynchronization;
        private boolean invalidateDuringNextOfferSynchronization;
        private TrackingMerchantMenuHandle(
                TrackingMenuFactory owner,
                Player viewer,
                int containerId,
                InventoryView view,
                long generation,
                TrackingMenuHandle previousHandle
        ) {
            super(owner, viewer, containerId, view, generation, previousHandle);
        }
        @Override
        public void open(
                @NonNull Component title,
                ItemStack @NonNull [] slots,
                @NonNull CursorSnapshot cursor
        ) {
            this.submitOffers(true);
            super.open(title, slots, cursor);
            this.commitOffers();
        }
        @Override
        public void synchronize(
                ItemStack @NonNull [] slots,
                @NonNull BitSet dirtySlots,
                @NonNull CursorSnapshot cursor,
                boolean cursorDirty,
                boolean forceFull
        ) {
            boolean reconcileSelection = this.selectionReconciliationPending;
            boolean full = forceFull || reconcileSelection;
            boolean reconcileResult = full || this.resultReconciliationPending;
            if (reconcileResult) {
                this.protocolPackets.add("empty-offers");
            }
            this.submitOffers(forceFull);
            this.protocolPackets.add(full ? "contents" : "rootChanges");
            super.synchronize(slots, dirtySlots, cursor, cursorDirty, full);
            if (reconcileResult) {
                this.protocolPackets.add("current-offers");
            }
            this.commitOffers();
            if (reconcileSelection) {
                this.selectionReconciliationPending = false;
            }
            this.resultReconciliationPending = false;
        }
        @Override
        public void reopenWithTitle(
                @NonNull Component title,
                ItemStack @NonNull [] slots,
                @NonNull CursorSnapshot cursor
        ) {
            this.submitOffers(true);
            super.reopenWithTitle(title, slots, cursor);
            this.commitOffers();
            this.selectionReconciliationPending = false;
        }
        @Override
        public boolean accepts(MenuInput.Common.@NonNull Interaction interaction) {
            boolean accepted = super.accepts(interaction);
            if (accepted) {
                this.resultReconciliationPending = true;
            }
            return accepted;
        }
        @Override
        public void setLevel(int level) {
            if (this.level == level) {
                return;
            }
            this.level = level;
            this.invalidateOffers();
        }
        @Override
        public void setProgress(double progress) {
            if (Double.compare(this.progress, progress) == 0) {
                return;
            }
            this.progress = progress;
            this.invalidateOffers();
        }
        @Override
        public void setRestockMessageEnabled(boolean enabled) {
            if (this.restockMessageEnabled == enabled) {
                return;
            }
            this.restockMessageEnabled = enabled;
            this.invalidateOffers();
        }
        @Override
        public void setTrades(@NonNull List<MerchantWindow.Trade> trades) {
            this.trades = List.copyOf(trades);
            this.invalidateOffers();
        }
        @Override
        public void invalidateClientContents() {
            this.selectionReconciliations++;
            this.selectionReconciliationPending = true;
        }
        @Override
        public boolean tickOffers() {
            ++this.lastPeriodicTick;
            return this.offerRevision != this.committedOfferRevision;
        }
        private void submitOffers(boolean forceFull) {
            long revision = this.offerRevision;
            this.offersQueued = forceFull || revision != this.committedOfferRevision;
            if (!this.offersQueued) {
                return;
            }
            this.queuedOfferRevision = revision;
            this.offerSynchronizationAttempts++;
            if (this.invalidateDuringNextOfferSynchronization) {
                this.invalidateDuringNextOfferSynchronization = false;
                this.invalidateOffers();
            }
            if (this.failNextOfferSynchronization) {
                this.failNextOfferSynchronization = false;
                throw new IllegalStateException("injected Merchant offer synchronization failure");
            }
        }
        private void commitOffers() {
            if (!this.offersQueued) {
                return;
            }
            this.committedOfferRevision = this.queuedOfferRevision;
            this.offersQueued = false;
            this.offerSynchronizations++;
        }
        private void invalidateOffers() {
            this.offerRevision++;
        }
    }

    private enum MenuKind {
        NORMAL,
        HOPPER,
        ANVIL,
        DISPENSER,
        DROPPER,
        GRINDSTONE,
        SMITHING,
        BREWING,
        CARTOGRAPHY,
        CRAFTER,
        CRAFTING,
        FURNACE,
        SMOKER,
        BLAST_FURNACE,
        ENCHANTMENT,
        STONECUTTER,
        MERCHANT
    }

    private record TrackingSynchronization(BitSet dirtySlots, boolean cursorDirty, boolean forceFull) {
    }

    private static final class OwnedDispatcher implements EntityScheduler {
        private final Plugin plugin;
        private final AtomicInteger liveTasks = new AtomicInteger();
        private OwnedDispatcher(Plugin plugin) {
            this.plugin = plugin;
            activeDispatcher = this;
        }
        @Override
        public boolean execute(@NonNull Plugin plugin, @NonNull Runnable task, @NonNull Runnable retired, long delay) {
            task.run();
            return true;
        }
        @Override
        public ScheduledTask run(@NonNull Plugin plugin, @NonNull Consumer<ScheduledTask> task, @NonNull Runnable retired) {
            task.accept(null);
            return null;
        }
        @Override
        public ScheduledTask runDelayed(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                @NonNull Runnable retired,
                long delay
        ) {
            throw new UnsupportedOperationException();
        }
        @Override
        public ScheduledTask runAtFixedRate(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                @NonNull Runnable retired,
                long initialDelay,
                long period
        ) {
            this.liveTasks.incrementAndGet();
            return new TrackingTask(plugin, this.liveTasks);
        }
    }

    private static final class TrackingTask implements ScheduledTask {
        private final Plugin plugin;
        private final AtomicInteger liveTasks;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private TrackingTask(Plugin plugin, AtomicInteger liveTasks) {
            this.plugin = plugin;
            this.liveTasks = liveTasks;
        }
        @Override
        public Plugin getOwningPlugin() {
            return this.plugin;
        }
        @Override
        public boolean isRepeatingTask() {
            return true;
        }
        @Override
        public CancelledState cancel() {
            if (this.cancelled.compareAndSet(false, true)) {
                this.liveTasks.decrementAndGet();
                return CancelledState.CANCELLED_BY_CALLER;
            }
            return CancelledState.CANCELLED_ALREADY;
        }
        @Override
        public ExecutionState getExecutionState() {
            return this.cancelled.get() ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }
}
