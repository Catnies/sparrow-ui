package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.click.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.click.InteractionEdits;
import net.momirealms.sparrow.ui.inventory.event.*;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.window.SparrowUiTestRuntime;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClickSemanticsTest {

    private PlayerMock player;
    private final List<String> reportedWarnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        this.player = net.momirealms.sparrow.ui.PlayerStub.addTo(server);
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
        });
        this.reportedWarnings.clear();
        SparrowUI.getInstance().warningsEnabled(true);
        SparrowUI.getInstance().setWarningHandler(this.reportedWarnings::add);
        SparrowUiTestRuntime.installOwnership(ignoredTarget -> true);
    }

    @AfterEach
    void tearDown() {
        SparrowUiTestRuntime.restoreOwnership();
        MockBukkit.unmock();
    }

    @Test
    void leftClickSwapExchangesBothSides() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = new ItemStack(Material.EMERALD, 2);

        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));
        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertEquals(2, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(Material.DIAMOND, context.cursor.getType());
        assertEquals(5, context.cursor.getAmount());
    }

    @Test
    void leftClickPicksUpPlacesMergesAndSwaps() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setMaxStackSizes(new int[]{10});
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);

        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));
        assertNull(inventory.itemAt(0));
        assertEquals(5, context.cursor.getAmount());
        assertTrue(context.dirty.contains(0));
        context.cursor = diamonds(12);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(2, context.cursor.getAmount());
        context.cursor = new ItemStack(Material.EMERALD, 3);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertEquals(3, inventory.itemAt(0).getAmount());
        assertEquals(Material.DIAMOND, context.cursor.getType());
        assertEquals(10, context.cursor.getAmount());
    }

    @Test
    void accessRuleRejectsPlacementMergeSwapAndPickup() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(2);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertNull(inventory.itemAt(0));
        inventory.setItem(reason(), 0, diamonds(3));
        context.cursor = diamonds(2);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(0)));
        inventory.setItem(reason(), 0, new ItemStack(Material.EMERALD));
        context.cursor = diamonds(2);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        context.cursor = ItemStack.empty();
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertTrue(context.cursor.isEmpty());
        assertEquals(4, ruleCalls.get());
        assertEquals(0, preCalls.get());
    }

    @Test
    void rightClickRuleSeesTheOneItemActuallyPlaced() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger seenAmount = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            seenAmount.set(placement.addedItem().getAmount());
            return false;
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(8);

        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0);

        assertEquals(1, seenAmount.get());
        assertNull(inventory.itemAt(0));
        assertEquals(8, context.cursor.getAmount());
        assertEquals(0, preCalls.get());
    }

    @Test
    void swapChecksStackLimitBeforeAccessRule() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setMaxStackSize(0, 1);
        inventory.setItem(reason(), 0, new ItemStack(Material.EMERALD));
        AtomicInteger ruleCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return true;
        });
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(2);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(0, ruleCalls.get());
        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertEquals(2, context.cursor.getAmount());
    }

    @Test
    void rightClickTakesHalfAndPlacesSingle() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0);

        assertEquals(2, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(3, context.cursor.getAmount());
        ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 1);

        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(2, context.cursor.getAmount());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void cursorBundleRightClickPlacesSelectedStackAndReinsertsRemainder() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setMaxStackSizes(new int[]{2});
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = bundle(diamonds(4));

        assertEquals(InventoryAction.PLACE_FROM_BUNDLE, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0));
        assertEquals(diamonds(2), inventory.itemAt(0));
        assertEquals(Material.BUNDLE, context.cursor.getType());
        assertEquals(List.of(diamonds(2)), ((BundleMeta) context.cursor.getItemMeta()).getItems());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void cursorBundleLeftClickPicksUpAllOrSomeIntoBundle() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(reason(), 0, diamonds(4));
        inventory.setItem(reason(), 1, diamonds(4));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        context.cursor = bundle();

        assertEquals(InventoryAction.PICKUP_ALL_INTO_BUNDLE, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));
        assertNull(inventory.itemAt(0));
        assertEquals(List.of(diamonds(4)), ((BundleMeta) context.cursor.getItemMeta()).getItems());
        context.cursor = bundle(diamonds(62));

        assertEquals(InventoryAction.PICKUP_SOME_INTO_BUNDLE, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 1));
        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 1));
        assertEquals(diamonds(2), inventory.itemAt(1));
        assertEquals(List.of(diamonds(64)), ((BundleMeta) context.cursor.getItemMeta()).getItems());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void bundleCanBeNestedByLeftClickAndSwappedByRightClick() {
        ItemStack slotBundle = bundle(diamonds(1));
        ItemStack cursorBundle = bundle(new ItemStack(Material.EMERALD));
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{slotBundle});
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = cursorBundle.clone();

        assertEquals(InventoryAction.PICKUP_ALL_INTO_BUNDLE, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));
        assertNull(inventory.itemAt(0));
        assertEquals(List.of(slotBundle, new ItemStack(Material.EMERALD)), ((BundleMeta) context.cursor.getItemMeta()).getItems());
        inventory.setItem(reason(), 0, slotBundle);
        context.cursor = cursorBundle.clone();

        assertEquals(InventoryAction.SWAP_WITH_CURSOR, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0));
        assertEquals(cursorBundle, inventory.itemAt(0));
        assertEquals(slotBundle, context.cursor);
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void bundleRightClickTakesSelectedStackAndKeepsEmptyBundleInSlot() {
        ItemStack bundle = bundle(new ItemStack(Material.DIAMOND), new ItemStack(Material.EMERALD, 4));
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{bundle});
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        AtomicInteger committed = new AtomicInteger();

        assertEquals(InventoryAction.PICKUP_FROM_BUNDLE, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0, bundle, 1, committed::incrementAndGet, ClickSemantics.InteractionGate.ALLOW_ALL));
        assertEquals(1, committed.get());
        assertEquals(new ItemStack(Material.EMERALD, 4), context.cursor);
        assertEquals(List.of(new ItemStack(Material.DIAMOND)), ((BundleMeta) inventory.itemAt(0).getItemMeta()).getItems());
        context.cursor = ItemStack.empty();
        inventory.setItem(reason(), 0, bundle());

        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0, null, -1, committed::incrementAndGet, ClickSemantics.InteractionGate.ALLOW_ALL));
        assertTrue(context.cursor.isEmpty());
        assertEquals(Material.BUNDLE, inventory.itemAt(0).getType());
        assertEquals(1, committed.get());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void bundleRightClickWithHeldItemSwapsTheBundle() {
        ItemStack bundle = bundle(new ItemStack(Material.DIAMOND));
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{bundle});
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = new ItemStack(Material.STONE, 32);
        AtomicInteger committed = new AtomicInteger();

        assertEquals(InventoryAction.SWAP_WITH_CURSOR, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0, bundle, 0, committed::incrementAndGet, ClickSemantics.InteractionGate.ALLOW_ALL));
        assertEquals(new ItemStack(Material.STONE, 32), inventory.itemAt(0));
        assertEquals(bundle, context.cursor);
        assertEquals(1, committed.get());
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void cancelledBundleTakeLeavesSelectionAndInventoryUntouched() {
        ItemStack bundle = bundle(new ItemStack(Material.DIAMOND), new ItemStack(Material.EMERALD));
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{bundle});
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        AtomicInteger committed = new AtomicInteger();

        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0, bundle, 1, committed::incrementAndGet, ClickSemantics.InteractionGate.ALLOW_ALL));
        assertEquals(0, committed.get());
        assertTrue(context.cursor.isEmpty());
        assertEquals(
                List.of(new ItemStack(Material.DIAMOND), new ItemStack(Material.EMERALD)),
                ((BundleMeta) inventory.itemAt(0).getItemMeta()).getItems()
        );

        assertTrue(context.dirty.contains(0));
    }

    @Test
    @Disabled("MockBukkit does not provide the NMS Item and BundleContents types")
    void staleBundleSelectionFallsBackToFirstCurrentStack() {
        ItemStack observedBundle = bundle(new ItemStack(Material.DIAMOND), new ItemStack(Material.EMERALD));
        ItemStack currentBundle = bundle(new ItemStack(Material.GOLD_INGOT, 2), new ItemStack(Material.COAL, 3));
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{currentBundle});
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);

        assertTrue(ClickSemantics.handleClick(context, ClickType.RIGHT, -1, 0, observedBundle, 1, () -> {
        }, ClickSemantics.InteractionGate.ALLOW_ALL));

        assertEquals(new ItemStack(Material.GOLD_INGOT, 2), context.cursor);
        assertEquals(List.of(new ItemStack(Material.COAL, 3)), ((BundleMeta) inventory.itemAt(0).getItemMeta()).getItems());
    }

    @Test
    void cancelledTransactionsLeaveClickUntouched() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(5));
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(context.dirty.contains(0));
    }

    @Test
    void frozenSlotsOnlyGetCorrected() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).freeze(0);

        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(context.dirty.contains(0));
    }

    @Test
    void itemSlotsAreNotHandled() {
        FakeContext context = new FakeContext(this.player);

        assertFalse(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));
    }

    @Test
    void estimateInventoryActionMapsCurrentClickBranchesWithoutMutation() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setMaxStackSizes(new int[]{10, 10, 10});
        inventory.setItem(reason(), 0, diamonds(5));
        inventory.setItem(reason(), 1, new ItemStack(Material.EMERALD, 3));
        FakeContext context = new FakeContext(this.player)
                .link(0, inventory, 0)
                .link(1, inventory, 1)
                .link(2, inventory, 2);

        assertEquals(InventoryAction.PICKUP_ALL, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        context.cursor = diamonds(7);

        assertEquals(InventoryAction.PLACE_SOME, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        context.cursor = diamonds(12);

        assertEquals(InventoryAction.PLACE_SOME, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 2));
        context.cursor = diamonds(1);

        assertEquals(InventoryAction.PLACE_ONE, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        context.cursor = new ItemStack(Material.EMERALD, 3);

        assertEquals(InventoryAction.SWAP_WITH_CURSOR, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        context.cursor = ItemStack.empty();

        assertEquals(InventoryAction.PICKUP_HALF, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        context.cursor = diamonds(2);

        assertEquals(InventoryAction.PLACE_ONE, ClickSemantics.estimateInventoryAction(context, ClickType.RIGHT, -1, 0));
        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.SHIFT_LEFT, -1, 0));
        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.DOUBLE_CLICK, -1, 0));
        VirtualInventory hotbar = new VirtualInventory(1);
        hotbar.setItem(reason(), 0, new ItemStack(Material.GOLD_INGOT, 1));
        context.hotbar(0, hotbar, 0);

        assertEquals(InventoryAction.HOTBAR_SWAP, ClickSemantics.estimateInventoryAction(context, ClickType.NUMBER_KEY, 0, 0));
        assertEquals(InventoryAction.HOTBAR_SWAP, ClickSemantics.estimateInventoryAction(context, ClickType.SWAP_OFFHAND, -1, 0));
        context.cursor = ItemStack.empty();

        assertEquals(InventoryAction.DROP_ONE_SLOT, ClickSemantics.estimateInventoryAction(context, ClickType.DROP, -1, 0));
        assertEquals(InventoryAction.DROP_ALL_SLOT, ClickSemantics.estimateInventoryAction(context, ClickType.CONTROL_DROP, -1, 0));
        this.player.setGameMode(GameMode.CREATIVE);

        assertEquals(InventoryAction.CLONE_STACK, ClickSemantics.estimateInventoryAction(context, ClickType.MIDDLE, -1, 0));
        context.cursor = diamonds(1);

        assertEquals(InventoryAction.COLLECT_TO_CURSOR, ClickSemantics.estimateInventoryAction(context, ClickType.DOUBLE_CLICK, -1, 2));
        assertEquals(InventoryAction.DROP_ALL_CURSOR, ClickSemantics.estimateInventoryAction(context, ClickType.WINDOW_BORDER_LEFT, -1, -999));
        assertEquals(InventoryAction.DROP_ONE_CURSOR, ClickSemantics.estimateInventoryAction(context, ClickType.WINDOW_BORDER_RIGHT, -1, -999));
        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.MIDDLE, -1, -999));
        context.freeze(0);

        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        assertEquals(InventoryAction.UNKNOWN, ClickSemantics.estimateInventoryAction(context, ClickType.UNKNOWN, -1, 0));
    }

    @Test
    void estimateInventoryActionDoesNotRefreshReference() {
        org.bukkit.inventory.Inventory chest = org.bukkit.Bukkit.createInventory(null, 9);
        chest.setItem(0, diamonds(5));
        AtomicInteger contentsReads = new AtomicInteger();
        ReferencingInventory referencing = ReferencingInventory.create(
                chest,
                inventory -> {
                    contentsReads.incrementAndGet();
                    return inventory.getContents();
                },
                java.util.function.UnaryOperator.identity(),
                false
        );
        AtomicInteger externalEvents = new AtomicInteger();
        referencing.subscribePostUpdate(event -> externalEvents.incrementAndGet());
        chest.setItem(1, diamonds(3));
        contentsReads.set(0);
        FakeContext context = new FakeContext(this.player).link(0, referencing, 0);

        assertEquals(InventoryAction.PICKUP_ALL, ClickSemantics.estimateInventoryAction(context, ClickType.LEFT, -1, 0));
        assertEquals(1, contentsReads.get());
        assertEquals(0, externalEvents.get());
        assertEquals(5, ItemUtils.amountOf(referencing.itemAt(0)));
        assertEquals(5, ItemUtils.amountOf(chest.getItem(0)));
        assertTrue(context.cursor.isEmpty());
        referencing.refresh();

        assertEquals(1, externalEvents.get());
    }

    @Test
    void committedClickFinishesCursorWhenLandFails() {
        IllegalStateException expected = new IllegalStateException("platform write failed");
        ReferencingInventory inventory = ReferencingInventory.of(new ThrowingWriteStorage(expected));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(5);

        assertSame(expected, assertThrows(
                IllegalStateException.class,
                () -> ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0)
        ));

        assertNull(inventory.itemAt(0));
        assertTrue(context.cursor.isEmpty());
    }

    @Test
    void viewerStorageLowerClickUsesInventoryTransaction() {
        this.player.getInventory().setItem(9, diamonds(6));
        ReferencingInventory referencing = ReferencingInventory.create(
                this.player.getInventory(),
                org.bukkit.inventory.Inventory::getStorageContents,
                ClickSemanticsTest::reorderPlayerStorage,
                true
        );
        AtomicInteger preEvents = new AtomicInteger();
        referencing.subscribePreUpdate(event -> preEvents.incrementAndGet());
        FakeContext context = new FakeContext(this.player).link(50, referencing, 0);

        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 50));
        assertEquals(1, preEvents.get());
        assertNull(this.player.getInventory().getItem(9));
        assertEquals(6, context.cursor.getAmount());
    }

    @Test
    void numberKeySwapsWithHotbarThroughTransaction() {
        VirtualInventory inventory = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(36);
        inventory.setItem(reason(), 0, diamonds(4));
        storage.setItem(reason(), 30, new ItemStack(Material.EMERALD, 2));
        FakeContext context = new FakeContext(this.player)
                .link(0, inventory, 0)
                .hotbar(3, storage, 30);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 3, 0);

        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertEquals(4, ItemUtils.amountOf(storage.itemAt(30)));
        assertEquals(Material.DIAMOND, storage.itemAt(30).getType());
    }

    @Test
    void numberKeyRequiresBothReceivingRootsToAcceptAtomically() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory hotbar = new VirtualInventory(1);
        clicked.setItem(reason(), 0, diamonds(4));
        hotbar.setItem(reason(), 0, new ItemStack(Material.EMERALD, 2));
        AtomicInteger clickedRules = new AtomicInteger();
        AtomicInteger hotbarRules = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        clicked.setAccessRule(placement -> {
            clickedRules.incrementAndGet();
            return true;
        });
        hotbar.setAccessRule(placement -> {
            hotbarRules.incrementAndGet();
            return false;
        });
        clicked.subscribePreUpdate(event -> preCalls.incrementAndGet());
        hotbar.subscribePreUpdate(event -> preCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0)
                .hotbar(0, hotbar, 0);

        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.NUMBER_KEY, 0, 0));
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(2, clickedRules.get());
        assertEquals(2, hotbarRules.get());
        assertEquals(Material.DIAMOND, clicked.itemAt(0).getType());
        assertEquals(Material.EMERALD, hotbar.itemAt(0).getType());
        assertEquals(0, preCalls.get());
    }

    @Test
    void identicalNumberKeyAndOffhandSwapsChangeNothingButStillReachBothListeners() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory hotbar = new VirtualInventory(1);
        clicked.setItem(reason(), 0, diamonds(4));
        hotbar.setItem(reason(), 0, diamonds(4));
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger bukkitCalls = new AtomicInteger();
        AtomicInteger sparrowCalls = new AtomicInteger();
        clicked.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return true;
        });
        hotbar.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return true;
        });
        clicked.subscribePreUpdate(event -> preCalls.incrementAndGet());
        hotbar.subscribePreUpdate(event -> preCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0)
                .hotbar(0, hotbar, 0);
        context.offhandItem = diamonds(4);

        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.NUMBER_KEY, 0, 0));
        ClickSemantics.InteractionGate gate = new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                return true;
            }
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                sparrowCalls.incrementAndGet();
                return true;
            }
        };
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0, null, -1, () -> {}, gate);

        assertEquals(InventoryAction.NOTHING, ClickSemantics.estimateInventoryAction(context, ClickType.SWAP_OFFHAND, -1, 0));
        ClickSemantics.handleClick(context, ClickType.SWAP_OFFHAND, -1, 0, null, -1, () -> {}, gate);

        assertEquals(0, ruleCalls.get());
        assertEquals(0, preCalls.get());
        assertEquals(2, bukkitCalls.get());
        assertEquals(2, sparrowCalls.get());
        assertEquals(4, clicked.itemAt(0).getAmount());
        assertEquals(4, hotbar.itemAt(0).getAmount());
        assertEquals(4, context.offhandItem.getAmount());
    }

    @Test
    void numberKeyChecksStackLimitBeforeTheReceivingRule() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory hotbar = new VirtualInventory(1);
        clicked.setItem(reason(), 0, diamonds(4));
        hotbar.setItem(reason(), 0, new ItemStack(Material.EMERALD));
        hotbar.setMaxStackSize(0, 1);
        AtomicInteger clickedRules = new AtomicInteger();
        AtomicInteger hotbarRules = new AtomicInteger();
        clicked.setAccessRule(placement -> {
            clickedRules.incrementAndGet();
            return true;
        });
        hotbar.setAccessRule(placement -> {
            hotbarRules.incrementAndGet();
            return true;
        });
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0)
                .hotbar(0, hotbar, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(0, clickedRules.get());
        assertEquals(0, hotbarRules.get());
        assertEquals(Material.DIAMOND, clicked.itemAt(0).getType());
        assertEquals(Material.EMERALD, hotbar.itemAt(0).getType());
    }

    @Test
    void pureLowerNumberKeySwapsTwoViewerStorageSlotsInOneTransaction() {
        VirtualInventory storage = new VirtualInventory(36);
        storage.setItem(reason(), 0, diamonds(4));
        storage.setItem(reason(), 27, new ItemStack(Material.EMERALD, 2));
        AtomicInteger preEvents = new AtomicInteger();
        AtomicReference<UpdateReason> reason = new AtomicReference<>();
        storage.subscribePreUpdate(event -> {
            preEvents.incrementAndGet();
            reason.set(event.reason());
        });
        FakeContext context = new FakeContext(this.player)
                .link(40, storage, 0)
                .hotbar(0, storage, 27);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 40);

        assertEquals(1, preEvents.get());
        PlayerUpdateReason.Click click = assertInstanceOf(PlayerUpdateReason.Click.class, reason.get());

        assertEquals(this.player, click.player());
        assertEquals(ClickType.NUMBER_KEY, click.clickType());
        assertEquals(0, click.hotbarButton());
        assertEquals(Material.EMERALD, storage.itemAt(0).getType());
        assertEquals(4, ItemUtils.amountOf(storage.itemAt(27)));
        assertEquals(Material.DIAMOND, storage.itemAt(27).getType());
    }

    @Test
    void offhandSwapAndDropSemantics() {
        VirtualInventory storage = new VirtualInventory(36);
        storage.setItem(reason(), 0, diamonds(4));
        storage.setItem(reason(), 1, diamonds(9));
        FakeContext context = new FakeContext(this.player).link(0, storage, 0).link(1, storage, 1);
        ClickSemantics.handleClick(context, ClickType.SWAP_OFFHAND, 40, 0);

        assertNull(storage.itemAt(0));
        assertEquals(4, ItemUtils.amountOf(context.offhandItem));
        ClickSemantics.handleClick(context, ClickType.DROP, -1, 1);

        assertEquals(8, ItemUtils.amountOf(storage.itemAt(1)));
        ClickSemantics.handleClick(context, ClickType.CONTROL_DROP, -1, 1);

        assertNull(storage.itemAt(1));
        assertEquals(2, context.drops.size());
        assertEquals(1, context.drops.get(0).getAmount());
        assertEquals(8, context.drops.get(1).getAmount());
    }

    @Test
    void offhandSwapChecksOnlyTheClickedReceivingRoot() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(4));
        AtomicInteger ruleCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.offhandItem = new ItemStack(Material.EMERALD, 2);
        ClickSemantics.handleClick(context, ClickType.SWAP_OFFHAND, -1, 0);

        assertEquals(1, ruleCalls.get());
        assertEquals(Material.DIAMOND, inventory.itemAt(0).getType());
        assertEquals(Material.EMERALD, context.offhandItem.getType());
    }

    @Test
    void emptyOffhandSwapChecksRemovalAccess() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(4));
        AtomicInteger ruleCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.SWAP_OFFHAND, -1, 0);

        assertEquals(1, ruleCalls.get());
        assertEquals(4, inventory.itemAmount(0));
        assertNull(context.offhandItem);
    }

    @Test
    void creativeMiddleClickClonesFullStackToCursor() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(3));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.MIDDLE, -1, 0);

        assertTrue(context.cursor.isEmpty());
        this.player.setGameMode(GameMode.CREATIVE);
        ClickSemantics.handleClick(context, ClickType.MIDDLE, -1, 0);

        assertEquals(64, context.cursor.getAmount());
        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    void creativeCandidatesRevalidateGameModeAfterBukkit() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(reason(), 0, diamonds(3));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        this.player.setGameMode(GameMode.CREATIVE);
        ClickSemantics.handleClick(context, ClickType.MIDDLE, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                ClickSemanticsTest.this.player.setGameMode(GameMode.SURVIVAL);
                return true;
            }
        });

        assertTrue(context.cursor.isEmpty());
        this.player.setGameMode(GameMode.CREATIVE);
        context.cursor = diamonds(4);
        ClickSemantics.handleDrag(context, ClickType.MIDDLE, List.of(1), new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowDrag(ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
                ClickSemanticsTest.this.player.setGameMode(GameMode.SURVIVAL);
                return true;
            }
        });

        assertNull(inventory.itemAt(1));
        assertEquals(4, context.cursor.getAmount());
    }

    @Test
    void shiftFromLinkPrefersHigherPriorityInventoryInOneTransaction() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory low = new VirtualInventory(1);
        VirtualInventory high = new VirtualInventory(1);
        high.operationPriority(OperationCategory.ADD, 10);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, low, 0).link(2, high, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(high.itemAt(0)));
        assertNull(low.itemAt(0));
    }

    @Test
    void shiftContinuesAcrossEveryPriorityTargetInOneTransaction() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory left = new VirtualInventory(1);
        VirtualInventory right = new VirtualInventory(1);
        left.setMaxStackSize(0, 16);
        right.setMaxStackSize(0, 48);
        source.setItem(reason(), 0, diamonds(64));
        AtomicInteger leftAmount = new AtomicInteger();
        AtomicInteger rightAmount = new AtomicInteger();
        left.setAccessRule(placement -> {
            leftAmount.set(placement.addedItem().getAmount());
            return true;
        });
        right.setAccessRule(placement -> {
            rightAmount.set(placement.addedItem().getAmount());
            return true;
        });
        AtomicInteger postCalls = new AtomicInteger();
        AtomicInteger changedRoots = new AtomicInteger();
        source.subscribePostUpdate(event -> {
            postCalls.incrementAndGet();
            changedRoots.set(event.rootChanges().size());
        });
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0)
                .link(1, left, 0)
                .link(2, right, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertEquals(16, ItemUtils.amountOf(left.itemAt(0)));
        assertEquals(48, ItemUtils.amountOf(right.itemAt(0)));
        assertEquals(16, leftAmount.get());
        assertEquals(48, rightAmount.get());
        assertEquals(1, postCalls.get());
        assertEquals(3, changedRoots.get());
    }

    @Test
    void bukkitWriteToShiftTargetReplansIntoTheEmptiedTarget() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory full = new VirtualInventory(1);
        VirtualInventory fallback = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        full.setItem(reason(), 0, diamonds(64));
        full.operationPriority(OperationCategory.ADD, 10);
        AtomicInteger sparrowCalls = new AtomicInteger();
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0)
                .link(1, full, 0)
                .link(2, fallback, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                full.setItem(reason(), 0, null);
                return true;
            }
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                sparrowCalls.incrementAndGet();
                return true;
            }
        });

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(full.itemAt(0)));
        assertNull(fallback.itemAt(0));
        assertEquals(1, sparrowCalls.get());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void preWriteToReadOnlyShiftTargetConflictsWholeCandidate() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory full = new VirtualInventory(1);
        VirtualInventory fallback = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        full.setItem(reason(), 0, diamonds(64));
        full.operationPriority(OperationCategory.ADD, 10);
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger shiftPostCalls = new AtomicInteger();
        source.subscribePreUpdate(event -> {
            preCalls.incrementAndGet();
            full.setItem(UpdateReason.Program.INSTANCE, 0, null);
        });
        source.subscribePostUpdate(event -> shiftPostCalls.incrementAndGet());
        fallback.subscribePostUpdate(event -> shiftPostCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0)
                .link(1, full, 0)
                .link(2, fallback, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(1, preCalls.get());
        assertEquals(0, shiftPostCalls.get());
        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(full.itemAt(0));
        assertNull(fallback.itemAt(0));
    }

    @Test
    void liveReferencedWriteDuringBukkitGateReplansBeforeSparrowGate() {
        org.bukkit.inventory.Inventory chest = org.bukkit.Bukkit.createInventory(null, 9);
        ReferencingInventory referencing = ReferencingInventory.create(
                chest,
                org.bukkit.inventory.Inventory::getContents,
                java.util.function.UnaryOperator.identity(),
                false
        );
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger playerPreCalls = new AtomicInteger();
        referencing.subscribePreUpdate(event -> {
            if (event.reason() instanceof PlayerUpdateReason) {
                playerPreCalls.incrementAndGet();
            }
        });
        FakeContext context = new FakeContext(this.player).link(0, referencing, 0);
        context.cursor = diamonds(5);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                chest.setItem(1, new ItemStack(Material.EMERALD, 2));
                return true;
            }
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                sparrowCalls.incrementAndGet();
                return true;
            }
        });

        assertEquals(1, sparrowCalls.get());
        assertEquals(1, playerPreCalls.get());
        assertEquals(new ItemStack(Material.EMERALD, 2), chest.getItem(1));
        assertEquals(diamonds(5), chest.getItem(0));
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void externalContainerIsResyncedOnceForEachGateThatRanUserCode() {
        int silent = this.placeIntoCountingStorage(false, false).comparisons;

        assertEquals(silent + 1, this.placeIntoCountingStorage(true, false).comparisons);
        assertEquals(silent + 2, this.placeIntoCountingStorage(true, true).comparisons);
    }

    private CountingStorage placeIntoCountingStorage(boolean bukkitGateFires, boolean sparrowObserved) {
        CountingStorage storage = new CountingStorage();
        ReferencingInventory referencing = ReferencingInventory.of(storage);
        if (sparrowObserved) {
            referencing.subscribeClick(event -> {});
        }
        FakeContext context = new FakeContext(this.player).link(0, referencing, 0);
        context.cursor = diamonds(5);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean firesBukkitEvents() {
                return bukkitGateFires;
            }
        });

        assertEquals(diamonds(5), storage.items[0]);
        assertTrue(context.cursor.isEmpty());
        return storage;
    }

    @Test
    void cursorChangedDuringPreConflictsPlacementBeforeCommit() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(5);
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> context.cursor = new ItemStack(Material.EMERALD, 2));
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertNull(inventory.itemAt(0));
        assertEquals(new ItemStack(Material.EMERALD, 2), context.cursor);
        assertEquals(0, postCalls.get());
        assertEquals(1, this.reportedWarnings.size());
    }

    @Test
    void offhandChangedDuringPreConflictsSwapBeforeCommit() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(4));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.offhandItem = new ItemStack(Material.EMERALD, 2);
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> context.offhandItem = new ItemStack(Material.GOLD_INGOT));
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        ClickSemantics.handleClick(context, ClickType.SWAP_OFFHAND, -1, 0);

        assertEquals(diamonds(4), inventory.itemAt(0));
        assertEquals(new ItemStack(Material.GOLD_INGOT), context.offhandItem);
        assertEquals(0, postCalls.get());
    }

    @Test
    void shiftFromUpperMovesOnlyIntoViewerStorage() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(36);
        source.setItem(reason(), 0, diamonds(70));
        storage.setItem(reason(), 0, diamonds(60));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).inventory(storage);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertEquals(64, ItemUtils.amountOf(storage.itemAt(0)));
        assertEquals(64, ItemUtils.amountOf(storage.itemAt(1)));
        assertEquals(2, ItemUtils.amountOf(storage.itemAt(2)));
        assertEquals(130, totalOf(storage));
    }

    @Test
    void fullHigherPriorityTargetFallsThroughToTheNextInventory() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory sibling = new VirtualInventory(1);
        ItemStack[] full = new ItemStack[36];
        for (int slot = 0; slot < full.length; slot++) {
            full[slot] = new ItemStack(Material.COBBLESTONE, 64);
        }
        VirtualInventory storage = new VirtualInventory(full);
        storage.operationPriority(OperationCategory.ADD, Integer.MAX_VALUE);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, sibling, 0).inventory(storage);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(sibling.itemAt(0)));
    }

    @Test
    void placementRulesRouteShiftAcrossIndependentRoots() {
        VirtualInventory source = new VirtualInventory(2);
        VirtualInventory left = new VirtualInventory(2);
        VirtualInventory right = new VirtualInventory(1);
        AtomicInteger leftRules = new AtomicInteger();
        AtomicInteger rightRules = new AtomicInteger();
        left.setAccessRule(placement -> {
            leftRules.incrementAndGet();
            return placement.addedItem().getType() == Material.APPLE;
        });
        right.setAccessRule(placement -> {
            rightRules.incrementAndGet();
            return placement.addedItem().getType() == Material.GRASS_BLOCK;
        });
        source.setItem(reason(), 0, new ItemStack(Material.APPLE));
        source.setItem(reason(), 1, new ItemStack(Material.GRASS_BLOCK));
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, source, 1)
                .link(2, left, 0).link(3, left, 1).link(4, right, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 1);

        assertNull(source.itemAt(0));
        assertNull(source.itemAt(1));
        assertEquals(Material.APPLE, left.itemAt(0).getType());
        assertNull(left.itemAt(1));
        assertEquals(Material.GRASS_BLOCK, right.itemAt(0).getType());
        assertEquals(2, leftRules.get());
        assertEquals(1, rightRules.get());
    }

    @Test
    void shiftFallsThroughWhenAccessRuleRejectsTheViewer() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory restricted = new VirtualInventory(1);
        VirtualInventory fallback = new VirtualInventory(1);
        AtomicReference<Player> seenPlayer = new AtomicReference<>();
        restricted.setAccessRule(placement -> {
            seenPlayer.set(placement.player());
            return false;
        });
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, restricted, 0).link(2, fallback, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertNull(restricted.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(fallback.itemAt(0)));
        assertSame(this.player, seenPlayer.get());
    }

    @Test
    void cancelledShiftCandidateDoesNotFallback() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory left = new VirtualInventory(1);
        VirtualInventory right = new VirtualInventory(1);
        AtomicInteger leftPost = new AtomicInteger();
        AtomicInteger rightPost = new AtomicInteger();
        left.subscribePreUpdate(event -> event.setCancelled(true));
        left.subscribePostUpdate(event -> leftPost.incrementAndGet());
        right.subscribePostUpdate(event -> rightPost.incrementAndGet());
        source.setItem(reason(), 0, new ItemStack(Material.GRASS_BLOCK, 2));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).inventory(left).inventory(right);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(2, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(left.itemAt(0));
        assertNull(right.itemAt(0));
        assertEquals(0, leftPost.get());
        assertEquals(0, rightPost.get());
    }

    @Test
    void rejectedReferencingTargetAlsoSkipsItsPhysicalAlias() {
        org.bukkit.inventory.Inventory chest = org.bukkit.Bukkit.createInventory(null, 9);
        ReferencingInventory first = ReferencingInventory.create(
                chest, org.bukkit.inventory.Inventory::getContents, java.util.function.UnaryOperator.identity(), false);
        ReferencingInventory alias = ReferencingInventory.create(
                chest, org.bukkit.inventory.Inventory::getContents, java.util.function.UnaryOperator.identity(), false);
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory fallback = new VirtualInventory(1);
        AtomicInteger aliasRules = new AtomicInteger();
        first.setAccessRule(placement -> false);
        alias.setAccessRule(placement -> {
            aliasRules.incrementAndGet();
            return true;
        });
        source.setItem(reason(), 0, new ItemStack(Material.GRASS_BLOCK));
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, first, 0).link(2, alias, 0).link(3, fallback, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertNull(chest.getItem(0));
        assertEquals(0, aliasRules.get());
        assertEquals(Material.GRASS_BLOCK, fallback.itemAt(0).getType());
    }

    @Test
    void sourceCancellationStopsShiftWithoutTryingAnotherTarget() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory first = new VirtualInventory(1);
        VirtualInventory second = new VirtualInventory(1);
        AtomicInteger secondPre = new AtomicInteger();
        source.setItem(reason(), 0, diamonds(5));
        source.subscribePreUpdate(event -> event.setCancelled(true));
        second.subscribePreUpdate(event -> secondPre.incrementAndGet());
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, first, 0).link(2, second, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(first.itemAt(0));
        assertNull(second.itemAt(0));
        assertEquals(0, secondPre.get());
    }

    @Test
    void shiftFromViewerStorageMovesIntoUpperInventory() {
        VirtualInventory target = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(36);
        target.setMaxStackSizes(new int[]{5});
        storage.setItem(reason(), 0, diamonds(8));
        FakeContext context = new FakeContext(this.player)
                .link(0, target, 0).link(40, storage, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 40);

        assertEquals(5, ItemUtils.amountOf(target.itemAt(0)));
        assertEquals(3, ItemUtils.amountOf(storage.itemAt(0)));
    }

    @Test
    void doubleClickCollectsFromUpperThenViewerStorage() {
        VirtualInventory first = new VirtualInventory(2);
        VirtualInventory second = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(36);
        storage.operationPriority(OperationCategory.COLLECT, Integer.MIN_VALUE);
        first.setItem(reason(), 0, diamonds(20));
        second.setItem(reason(), 0, diamonds(30));
        storage.setItem(reason(), 0, diamonds(30));
        FakeContext context = new FakeContext(this.player)
                .link(0, first, 0).link(1, second, 0).link(2, first, 1).inventory(storage);
        context.cursor = diamonds(10);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 2);

        assertEquals(64, context.cursor.getAmount());
        assertNull(first.itemAt(0));
        assertNull(second.itemAt(0));
        assertEquals(26, ItemUtils.amountOf(storage.itemAt(0)));
    }

    @Test
    void doubleClickOnSlotWithoutInventoryNeverCollects() {
        VirtualInventory source = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(30));
        FakeContext context = new FakeContext(this.player).link(0, source, 0);
        context.cursor = diamonds(10);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 99);

        assertEquals(10, context.cursor.getAmount());
        assertEquals(30, ItemUtils.amountOf(source.itemAt(0)));
    }

    @Test
    void doubleClickOnBackgroundCoveredSlotNeverCollects() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory source = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(30));
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0).link(1, source, 0).background(0);
        context.cursor = diamonds(10);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 0);

        assertEquals(10, context.cursor.getAmount());
        assertEquals(30, ItemUtils.amountOf(source.itemAt(0)));
    }

    @Test
    void cancelledCollectLeavesEveryInventoryUntouched() {
        VirtualInventory first = new VirtualInventory(2);
        VirtualInventory second = new VirtualInventory(1);
        first.setItem(reason(), 0, diamonds(20));
        second.setItem(reason(), 0, diamonds(30));
        second.subscribePreUpdate(event -> event.setCancelled(true));
        FakeContext context = new FakeContext(this.player)
                .link(0, first, 0).link(1, second, 0).link(2, first, 1);
        context.cursor = diamonds(10);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 2);

        assertEquals(20, ItemUtils.amountOf(first.itemAt(0)));
        assertEquals(30, ItemUtils.amountOf(second.itemAt(0)));
        assertEquals(10, context.cursor.getAmount());
    }

    @Test
    void collectHonorsInventoryPriorityBeforeStackShape() {
        VirtualInventory high = new VirtualInventory(1);
        VirtualInventory low = new VirtualInventory(2);
        high.operationPriority(OperationCategory.COLLECT, 10);
        high.setItem(reason(), 0, diamonds(64));
        low.setItem(reason(), 0, diamonds(20));
        FakeContext context = new FakeContext(this.player)
                .link(0, low, 0).link(1, high, 0).link(2, low, 1);
        context.cursor = diamonds(1);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 2);

        assertEquals(64, context.cursor.getAmount());
        assertEquals(1, ItemUtils.amountOf(high.itemAt(0)));
        assertEquals(20, ItemUtils.amountOf(low.itemAt(0)));
    }

    @Test
    void doubleClickNeverCollectsFromObscuredSlots() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(2);
        storage.setItem(reason(), 0, diamonds(10));
        storage.setItem(reason(), 1, diamonds(20));
        FakeContext context = new FakeContext(this.player).link(0, clicked, 0).link(1, storage, 0);
        context.cursor = diamonds(1);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 0);

        assertEquals(11, context.cursor.getAmount());
        assertNull(storage.itemAt(0));
        assertEquals(20, ItemUtils.amountOf(storage.itemAt(1)));
    }

    @Test
    void doubleClickSkipsSlotsShownOnlyThroughFrozenPaths() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(2);
        storage.setItem(reason(), 0, diamonds(10));
        storage.setItem(reason(), 1, diamonds(20));
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0).link(1, storage, 0).link(2, storage, 1).freeze(2);
        context.cursor = diamonds(1);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 0);

        assertEquals(11, context.cursor.getAmount());
        assertNull(storage.itemAt(0));
        assertEquals(20, ItemUtils.amountOf(storage.itemAt(1)));
    }

    @Test
    void leftDragSplitsEvenlyAcrossUpperAndViewerStorage() {
        VirtualInventory inventory = new VirtualInventory(2);
        VirtualInventory storage = new VirtualInventory(36);
        FakeContext context = new FakeContext(this.player)
                .link(0, inventory, 0).link(1, inventory, 1)
                .link(30, storage, 0);
        context.cursor = diamonds(10);
        AtomicReference<UpdateReason> reason = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> reason.set(event.reason()));
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1, 30));
        PlayerUpdateReason.Drag drag = assertInstanceOf(PlayerUpdateReason.Drag.class, reason.get());

        assertEquals(this.player, drag.player());
        assertEquals(ClickType.LEFT, drag.clickType());
        assertEquals(List.of(
                new ClickSemantics.LinkedSlot(inventory, 0),
                new ClickSemantics.LinkedSlot(inventory, 1),
                new ClickSemantics.LinkedSlot(storage, 0)
        ), drag.slots());

        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(3, ItemUtils.amountOf(storage.itemAt(0)));
        assertEquals(1, context.cursor.getAmount());
    }

    @Test
    void rightDragPlacesOneEach() {
        VirtualInventory inventory = new VirtualInventory(2);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        context.cursor = diamonds(5);
        ClickSemantics.handleDrag(context, ClickType.RIGHT, List.of(0, 1));

        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(3, context.cursor.getAmount());
    }

    @Test
    void leftDragFiltersThenRedistributesAcrossSurvivingTargets() {
        VirtualInventory inventory = new VirtualInventory(4);
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicInteger wrongAmount = new AtomicInteger();
        for (int slot = 0; slot < 4; slot++) {
            int acceptedSlot = slot;
            inventory.setAccessRule(slot, placement -> {
                ruleCalls.incrementAndGet();
                if (placement.addedAmount() != 2 && placement.addedAmount() != 4) {
                    wrongAmount.incrementAndGet();
                }
                return acceptedSlot == 0 || acceptedSlot == 2;
            });
        }
        FakeContext context = new FakeContext(this.player)
                .link(0, inventory, 0)
                .link(1, inventory, 1)
                .link(2, inventory, 2)
                .link(3, inventory, 3);
        context.cursor = diamonds(8);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1, 2, 3));

        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(0)));
        assertNull(inventory.itemAt(1));
        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(2)));
        assertNull(inventory.itemAt(3));
        assertTrue(context.cursor.isEmpty());
        assertEquals(6, ruleCalls.get());
        assertEquals(0, wrongAmount.get());
    }

    @Test
    void placementRuleExceptionAbortsClickAndDragBeforePre() {
        VirtualInventory inventory = new VirtualInventory(2);
        AtomicInteger preCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            throw new IllegalStateException("rule-boom");
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player)
                .link(0, inventory, 0)
                .link(1, inventory, 1);
        context.cursor = diamonds(4);

        assertThrows(IllegalStateException.class, () ->
                ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0));

        assertThrows(IllegalStateException.class, () ->
                ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1)));

        assertNull(inventory.itemAt(0));
        assertNull(inventory.itemAt(1));
        assertEquals(4, context.cursor.getAmount());
        assertEquals(0, preCalls.get());
    }

    @Test
    void middleDragRequiresCreativeAndKeepsCursorAfterSuccessfulFill() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(2);
        ClickSemantics.handleDrag(context, ClickType.MIDDLE, List.of(0));

        assertNull(inventory.itemAt(0));
        this.player.setGameMode(GameMode.CREATIVE);
        ClickSemantics.handleDrag(context, ClickType.MIDDLE, List.of(0));

        assertEquals(64, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(2, context.cursor.getAmount());
    }

    @Test
    void outsideClicksDropCursor() {
        FakeContext context = new FakeContext(this.player);
        context.cursor = diamonds(5);
        ClickSemantics.handleOutsideClick(context, ClickType.WINDOW_BORDER_RIGHT);

        assertEquals(4, context.cursor.getAmount());
        assertEquals(1, context.drops.get(0).getAmount());
        ClickSemantics.handleOutsideClick(context, ClickType.WINDOW_BORDER_LEFT);

        assertTrue(context.cursor.isEmpty());
        assertEquals(4, context.drops.get(1).getAmount());
    }

    @Test
    void outsideClicksWithoutVanillaDropSemanticsAreNoOps() {
        FakeContext context = new FakeContext(this.player);
        context.cursor = diamonds(5);
        ClickSemantics.handleOutsideClick(context, ClickType.MIDDLE);
        ClickSemantics.handleOutsideClick(context, ClickType.SHIFT_LEFT);
        ClickSemantics.handleOutsideClick(context, ClickType.DROP);
        ClickSemantics.handleOutsideClick(context, ClickType.UNKNOWN);

        assertEquals(5, context.cursor.getAmount(), "原版只有左右边框点击有丢物语义");
        assertTrue(context.drops.isEmpty());
    }

    @Test
    void staleMirrorReadsAreReconciledBeforePlanning() {
        org.bukkit.inventory.Inventory chest = org.bukkit.Bukkit.createInventory(null, 9);
        chest.setItem(0, diamonds(10));
        ReferencingInventory referencing = ReferencingInventory.create(
                chest,
                org.bukkit.inventory.Inventory::getContents,
                java.util.function.UnaryOperator.identity(),
                false
        );
        chest.setItem(0, diamonds(8));
        FakeContext context = new FakeContext(this.player).link(0, referencing, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0);

        assertEquals(8, context.cursor.getAmount());
        assertNull(chest.getItem(0));
        assertNull(referencing.itemAt(0));
    }

    @Test
    void aliasedSlotsParticipateOnceInDrag() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger ruleCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return true;
        });
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 0);
        context.cursor = diamonds(64);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1));

        assertEquals(64, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertEquals(1, ruleCalls.get());
    }

    @Test
    void shiftExcludesEveryTargetInSourceInventory() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertNull(inventory.itemAt(1));
    }

    @Test
    void shiftNeverMovesIntoObscuredSlots() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(3);
        target.setItem(reason(), 1, diamonds(32));
        source.setItem(reason(), 0, diamonds(64));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 1);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(64, ItemUtils.amountOf(target.itemAt(1)));
        assertNull(target.itemAt(0));
        assertNull(target.itemAt(2));
        assertEquals(32, ItemUtils.amountOf(source.itemAt(0)));
    }

    @Test
    void shiftSkipsSlotsShownOnlyThroughFrozenPaths() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(2);
        target.setMaxStackSize(0, 32);
        source.setItem(reason(), 0, diamonds(64));
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0).link(1, target, 0).link(2, target, 1).freeze(2);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(32, ItemUtils.amountOf(target.itemAt(0)));
        assertNull(target.itemAt(1));
        assertEquals(32, ItemUtils.amountOf(source.itemAt(0)));
    }

    @Test
    void disabledDirectInventorySkipsBukkitGateForCandidateAndNoopClicks() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory empty = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        source.fireBukkitInventoryEvents(false);
        empty.fireBukkitInventoryEvents(false);
        AtomicInteger bukkitCalls = new AtomicInteger();
        AtomicInteger sparrowCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        source.subscribePostUpdate(event -> postCalls.incrementAndGet());
        ClickSemantics.InteractionGate gate = new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                return true;
            }
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                sparrowCalls.incrementAndGet();
                return true;
            }
        };
        FakeContext sourceContext = new FakeContext(this.player).link(0, source, 0);
        FakeContext emptyContext = new FakeContext(this.player).link(0, empty, 0);
        ClickSemantics.handleClick(sourceContext, ClickType.LEFT, -1, 0, null, -1, () -> {}, gate);
        ClickSemantics.handleClick(emptyContext, ClickType.LEFT, -1, 0, null, -1, () -> {}, gate);

        assertEquals(0, bukkitCalls.get());
        assertEquals(2, sparrowCalls.get());
        assertEquals(1, postCalls.get());
        assertNull(source.itemAt(0));
        assertEquals(5, sourceContext.cursor.getAmount());
    }

    @Test
    void shiftDispatchUsesDirectAndActualWriteInventoriesOnly() {
        AtomicInteger bukkitCalls = new AtomicInteger();
        ClickSemantics.InteractionGate gate = new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                return true;
            }
        };
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory enabledTarget = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        source.fireBukkitInventoryEvents(false);
        FakeContext enabledContext = new FakeContext(this.player).link(0, source, 0).link(1, enabledTarget, 0);
        ClickSemantics.handleClick(enabledContext, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, gate);

        assertEquals(1, bukkitCalls.get());
        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(enabledTarget.itemAt(0)));
        VirtualInventory secondSource = new VirtualInventory(1);
        VirtualInventory fullButEnabled = new VirtualInventory(1);
        VirtualInventory disabledFallback = new VirtualInventory(1);
        secondSource.setItem(reason(), 0, diamonds(5));
        fullButEnabled.setItem(reason(), 0, diamonds(64));
        fullButEnabled.operationPriority(OperationCategory.ADD, 10);
        secondSource.fireBukkitInventoryEvents(false);
        disabledFallback.fireBukkitInventoryEvents(false);
        FakeContext disabledContext = new FakeContext(this.player)
                .link(0, secondSource, 0)
                .link(1, fullButEnabled, 0)
                .link(2, disabledFallback, 0);
        ClickSemantics.handleClick(disabledContext, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, gate);

        assertEquals(1, bukkitCalls.get());
        assertNull(secondSource.itemAt(0));
        assertEquals(64, ItemUtils.amountOf(fullButEnabled.itemAt(0)));
        assertEquals(5, ItemUtils.amountOf(disabledFallback.itemAt(0)));
    }

    @Test
    void hotbarDispatchUsesBothWrittenInventories() {
        VirtualInventory upper = new VirtualInventory(1);
        VirtualInventory hotbar = new VirtualInventory(1);
        upper.setItem(reason(), 0, diamonds(5));
        hotbar.setItem(reason(), 0, new ItemStack(Material.EMERALD, 2));
        upper.fireBukkitInventoryEvents(false);
        AtomicInteger bukkitCalls = new AtomicInteger();
        FakeContext context = new FakeContext(this.player).link(0, upper, 0).hotbar(0, hotbar, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                return true;
            }
        });

        assertEquals(1, bukkitCalls.get());
        assertEquals(Material.EMERALD, upper.itemAt(0).getType());
        assertEquals(Material.DIAMOND, hotbar.itemAt(0).getType());
    }

    @Test
    void doubleClickDispatchIgnoresInventoriesThatWereOnlyRead() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory matching = new VirtualInventory(1);
        VirtualInventory unmatchedButEnabled = new VirtualInventory(1);
        matching.setItem(reason(), 0, diamonds(5));
        unmatchedButEnabled.setItem(reason(), 0, new ItemStack(Material.EMERALD, 5));
        clicked.fireBukkitInventoryEvents(false);
        matching.fireBukkitInventoryEvents(false);
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0)
                .link(1, matching, 0)
                .link(2, unmatchedButEnabled, 0);
        context.cursor = diamonds(1);
        AtomicInteger bukkitCalls = new AtomicInteger();
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                return true;
            }
        });

        assertEquals(0, bukkitCalls.get());
        assertEquals(6, context.cursor.getAmount());
        assertNull(matching.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(unmatchedButEnabled.itemAt(0)));
    }

    @Test
    void dragDispatchUsesAnyWrittenInventoryAndKeepsTheCompletePayload() {
        VirtualInventory disabled = new VirtualInventory(1);
        VirtualInventory enabled = new VirtualInventory(1);
        disabled.fireBukkitInventoryEvents(false);
        FakeContext mixedContext = new FakeContext(this.player).link(0, disabled, 0).link(1, enabled, 0);
        mixedContext.cursor = diamonds(4);
        AtomicInteger bukkitCalls = new AtomicInteger();
        AtomicReference<Map<Integer, ItemStack>> payload = new AtomicReference<>();
        ClickSemantics.InteractionGate gate = new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowDrag(ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                payload.set(Map.copyOf(newItems));
                return true;
            }
        };
        ClickSemantics.handleDrag(mixedContext, ClickType.LEFT, List.of(0, 1), gate);

        assertEquals(1, bukkitCalls.get());
        assertEquals(Set.of(0, 1), payload.get().keySet());
        assertEquals(2, payload.get().get(0).getAmount());
        assertEquals(2, payload.get().get(1).getAmount());
        assertEquals(2, ItemUtils.amountOf(disabled.itemAt(0)));
        assertEquals(2, ItemUtils.amountOf(enabled.itemAt(0)));
        VirtualInventory firstDisabled = new VirtualInventory(1);
        VirtualInventory secondDisabled = new VirtualInventory(1);
        firstDisabled.fireBukkitInventoryEvents(false);
        secondDisabled.fireBukkitInventoryEvents(false);
        FakeContext disabledContext = new FakeContext(this.player).link(0, firstDisabled, 0).link(1, secondDisabled, 0);
        disabledContext.cursor = diamonds(4);
        ClickSemantics.handleDrag(disabledContext, ClickType.LEFT, List.of(0, 1), gate);

        assertEquals(1, bukkitCalls.get());
        assertEquals(2, ItemUtils.amountOf(firstDisabled.itemAt(0)));
        assertEquals(2, ItemUtils.amountOf(secondDisabled.itemAt(0)));
        assertTrue(disabledContext.cursor.isEmpty());
    }

    @Test
    void bukkitDispatchDecisionIsNotRepeatedAfterReplan() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory fullHighPriority = new VirtualInventory(1);
        VirtualInventory initialTarget = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        fullHighPriority.setItem(reason(), 0, diamonds(64));
        fullHighPriority.operationPriority(OperationCategory.ADD, 10);
        source.fireBukkitInventoryEvents(false);
        fullHighPriority.fireBukkitInventoryEvents(false);
        AtomicInteger bukkitCalls = new AtomicInteger();
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0)
                .link(1, fullHighPriority, 0)
                .link(2, initialTarget, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                initialTarget.fireBukkitInventoryEvents(false);
                fullHighPriority.fireBukkitInventoryEvents(true);
                fullHighPriority.setItem(reason(), 0, null);
                return true;
            }
        });

        assertEquals(1, bukkitCalls.get());
        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(fullHighPriority.itemAt(0)));
        assertNull(initialTarget.itemAt(0));
    }

    @Test
    void unselectedPhysicalAliasDoesNotEnableBukkitGate() {
        net.minecraft.world.Container container = new net.minecraft.world.SimpleContainer(9);
        org.bukkit.craftbukkit.inventory.CraftInventory chest = new org.bukkit.craftbukkit.inventory.CraftInventory(container);
        chest.setItem(0, diamonds(5));
        ReferencingInventory direct = ReferencingInventory.create(
                chest, org.bukkit.inventory.Inventory::getContents, java.util.function.UnaryOperator.identity(), false);
        ReferencingInventory alias = ReferencingInventory.create(
                chest, org.bukkit.inventory.Inventory::getContents, java.util.function.UnaryOperator.identity(), false);
        direct.fireBukkitInventoryEvents(false);
        AtomicInteger bukkitCalls = new AtomicInteger();
        FakeContext context = new FakeContext(this.player).link(0, direct, 0).link(1, alias, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                bukkitCalls.incrementAndGet();
                return true;
            }
        });

        assertEquals(0, bukkitCalls.get());
        assertNull(chest.getItem(0));
        assertEquals(5, context.cursor.getAmount());
    }

    @Test
    void frozenInventoryRejectsClicksWithoutAnyEvent() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        inventory.setItem(reason(), 0, diamonds(5));
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.frozen(true);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.InteractionGate gate = new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                gateCalls.incrementAndGet();
                return true;
            }
        };
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, gate);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertEquals(0, preCalls.get());
        assertEquals(0, gateCalls.get());
        inventory.frozen(false);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, gate);

        assertNull(inventory.itemAt(0));
        assertEquals(5, context.cursor.getAmount());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void editsWriteToFrozenInventoryIsRejectedWithoutKillingTheClick() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory frozenInventory = new VirtualInventory(1);
        clicked.setItem(reason(), 0, diamonds(5));
        frozenInventory.frozen(true);
        FakeContext context = new FakeContext(this.player).link(0, clicked, 0).link(1, frozenInventory, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                assertFalse(edits.slot(1, diamonds(9)));
                return true;
            }
        });

        assertNull(clicked.itemAt(0));
        assertEquals(5, context.cursor.getAmount());
        assertNull(frozenInventory.itemAt(0));
    }

    @Test
    void shiftNeverMovesIntoFrozenInventory() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory frozenTarget = new VirtualInventory(1);
        VirtualInventory fallback = new VirtualInventory(1);
        frozenTarget.operationPriority(OperationCategory.ADD, 10);
        frozenTarget.frozen(true);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, frozenTarget, 0).link(2, fallback, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertNull(source.itemAt(0));
        assertNull(frozenTarget.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(fallback.itemAt(0)));
    }

    @Test
    void doubleClickNeverCollectsFromFrozenInventory() {
        VirtualInventory clicked = new VirtualInventory(1);
        VirtualInventory frozenSource = new VirtualInventory(1);
        VirtualInventory openSource = new VirtualInventory(1);
        frozenSource.setItem(reason(), 0, diamonds(30));
        frozenSource.frozen(true);
        openSource.setItem(reason(), 0, diamonds(20));
        FakeContext context = new FakeContext(this.player)
                .link(0, clicked, 0).link(1, frozenSource, 0).link(2, openSource, 0);
        context.cursor = diamonds(10);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 0);

        assertEquals(30, ItemUtils.amountOf(frozenSource.itemAt(0)));
        assertNull(openSource.itemAt(0));
        assertEquals(30, context.cursor.getAmount());
    }

    @Test
    void hotbarSwapExchangesContentAcrossInventoryKinds() {
        net.minecraft.world.Container container = new net.minecraft.world.SimpleContainer(9);
        org.bukkit.craftbukkit.inventory.CraftInventory chest = new org.bukkit.craftbukkit.inventory.CraftInventory(container);
        chest.setItem(0, new ItemStack(Material.STONE, 32));
        ReferencingInventory hotbar = ReferencingInventory.create(
                chest,
                org.bukkit.inventory.Inventory::getContents,
                java.util.function.UnaryOperator.identity(),
                false
        );
        VirtualInventory shared = new VirtualInventory(1);
        shared.setItem(reason(), 0, new ItemStack(Material.STONE, 10));
        FakeContext context = new FakeContext(this.player).link(0, shared, 0).hotbar(0, hotbar, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(32, ItemUtils.amountOf(shared.itemAt(0)));
        assertEquals(10, ItemUtils.amountOf(chest.getItem(0)));
    }

    @Test
    void hotbarSwapExchangesContentBetweenOwnStateInventories() {
        VirtualInventory upper = new VirtualInventory(1);
        VirtualInventory hotbar = new VirtualInventory(1);
        upper.setItem(reason(), 0, diamonds(5));
        hotbar.setItem(reason(), 0, new ItemStack(Material.EMERALD, 2));
        FakeContext context = new FakeContext(this.player).link(0, upper, 0).hotbar(0, hotbar, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(Material.EMERALD, upper.itemAt(0).getType());
        assertEquals(2, ItemUtils.amountOf(upper.itemAt(0)));
        assertEquals(Material.DIAMOND, hotbar.itemAt(0).getType());
        assertEquals(5, ItemUtils.amountOf(hotbar.itemAt(0)));
    }

    @Test
    void hotbarSwapExchangesContentBetweenContainers() {
        net.minecraft.world.Container upperContainer = new net.minecraft.world.SimpleContainer(9);
        net.minecraft.world.Container hotbarContainer = new net.minecraft.world.SimpleContainer(9);
        org.bukkit.craftbukkit.inventory.CraftInventory upperChest = new org.bukkit.craftbukkit.inventory.CraftInventory(upperContainer);
        org.bukkit.craftbukkit.inventory.CraftInventory hotbarChest = new org.bukkit.craftbukkit.inventory.CraftInventory(hotbarContainer);
        upperChest.setItem(0, diamonds(5));
        hotbarChest.setItem(0, new ItemStack(Material.EMERALD, 2));
        ReferencingInventory upper = ReferencingInventory.create(
                upperChest, org.bukkit.inventory.Inventory::getContents, java.util.function.UnaryOperator.identity(), false);
        ReferencingInventory hotbar = ReferencingInventory.create(
                hotbarChest, org.bukkit.inventory.Inventory::getContents, java.util.function.UnaryOperator.identity(), false);
        FakeContext context = new FakeContext(this.player).link(0, upper, 0).hotbar(0, hotbar, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(Material.EMERALD, upperChest.getItem(0).getType());
        assertEquals(2, ItemUtils.amountOf(upperChest.getItem(0)));
        assertEquals(Material.DIAMOND, hotbarChest.getItem(0).getType());
        assertEquals(5, ItemUtils.amountOf(hotbarChest.getItem(0)));
    }

    @Test
    void hotbarSwapRejectsFrozenHotbarEnd() {
        VirtualInventory upper = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(9);
        upper.setItem(reason(), 0, diamonds(5));
        storage.setItem(reason(), 0, new ItemStack(Material.EMERALD, 3));
        storage.frozen(true);
        FakeContext context = new FakeContext(this.player).link(0, upper, 0).hotbar(0, storage, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(5, ItemUtils.amountOf(upper.itemAt(0)));
        assertEquals(Material.EMERALD, storage.itemAt(0).getType());
    }

    @Test
    void dragSkipsFrozenInventorySlots() {
        VirtualInventory frozenInventory = new VirtualInventory(1);
        VirtualInventory openInventory = new VirtualInventory(1);
        frozenInventory.frozen(true);
        FakeContext context = new FakeContext(this.player).link(0, frozenInventory, 0).link(1, openInventory, 0);
        context.cursor = diamonds(8);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1));

        assertNull(frozenInventory.itemAt(0));
        assertEquals(8, ItemUtils.amountOf(openInventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
    }

    @Test
    void programWritesIgnoreInventoryFrozen() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        inventory.frozen(true);

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(reason(), 0, diamonds(5)));
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(1, postCalls.get());
    }

    @Test
    void frozenBackstopCancelsPlayerReasonWrites() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger preCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.frozen(true);
        UpdateReason playerReason = new PlayerUpdateReason.Click(this.player, ClickType.LEFT, -1);

        assertInstanceOf(TransactionResult.Cancelled.class, inventory.trySetItem(playerReason, 0, diamonds(5)));
        assertNull(inventory.itemAt(0));
        assertEquals(0, preCalls.get());
    }

    @Test
    void cancelledDragLeavesViewerStorageUntouched() {
        VirtualInventory inventory = new VirtualInventory(1);
        VirtualInventory storage = new VirtualInventory(36);
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        FakeContext context = new FakeContext(this.player)
                .link(0, inventory, 0).link(30, storage, 0);
        context.cursor = diamonds(10);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 30));

        assertNull(inventory.itemAt(0));
        assertNull(storage.itemAt(0));
        assertEquals(10, context.cursor.getAmount());
    }

    @Test
    void bukkitDragCancellationStopsBeforePreAndCommit() {
        VirtualInventory inventory = new VirtualInventory(2);
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        context.cursor = diamonds(8);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1), new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowDrag(ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
                gateCalls.incrementAndGet();
                return false;
            }
        });

        assertEquals(1, gateCalls.get());
        assertEquals(0, preCalls.get());
        assertTrue(inventory.isEmpty());
        assertEquals(8, context.cursor.getAmount());
    }

    @Test
    void bukkitClickCursorWriteReachesTheCommittedCursor() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                assertTrue(edits.cursor(diamonds(2)));
                return true;
            }
        });

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(target.itemAt(0)));
        assertEquals(2, context.cursor.getAmount());
    }

    @Test
    void bukkitDragCursorWriteOverridesThePlannedCursor() {
        VirtualInventory inventory = new VirtualInventory(2);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        context.cursor = diamonds(8);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1), new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowDrag(ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
                assertTrue(newCursor.isEmpty());
                assertTrue(edits.cursor(diamonds(3)));
                return true;
            }
        });

        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(3, context.cursor.getAmount());
    }

    @Test
    void cancelledBukkitEventDropsItsCursorWriteWithTheCandidate() {
        VirtualInventory inventory = new VirtualInventory(2);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        context.cursor = diamonds(8);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1), new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowDrag(ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
                edits.cursor(diamonds(3));
                return false;
            }
        });

        assertTrue(inventory.isEmpty());
        assertEquals(8, context.cursor.getAmount());
    }

    @Test
    void frozenSlotClickNeverReachesAnyListener() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).freeze(0);
        AtomicBoolean dispatched = new AtomicBoolean();

        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                dispatched.set(true);
                return true;
            }
        }));

        assertFalse(dispatched.get());
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(context.dirty.contains(0));
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void cursorWriteDoesNotDisarmTheConcurrentCursorCheck() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                edits.cursor(diamonds(2));
                context.cursor = diamonds(9);
                return true;
            }
        });

        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(target.itemAt(0));
        assertEquals(9, context.cursor.getAmount());
        assertEquals(1, this.reportedWarnings.size());
    }

    @Test
    void disabledWarningsSuppressTheReportButNotTheDiscard() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        SparrowUI.getInstance().warningsEnabled(false);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                edits.cursor(diamonds(2));
                context.cursor = diamonds(9);
                return true;
            }
        });

        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(target.itemAt(0));
        assertEquals(9, context.cursor.getAmount());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void bukkitSlotWriteBecomesThePlanningInputOfAParticipatingRoot() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                assertTrue(edits.slot(0, diamonds(3)));
                return true;
            }
        });

        assertNull(source.itemAt(0));
        assertEquals(3, ItemUtils.amountOf(target.itemAt(0)));
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void bukkitSlotWriteExpandsTheTransactionToAnUntouchedRoot() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        VirtualInventory bonus = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        AtomicInteger bonusPreCalls = new AtomicInteger();
        AtomicReference<InventoryPostUpdateEvent> bonusPost = new AtomicReference<>();
        bonus.subscribePreUpdate(event -> bonusPreCalls.incrementAndGet());
        bonus.subscribePostUpdate(bonusPost::set);
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0).link(2, bonus, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                assertTrue(edits.slot(2, diamonds(4)));
                return true;
            }
        });

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(target.itemAt(0)));
        assertEquals(4, ItemUtils.amountOf(bonus.itemAt(0)));
        assertEquals(1, bonusPreCalls.get());
        List<SlotChange> bonusChanges = bonusPost.get().slotChanges();

        assertEquals(1, bonusChanges.size());
        assertEquals(0, bonusChanges.get(0).slot());
    }

    @Test
    void sparrowClickEventWritesTheFinalValueOntoTheReplannedResult() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        AtomicInteger sparrowCalls = new AtomicInteger();
        source.subscribeClick(event -> {
            sparrowCalls.incrementAndGet();

            assertTrue(event.edits().slot(0, diamonds(7)));
        });
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(0, diamonds(3));
                return true;
            }
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                return ClickSemantics.dispatchClickEvent(link.inventory(), link.slot(), context.viewer(), ClickType.SHIFT_LEFT, -1, action, edits);
            }
        });

        assertEquals(1, sparrowCalls.get());
        assertEquals(7, ItemUtils.amountOf(source.itemAt(0)));
        assertEquals(3, ItemUtils.amountOf(target.itemAt(0)));
    }

    @Test
    void cancelledEventDropsItsSlotWriteWithTheCandidate() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        VirtualInventory bonus = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0).link(2, bonus, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(0, diamonds(3));
                edits.slot(2, diamonds(4));
                return false;
            }
        });

        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(target.itemAt(0));
        assertTrue(bonus.isEmpty());
    }

    @Test
    void slotWriteWithoutAnyInventoryBehindItIsDiscarded() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0).freeze(2);
        AtomicBoolean itemSlot = new AtomicBoolean(true);
        AtomicBoolean frozenSlot = new AtomicBoolean(true);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                itemSlot.set(edits.slot(9, diamonds(4)));
                frozenSlot.set(edits.slot(2, diamonds(4)));
                return true;
            }
        });

        assertFalse(itemSlot.get());
        assertFalse(frozenSlot.get());
        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(target.itemAt(0)));
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void unplannedClickStillMergesGateWrites() {
        VirtualInventory inventory = new VirtualInventory(2);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        AtomicBoolean accepted = new AtomicBoolean();

        assertTrue(ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                accepted.set(edits.slot(1, diamonds(4)));
                return true;
            }
        }));

        assertTrue(accepted.get());
        assertNull(inventory.itemAt(0));
        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(1)));
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void unplannedClickBecomesARealDropAfterASlotWrite() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.DROP, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                assertTrue(edits.slot(0, diamonds(4)));
                return true;
            }
        });

        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(1, context.drops.size());
        assertEquals(1, context.drops.get(0).getAmount());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void unplannedClickAppliesCursorWriteWithoutAnyTransaction() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setAccessRule(placement -> false);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.cursor(diamonds(3));
                return true;
            }
        });

        assertEquals(3, context.cursor.getAmount());
        assertNull(inventory.itemAt(0));
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void unplannedSlotWriteAndCursorReplacementBothFeedTheReplan() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(0, diamonds(4));
                context.cursor = diamonds(1);
                return true;
            }
        });

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void candidateReplannedAwayStillReachesTheSparrowClickEvent() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        AtomicReference<InventoryAction> sparrowAction = new AtomicReference<>();
        AtomicInteger sparrowCalls = new AtomicInteger();
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(0, null);
                return true;
            }
            @Override
            public boolean allowInventoryClick(ClickSemantics.LinkedSlot link, InventoryAction action, InteractionEdits edits) {
                sparrowCalls.incrementAndGet();
                sparrowAction.set(action);
                return true;
            }
        });

        assertEquals(1, sparrowCalls.get());
        assertEquals(InventoryAction.NOTHING, sparrowAction.get());
        assertNull(inventory.itemAt(0));
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void shiftClickCommitsDespiteADirectCursorReplacement() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                context.cursor = diamonds(1);
                return true;
            }
        });

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(target.itemAt(0)));
        assertEquals(1, context.cursor.getAmount());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void eventCursorWriteOutranksADirectCursorReplacementInTheBukkitGate() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(5);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.cursor(diamonds(2));
                context.cursor = diamonds(9);
                return true;
            }
        });

        assertEquals(2, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void concurrentRootWriteDropsTheClickWithoutAnyWarning() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                target.setItem(reason(), 0, new ItemStack(Material.EMERALD));
                return true;
            }
        });

        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertEquals(Material.EMERALD, target.itemAt(0).getType());
        assertEquals(0, this.reportedWarnings.size());
    }

    @Test
    void singleSlotClickSurvivesAConcurrentWriteToAnotherPage() {
        VirtualInventory pageOne = new VirtualInventory(1);
        VirtualInventory pageTwo = new VirtualInventory(1);
        pageOne.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, pageOne, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                pageTwo.setItem(reason(), 0, new ItemStack(Material.EMERALD, 3));
                return true;
            }
        });

        assertNull(pageOne.itemAt(0));
        assertEquals(5, context.cursor.getAmount());
        assertEquals(3, ItemUtils.amountOf(pageTwo.itemAt(0)));
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void singleSlotClickReplansWhenItsOwnPageIsWrittenConcurrently() {
        VirtualInventory page = new VirtualInventory(1);
        page.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, page, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                page.setItem(reason(), 0, new ItemStack(Material.EMERALD, 3));
                return true;
            }
        });

        assertNull(page.itemAt(0));
        assertEquals(Material.EMERALD, context.cursor.getType());
        assertEquals(3, context.cursor.getAmount());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void directCursorReplacementReplansTheClickAgainstTheNewCursor() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(5);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                context.cursor = new ItemStack(Material.DIRT, 1);
                return true;
            }
        });

        assertEquals(Material.DIRT, inventory.itemAt(0).getType());
        assertEquals(1, inventory.itemAt(0).getAmount());
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void orthogonalWriteRidesAlongWithTheReplannedCandidate() {
        VirtualInventory deposit = new VirtualInventory(1);
        VirtualInventory bonus = new VirtualInventory(1);
        bonus.setItem(reason(), 0, diamonds(1));
        FakeContext context = new FakeContext(this.player).link(0, deposit, 0).link(1, bonus, 0);
        context.cursor = diamonds(5);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(1, diamonds(3));
                context.cursor = new ItemStack(Material.DIRT, 2);
                return true;
            }
        });

        assertEquals(Material.DIRT, deposit.itemAt(0).getType());
        assertEquals(2, deposit.itemAt(0).getAmount());
        assertEquals(3, ItemUtils.amountOf(bonus.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void orthogonalWriteCommitsAloneWhenTheReplanFindsNothingToDo() {
        VirtualInventory deposit = new VirtualInventory(1);
        VirtualInventory bonus = new VirtualInventory(1);
        bonus.setItem(reason(), 0, diamonds(1));
        deposit.setAccessRule(placement -> placement.addedItem().getType() == Material.DIRT);
        FakeContext context = new FakeContext(this.player).link(0, deposit, 0).link(1, bonus, 0);
        context.cursor = new ItemStack(Material.DIRT, 1);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(1, diamonds(2));
                context.cursor = new ItemStack(Material.NETHERITE_SCRAP, 1);
                return true;
            }
        });

        assertNull(deposit.itemAt(0));
        assertEquals(2, ItemUtils.amountOf(bonus.itemAt(0)));
        assertEquals(Material.NETHERITE_SCRAP, context.cursor.getType());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void writeOntoTheReplannedTargetBecomesThePlanningInput() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        context.cursor = diamonds(5);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(0, diamonds(4));
                context.cursor = new ItemStack(Material.DIRT, 1);
                return true;
            }
        });

        assertEquals(Material.DIRT, inventory.itemAt(0).getType());
        assertEquals(Material.DIAMOND, context.cursor.getType());
        assertEquals(4, context.cursor.getAmount());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void cancelledEventDropsItsCursorOverlayWithTheCandidate() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        context.cursor = diamonds(2);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.slot(0, diamonds(3));
                edits.cursor(diamonds(9));
                return false;
            }
        });

        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(target.itemAt(0));
        assertEquals(2, context.cursor.getAmount());
    }

    @Test
    void preSeesTheRealBaselineInsteadOfTheOverlayValue() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        AtomicReference<InventoryPreUpdateEvent> sourcePre = new AtomicReference<>();
        source.subscribePreUpdate(sourcePre::set);
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                assertTrue(edits.slot(0, diamonds(3)));
                return true;
            }
        });
        List<SlotChange> changes = sourcePre.get().slotChanges();

        assertEquals(1, changes.size());
        assertEquals(5, ItemUtils.amountOf(changes.get(0).unsafeBefore()));
        assertNull(changes.get(0).unsafeAfter());
    }

    @Test
    void cursorWriteRidesAlongWithAReplannedShiftCandidate() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory full = new VirtualInventory(1);
        VirtualInventory fallback = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        full.setItem(reason(), 0, diamonds(64));
        full.operationPriority(OperationCategory.ADD, 10);
        FakeContext context = new FakeContext(this.player)
                .link(0, source, 0)
                .link(1, full, 0)
                .link(2, fallback, 0);
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                edits.cursor(diamonds(2));
                full.setItem(reason(), 0, null);
                return true;
            }
        });

        assertNull(source.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(full.itemAt(0)));
        assertNull(fallback.itemAt(0));
        assertEquals(2, context.cursor.getAmount());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void dragReplansAfterAContainerWriteInsideTheBukkitGate() {
        VirtualInventory inventory = new VirtualInventory(3);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1).link(2, inventory, 2);
        context.cursor = diamonds(8);
        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1), new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowDrag(ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
                inventory.setItem(reason(), 2, new ItemStack(Material.EMERALD));
                return true;
            }
        });

        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(Material.EMERALD, inventory.itemAt(2).getType());
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void unplannedClickBecomesARealPlacementAfterACursorReplacement() {
        VirtualInventory inventory = new VirtualInventory(1);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0);
        ClickSemantics.handleClick(context, ClickType.LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                context.cursor = diamonds(5);
                return true;
            }
        });

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertTrue(context.cursor.isEmpty());
        assertTrue(this.reportedWarnings.isEmpty());
    }

    @Test
    void growingTheTransactionToManyRootsCommitsEveryRootSilently() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(reason(), 0, diamonds(5));
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);
        List<VirtualInventory> extras = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            VirtualInventory extra = new VirtualInventory(1);
            extras.add(extra);
            context.link(2 + i, extra, 0);
        }
        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0, null, -1, () -> {}, new ClickSemantics.InteractionGate() {
            @Override
            public boolean allowClick(InventoryAction action, InteractionEdits edits) {
                for (int i = 0; i < extras.size(); i++) {
                    assertTrue(edits.slot(2 + i, diamonds(1)));
                }
                return true;
            }
        });
        for (int i = 0; i < extras.size(); i++) {
            assertEquals(1, ItemUtils.amountOf(extras.get(i).itemAt(0)));
        }

        assertEquals(0, this.reportedWarnings.size());
    }

    @Test
    void randomizedClickSequencesConserveQuantity() {
        java.util.Random random = new java.util.Random(20260726L);
        VirtualInventory first = new VirtualInventory(3);
        VirtualInventory second = new VirtualInventory(2);
        VirtualInventory storage = new VirtualInventory(2);
        FakeContext context = new FakeContext(this.player)
                .link(0, first, 0).link(1, first, 1).link(2, first, 2)
                .link(3, second, 0).link(4, second, 1)
                .link(40, storage, 0).link(41, storage, 1);
        storage.setItem(reason(), 0, diamonds(30));
        first.setItem(reason(), 0, diamonds(20));
        int expected = 50;
        ClickType[] singles = {ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.DROP, ClickType.DOUBLE_CLICK};
        for (int step = 0; step < 80; step++) {
            if (random.nextInt(5) == 0) {
                ClickSemantics.handleDrag(context, random.nextBoolean() ? ClickType.LEFT : ClickType.RIGHT,
                        List.of(random.nextInt(6), random.nextInt(6)));
            } else {
                ClickSemantics.handleClick(context, singles[random.nextInt(singles.length)], -1, random.nextInt(6));
            }
            int total = totalOf(first) + totalOf(second) + totalOf(storage)
                    + context.cursor.getAmount() + totalDrops(context);

            assertEquals(expected, total, "conservation broken at step " + step);
        }
    }

    private static final class ThrowingWriteStorage implements ExternalStorage {
        private final RuntimeException failure;
        private ThrowingWriteStorage(RuntimeException failure) {
            this.failure = failure;
        }
        @Override
        public int size() {
            return 1;
        }
        @Override
        public ItemStack read(int slot) {
            return null;
        }
        @Override
        public void write(int slot, ItemStack item) {
            throw this.failure;
        }
        @Override
        public int maxStackSize(int slot) {
            return 99;
        }
    }

    private static final class CountingStorage implements ExternalStorage {
        private final ItemStack[] items = new ItemStack[1];
        private int comparisons;
        @Override
        public int size() {
            return 1;
        }
        @Override
        public ItemStack read(int slot) {
            return this.items[slot];
        }
        @Override
        public @Nullable ItemStack @NotNull [] readAll() {
            return this.items.clone();
        }
        @Override
        public void write(int slot, ItemStack item) {
            this.items[slot] = item;
        }
        @Override
        public boolean contentEquals(int slot, @Nullable ItemStack expected) {
            this.comparisons++;
            return ExternalStorage.super.contentEquals(slot, expected);
        }
        @Override
        public int maxStackSize(int slot) {
            return 99;
        }
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    @Test
    void ownershipRuleBlocksEveryDirectExtractionPathWithoutPre() {
        for (ClickType type : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.MIDDLE)) {
            VirtualInventory owned = new VirtualInventory(new ItemStack[]{diamonds(3)});
            VirtualInventory other = new VirtualInventory(new ItemStack[]{new ItemStack(Material.EMERALD, 2)});
            AtomicInteger pre = new AtomicInteger();
            owned.setAccessRule(access -> access.player() != this.player);
            owned.setAccessRule(0, access -> true);
            owned.subscribePreUpdate(event -> pre.incrementAndGet());
            FakeContext context = new FakeContext(this.player).link(0, owned, 0).link(1, other, 0).hotbar(0, other, 0);
            context.offhandItem = new ItemStack(Material.GOLD_INGOT, 2);
            ClickSemantics.handleClick(context, type, 0, 0);

            assertEquals(diamonds(3), owned.itemAt(0), type.name());
            assertEquals(new ItemStack(Material.EMERALD, 2), other.itemAt(0), type.name());
            assertEquals(new ItemStack(Material.GOLD_INGOT, 2), context.offhandItem, type.name());
            assertTrue(context.cursor.isEmpty(), type.name());
            assertTrue(context.drops.isEmpty(), type.name());
            assertEquals(0, pre.get(), type.name());
        }
    }

    @Test
    void doubleClickSkipsInventoriesWhoseRemovalRuleRejectsTheViewer() {
        VirtualInventory denied = new VirtualInventory(new ItemStack[]{diamonds(5)});
        VirtualInventory allowed = new VirtualInventory(new ItemStack[]{null, diamonds(3)});
        denied.setAccessRule(access -> false);
        denied.operationPriority(OperationCategory.COLLECT, 100);
        FakeContext context = new FakeContext(this.player).link(0, allowed, 0).link(1, denied, 0).link(2, allowed, 1);
        context.cursor = diamonds(1);
        ClickSemantics.handleClick(context, ClickType.DOUBLE_CLICK, -1, 0);

        assertEquals(diamonds(5), denied.itemAt(0));
        assertNull(allowed.itemAt(1));
        assertEquals(diamonds(4), context.cursor);
    }

    @Test
    void shiftChecksTheActualSourceRemovalAfterTargetCapacityIsKnown() {
        VirtualInventory source = new VirtualInventory(new ItemStack[]{diamonds(8)});
        VirtualInventory target = new VirtualInventory(1);
        target.setMaxStackSize(0, 3);
        AtomicInteger removed = new AtomicInteger();
        source.setAccessRule(access -> {
            removed.set(access.removedAmount());
            return access.removedAmount() <= 3;
        });
        FakeContext context = new FakeContext(this.player).link(0, source, 0).link(1, target, 0);

        ClickSemantics.handleClick(context, ClickType.SHIFT_LEFT, -1, 0);

        assertEquals(3, removed.get());
        assertEquals(diamonds(5), source.itemAt(0));
        assertEquals(diamonds(3), target.itemAt(0));
    }

    @Test
    void numberKeyChecksBothAddedAndRemovedItemsOnTheHotbarSide() {
        VirtualInventory source = new VirtualInventory(new ItemStack[]{diamonds(3)});
        VirtualInventory hotbar = new VirtualInventory(new ItemStack[]{new ItemStack(Material.EMERALD, 2)});
        AtomicReference<AccessContext> observed = new AtomicReference<>();
        hotbar.setAccessRule(access -> {
            observed.set(access);
            return !access.isRemove();
        });
        FakeContext context = new FakeContext(this.player).link(0, source, 0).hotbar(0, hotbar, 0);
        ClickSemantics.handleClick(context, ClickType.NUMBER_KEY, 0, 0);

        assertEquals(diamonds(3), observed.get().addedItem());
        assertEquals(new ItemStack(Material.EMERALD, 2), observed.get().removedItem());
        assertEquals(diamonds(3), source.itemAt(0));
        assertEquals(new ItemStack(Material.EMERALD, 2), hotbar.itemAt(0));
    }

    @Test
    void dragRechecksActualAmountsAfterRedistribution() {
        VirtualInventory inventory = new VirtualInventory(2);
        List<Integer> amounts = new ArrayList<>();
        inventory.setAccessRule(0, access -> {
            amounts.add(access.addedAmount());
            return access.addedAmount() <= 2;
        });
        inventory.setAccessRule(1, access -> false);
        FakeContext context = new FakeContext(this.player).link(0, inventory, 0).link(1, inventory, 1);
        context.cursor = diamonds(4);

        ClickSemantics.handleDrag(context, ClickType.LEFT, List.of(0, 1));

        assertEquals(List.of(2, 4), amounts);
        assertTrue(inventory.isEmpty());
        assertEquals(diamonds(4), context.cursor);
    }

    private static ItemStack bundle(ItemStack... items) {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(items));
        bundle.setItemMeta(meta);
        return bundle;
    }

    private static int[] reorderPlayerStorage(int[] slots) {
        int[] reordered = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            reordered[i] = (slots[i] + 9) % 36;
        }
        return reordered;
    }

    private static int totalOf(SparrowInventory inventory) {
        int total = 0;
        ItemStack[] snapshot = inventory.snapshot();
        for (int i = 0; i < snapshot.length; i++) {
            total += ItemUtils.amountOf(snapshot[i]);
        }
        return total;
    }

    private static int totalDrops(FakeContext context) {
        int total = 0;
        for (int i = 0; i < context.drops.size(); i++) {
            total += context.drops.get(i).getAmount();
        }
        return total;
    }

    private static final class FakeContext implements ClickSemantics.Context {
        private final Player viewer;
        private final Map<Integer, ClickSemantics.LinkedSlot> links = new HashMap<>();
        private final Map<Integer, ClickSemantics.LinkedSlot> hotbarLinks = new HashMap<>();
        private final Set<Integer> frozenSlots = new HashSet<>();
        private final Set<Integer> backgroundSlots = new HashSet<>();
        private final LinkedHashSet<SparrowInventory> participants = new LinkedHashSet<>();
        private final Set<SparrowInventory> wholeVisible = new HashSet<>();
        private ItemStack cursor = ItemStack.empty();
        private ItemStack offhandItem;
        private final List<ItemStack> drops = new ArrayList<>();
        private final Set<Integer> dirty = new HashSet<>();
        private FakeContext(Player viewer) {
            this.viewer = viewer;
        }
        private FakeContext link(int windowSlot, SparrowInventory inventory, int slot) {
            this.links.put(windowSlot, new ClickSemantics.LinkedSlot(inventory, slot));
            this.participants.add(inventory);
            return this;
        }
        private FakeContext inventory(SparrowInventory inventory) {
            this.participants.add(inventory);
            this.wholeVisible.add(inventory);
            return this;
        }
        private FakeContext hotbar(int hotbarButton, SparrowInventory inventory, int slot) {
            this.hotbarLinks.put(hotbarButton, new ClickSemantics.LinkedSlot(inventory, slot));
            return this.inventory(inventory);
        }
        private FakeContext freeze(int windowSlot) {
            this.frozenSlots.add(windowSlot);
            return this;
        }
        private FakeContext background(int windowSlot) {
            this.backgroundSlots.add(windowSlot);
            return this;
        }
        @Override
        @NotNull
        public Player viewer() {
            return this.viewer;
        }
        @Override
        @Nullable
        public ClickSemantics.LinkedSlot linkAt(int windowSlot) {
            return this.links.get(windowSlot);
        }
        @Override
        public boolean frozenAt(int windowSlot) {
            return this.frozenSlots.contains(windowSlot);
        }
        @Override
        public boolean displayedEmptyAt(int windowSlot) {
            return !this.backgroundSlots.contains(windowSlot);
        }
        @Override
        @Nullable
        public ClickSemantics.LinkedSlot hotbarLink(int hotbarButton) {
            return this.hotbarLinks.get(hotbarButton);
        }
        @Override
        @NotNull
        public List<ClickSemantics.LinkedInventory> linkedInventories() {
            LinkedHashMap<SparrowInventory, BitSet> visible = new LinkedHashMap<>();
            for (SparrowInventory inventory : this.participants) {
                BitSet slots = new BitSet(inventory.size());
                if (this.wholeVisible.contains(inventory)) {
                    slots.set(0, inventory.size());
                }
                visible.put(inventory, slots);
            }
            this.links.forEach((windowSlot, link) -> {
                if (!this.frozenSlots.contains(windowSlot)) {
                    visible.get(link.inventory()).set(link.slot());
                }
            });
            List<ClickSemantics.LinkedInventory> linked = new ArrayList<>(visible.size());
            visible.forEach((inventory, slots) -> {
                if (!slots.isEmpty()) {
                    linked.add(new ClickSemantics.LinkedInventory(inventory, slots));
                }
            });
            return List.copyOf(linked);
        }
        @Override
        @NotNull
        public ItemStack cursor() {
            return this.cursor.clone();
        }
        @Override
        public void cursor(@NotNull ItemStack cursor) {
            this.cursor = ItemUtils.copyOrEmpty(cursor);
        }
        @Override
        @Nullable
        public ItemStack offhand() {
            return this.offhandItem == null ? null : this.offhandItem.clone();
        }
        @Override
        public void offhand(@Nullable ItemStack item) {
            this.offhandItem = item;
        }
        @Override
        public void drop(@NotNull ItemStack item) {
            this.drops.add(item);
        }
        @Override
        public void markDirty(int windowSlot) {
            this.dirty.add(windowSlot);
        }
    }
}
