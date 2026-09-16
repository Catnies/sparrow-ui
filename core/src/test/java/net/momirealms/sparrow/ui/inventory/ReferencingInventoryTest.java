package net.momirealms.sparrow.ui.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryTransactions;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencingInventoryTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void mirrorsInitialContentsAndWritesThrough() {
        org.bukkit.inventory.Inventory chest = new CraftInventory(new SimpleContainer(9));
        chest.setItem(2, diamonds(5));
        ReferencingInventory referencing = fromContents(chest);

        assertEquals(9, referencing.size());
        assertEquals(5, ItemUtils.amountOf(referencing.itemAt(2)));
        assertInstanceOf(TransactionResult.Committed.class, referencing.trySetItem(reason(), 0, diamonds(3)));
        assertEquals(3, ItemUtils.amountOf(chest.getItem(0)));
        AddResult add = referencing.tryAdd(reason(), diamonds(7));

        assertInstanceOf(TransactionResult.Committed.class, add.result());
        assertEquals(0, add.remaining());
        assertEquals(3 + 5 + 7, totalDiamonds(chest));
    }

    @Test
    void refreshAbsorbsExternalChangesWithPostOnlyEvents() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory referencing = fromContents(chest);
        List<UpdateReason> preReasons = Collections.synchronizedList(new ArrayList<>());
        List<UpdateReason> postReasons = Collections.synchronizedList(new ArrayList<>());
        referencing.subscribePreUpdate(event -> preReasons.add(event.reason()));
        referencing.subscribePostUpdate(event -> postReasons.add(event.reason()));
        chest.setItem(4, diamonds(6));
        referencing.refresh();

        assertEquals(6, ItemUtils.amountOf(referencing.itemAt(4)));
        assertEquals(List.of(), List.copyOf(preReasons));
        assertEquals(List.of(UpdateReason.External.INSTANCE), List.copyOf(postReasons));
        referencing.refresh();

        assertEquals(1, postReasons.size());
    }

    @Test
    void externalReconcileKeepsContainerItemInstances() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        AtomicInteger containerWrites = new AtomicInteger();
        ReferencingInventory referencing = fromContents(countingWrites(chest, containerWrites));
        chest.setItem(0, unbreakable(diamonds(1)));
        referencing.refresh();

        assertEquals(1, ItemUtils.amountOf(referencing.itemAt(0)));
        assertEquals(0, containerWrites.get());
        chest.setItem(0, diamonds(4));
        referencing.refresh();

        assertEquals(4, ItemUtils.amountOf(referencing.itemAt(0)));
        assertEquals(0, containerWrites.get());
    }

    @Test
    void containerWriteBackSkipsEqualContent() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        chest.setItem(0, diamonds(5));
        AtomicInteger containerWrites = new AtomicInteger();
        ReferencingInventory referencing = fromContents(countingWrites(chest, containerWrites));

        assertInstanceOf(TransactionResult.Committed.class, referencing.trySetItem(reason(), 0, diamonds(5)));
        assertEquals(0, containerWrites.get());
        assertEquals(5, ItemUtils.amountOf(chest.getItem(0)));
        assertInstanceOf(TransactionResult.Committed.class, referencing.trySetItem(reason(), 0, new ItemStack(Material.EMERALD, 2)));
        assertEquals(1, containerWrites.get());
        assertEquals(Material.EMERALD, chest.getItem(0).getType());
    }

    @Test
    void countOnlyChangeReplacesTheContainerInstance() {
        Container container = new SimpleContainer(9);
        CraftInventory chest = new CraftInventory(container);
        chest.setItem(0, diamonds(5));
        net.minecraft.world.item.ItemStack handle = container.getItem(0);
        ReferencingInventory referencing = fromContents(chest);
        AddResult added = referencing.tryAdd(reason(), diamonds(3));

        assertInstanceOf(TransactionResult.Committed.class, added.result());
        assertEquals(0, added.remaining());
        assertNotSame(handle, container.getItem(0));
        assertEquals(8, ItemUtils.amountOf(chest.getItem(0)));
    }

    @Test
    void equalContentWriteKeepsTheContainerInstance() {
        Container container = new SimpleContainer(9);
        CraftInventory chest = new CraftInventory(container);
        chest.setItem(0, diamonds(5));
        net.minecraft.world.item.ItemStack handle = container.getItem(0);
        ReferencingInventory referencing = fromContents(chest);

        assertInstanceOf(TransactionResult.Committed.class, referencing.trySetItem(reason(), 0, diamonds(5)));
        assertSame(handle, container.getItem(0));
        assertEquals(5, ItemUtils.amountOf(chest.getItem(0)));
    }

    @Test
    void dissimilarReplacementWritesNewContainerInstance() {
        Container container = new SimpleContainer(9);
        CraftInventory chest = new CraftInventory(container);
        chest.setItem(0, diamonds(5));
        net.minecraft.world.item.ItemStack handle = container.getItem(0);
        ReferencingInventory referencing = fromContents(chest);

        assertInstanceOf(TransactionResult.Committed.class, referencing.trySetItem(reason(), 0, new ItemStack(Material.EMERALD, 2)));
        assertNotSame(handle, container.getItem(0));
        assertEquals(Material.EMERALD, chest.getItem(0).getType());
        assertEquals(2, ItemUtils.amountOf(chest.getItem(0)));
    }

    @Test
    void planningBasisSharesContainerHandles() {
        Container container = new SimpleContainer(9);
        CraftInventory chest = new CraftInventory(container);
        chest.setItem(0, diamonds(5));
        ReferencingInventory referencing = fromContents(chest);

        assertSame(container.getItem(0).getBukkitStack(), referencing.openPlan().planned()[0]);
    }

    @Test
    void reentrantPreCommitConflictsTheOuterTransaction() {
        Container container = new SimpleContainer(9);
        CraftInventory chest = new CraftInventory(container);
        ReferencingInventory referencing = fromContents(chest);
        AtomicInteger preCalls = new AtomicInteger();
        referencing.subscribePreUpdate(event -> {
            if (preCalls.incrementAndGet() == 1) {
                assertInstanceOf(TransactionResult.Committed.class,
                        referencing.trySetItem(reason(), 1, new ItemStack(Material.EMERALD, 2)));
            }
        });
        TransactionResult result = referencing.trySetItem(reason(), 0, diamonds(5));

        assertInstanceOf(TransactionResult.Conflicted.class, result);
        assertNull(chest.getItem(0));
        assertEquals(2, ItemUtils.amountOf(chest.getItem(1)));
    }

    @Test
    void liveApplyDoesNotEchoAsExternalChange() {
        Container container = new SimpleContainer(9);
        CraftInventory chest = new CraftInventory(container);
        ReferencingInventory referencing = fromContents(chest);
        AtomicInteger postEvents = new AtomicInteger();
        referencing.subscribePostUpdate(event -> postEvents.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, referencing.trySetItem(reason(), 0, diamonds(5)));
        assertEquals(1, postEvents.get());
        referencing.refresh();

        assertEquals(1, postEvents.get());
    }

    @Test
    void externalRefreshBypassesAccessRules() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory referencing = fromContents(chest);
        AtomicInteger ruleCalls = new AtomicInteger();
        referencing.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        chest.setItem(0, diamonds(3));
        referencing.refresh();

        assertEquals(3, ItemUtils.amountOf(referencing.itemAt(0)));
        assertEquals(0, ruleCalls.get());
    }

    @Test
    void placementRulesBelongToEachReferencingRootAlias() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory first = fromContents(chest);
        ReferencingInventory second = fromContents(chest);
        AtomicInteger firstRules = new AtomicInteger();
        AtomicInteger secondRules = new AtomicInteger();
        first.setAccessRule(placement -> {
            firstRules.incrementAndGet();
            return false;
        });
        second.setAccessRule(placement -> {
            secondRules.incrementAndGet();
            return true;
        });
        AddResult rejected = first.tryAdd(reason(), diamonds(2));
        AddResult accepted = second.tryAdd(reason(), diamonds(2));

        assertEquals(2, rejected.remaining());
        assertEquals(0, accepted.remaining());
        assertEquals(2, ItemUtils.amountOf(chest.getItem(0)));
        assertEquals(9, firstRules.get());
        assertEquals(1, secondRules.get());
    }

    @Test
    void writesReconcileExternalChangesBeforePlanning() {
        org.bukkit.inventory.Inventory chest = new CraftInventory(new SimpleContainer(9));
        ReferencingInventory referencing = fromContents(chest);
        chest.setItem(0, diamonds(4));
        AddResult add = referencing.tryAdd(reason(), diamonds(3));

        assertInstanceOf(TransactionResult.Committed.class, add.result());
        assertEquals(0, add.remaining());
        assertEquals(7, ItemUtils.amountOf(chest.getItem(0)));
        assertEquals(7, ItemUtils.amountOf(referencing.itemAt(0)));
    }

    @Test
    void playerStorageReordersHotbarToTail() {
        org.bukkit.inventory.Inventory playerInventory = new CraftInventory(new SimpleContainer(36));
        playerInventory.setItem(0, diamonds(1));
        playerInventory.setItem(9, diamonds(2));
        playerInventory.setItem(35, diamonds(3));
        ReferencingInventory referencing = fromPlayerStorage(playerInventory);

        assertEquals(36, referencing.size());
        assertEquals(2, ItemUtils.amountOf(referencing.itemAt(0)));
        assertEquals(3, ItemUtils.amountOf(referencing.itemAt(26)));
        assertEquals(1, ItemUtils.amountOf(referencing.itemAt(27)));
        referencing.setItem(reason(), 27, diamonds(9));

        assertEquals(9, ItemUtils.amountOf(playerInventory.getItem(0)));
    }

    @Test
    void playerStorageReferenceUsesVanillaIterationOrder() {
        org.bukkit.inventory.Inventory playerStorage = new CraftInventory(new SimpleContainer(36));
        ReferencingInventory referencing = fromPlayerStorage(playerStorage);
        SlotOrder addOrder = referencing.iterationOrder(OperationCategory.ADD);
        SlotOrder collectOrder = referencing.iterationOrder(OperationCategory.COLLECT);

        assertEquals(35, addOrder.slotAt(0));
        assertEquals(0, addOrder.slotAt(35));
        assertEquals(0, collectOrder.slotAt(0));
        assertEquals(35, collectOrder.slotAt(35));
        referencing.add(reason(), diamonds(1));

        assertEquals(1, ItemUtils.amountOf(playerStorage.getItem(8)));
        assertNull(playerStorage.getItem(7));
        playerStorage.setItem(9, new ItemStack(Material.EMERALD, 2));
        playerStorage.setItem(10, new ItemStack(Material.EMERALD, 2));
        referencing.collect(reason(), new ItemStack(Material.EMERALD), 1);

        assertEquals(1, ItemUtils.amountOf(playerStorage.getItem(9)));
        assertEquals(2, ItemUtils.amountOf(playerStorage.getItem(10)));
    }

    @Test
    void batchAndCrossInventoryWritesKeepFullWriteThrough() {
        org.bukkit.inventory.Inventory chest = new CraftInventory(new SimpleContainer(9));
        chest.setItem(0, diamonds(5));
        ReferencingInventory referencing = fromContents(chest);
        AddResult added = referencing.tryAdd(reason(), diamonds(9));

        assertInstanceOf(TransactionResult.Committed.class, added.result());
        assertEquals(0, added.remaining());
        assertEquals(14, ItemUtils.amountOf(chest.getItem(0)));
        referencing.refresh();

        assertEquals(14, ItemUtils.amountOf(referencing.itemAt(0)));
        VirtualInventory virtual = new VirtualInventory(1);
        virtual.setMaxStackSizes(new int[]{5});
        PlannedRoot virtualPlan = virtual.openPlanForWrite();
        PlannedRoot referencingPlan = referencing.openPlanForWrite();
        List<TransactionScope> scopes = List.of(
                new TransactionScope(virtualPlan, List.of(new SlotChange(0, virtualPlan.planned()[0], diamonds(5)))),
                new TransactionScope(referencingPlan, List.of(
                        new SlotChange(0, referencingPlan.planned()[0], diamonds(64)),
                        new SlotChange(1, referencingPlan.planned()[1], diamonds(5))
                ))
        );

        assertInstanceOf(TransactionResult.Committed.class,
                InventoryTransactions.commit(reason(), scopes, false));

        assertEquals(64, ItemUtils.amountOf(chest.getItem(0)));
        assertEquals(5, ItemUtils.amountOf(virtual.itemAt(0)));
        assertEquals(5, ItemUtils.amountOf(chest.getItem(1)));
    }

    @Test
    void postObserverReentrantWritesSeeFlushedContainer() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory referencing = fromContents(chest);
        List<UpdateReason> postReasons = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Integer> containerAmountAtReentry = new AtomicReference<>();
        AtomicBoolean reentered = new AtomicBoolean();
        referencing.subscribePostUpdate(event -> {
            postReasons.add(event.reason());
            if (reentered.compareAndSet(false, true)) {
                containerAmountAtReentry.set(ItemUtils.amountOf(chest.getItem(0)));
                referencing.setItem(reason(), 1, diamonds(2));
            }
        });
        referencing.setItem(reason(), 0, diamonds(1));

        assertEquals(1, containerAmountAtReentry.get());
        assertEquals(2, ItemUtils.amountOf(chest.getItem(1)));
        assertEquals(List.of(UpdateReason.Program.INSTANCE, UpdateReason.Program.INSTANCE), List.copyOf(postReasons));
    }

    @Test
    void slotMaxStackSizeReflectsReferencedContainer() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory referencing = fromContents(chest);

        assertEquals(chest.getMaxStackSize(), referencing.slotMaxStackSize(0));
        assertThrows(IndexOutOfBoundsException.class, () -> referencing.slotMaxStackSize(9));
    }

    @Test
    void writePreparationPropagatesPlatformFailure() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        IllegalStateException expected = new IllegalStateException("wrong region");
        AtomicBoolean failReads = new AtomicBoolean();
        ReferencingInventory referencing = ReferencingInventory.create(
                chest,
                inventory -> {
                    if (failReads.get()) {
                        throw expected;
                    }
                    return inventory.getContents();
                },
                java.util.function.UnaryOperator.identity(),
                false
        );
        failReads.set(true);

        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> referencing.setItem(reason(), 0, diamonds(1))));

        assertNull(chest.getItem(0));
        assertNull(referencing.itemAt(0));
    }

    @Test
    void exposesReferencedInventory() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);

        assertSame(chest, fromContents(chest).referencedInventory());
    }

    @Test
    void rejectsDuplicatePhysicalSlotMappings() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);

        assertThrows(IllegalArgumentException.class, () -> ReferencingInventory.create(
                chest,
                org.bukkit.inventory.Inventory::getContents,
                slots -> new int[slots.length],
                false
        ));
    }

    @Test
    void reusesCachedExternalSlotKeys() {
        ReferencingInventory referencing = fromContents(Bukkit.createInventory(null, 9));

        assertSame(referencing.physicalKey(0), referencing.physicalKey(0));
    }

    @Test
    void unsafeItemAtMayHandOutLiveViewsSoRenderingReadsCopies() {
        org.bukkit.inventory.Inventory chest = new CraftInventory(new SimpleContainer(9));
        chest.setItem(0, diamonds(5));
        ReferencingInventory referencing = fromContents(chest);
        ItemStack copied = referencing.itemAt(0);

        assertNotSame(referencing.unsafeItemAt(0), copied);
        assertEquals(5, ItemUtils.amountOf(copied));
        chest.setItem(0, diamonds(1));

        assertEquals(5, ItemUtils.amountOf(copied), "复制读出的内容不跟着外部存储变");
    }

    private static ReferencingInventory fromContents(org.bukkit.inventory.Inventory inventory) {
        return ReferencingInventory.create(
                inventory,
                org.bukkit.inventory.Inventory::getContents,
                java.util.function.UnaryOperator.identity(),
                false
        );
    }

    private static ReferencingInventory fromPlayerStorage(org.bukkit.inventory.Inventory inventory) {
        return ReferencingInventory.create(
                inventory,
                org.bukkit.inventory.Inventory::getStorageContents,
                slots -> {
                    int[] reordered = new int[slots.length];
                    for (int i = 0; i < slots.length; i++) {
                        reordered[i] = (slots[i] + 9) % 36;
                    }
                    return reordered;
                },
                true
        );
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static ItemStack unbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    private static org.bukkit.inventory.Inventory countingWrites(org.bukkit.inventory.Inventory delegate, AtomicInteger writes) {
        return (org.bukkit.inventory.Inventory) Proxy.newProxyInstance(
                org.bukkit.inventory.Inventory.class.getClassLoader(),
                new Class<?>[]{org.bukkit.inventory.Inventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("equals") && args != null && args.length == 1) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode") && args == null) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("setItem")) {
                        writes.incrementAndGet();
                    }
                    return method.invoke(delegate, args);
                }
        );
    }

    private static int totalDiamonds(org.bukkit.inventory.Inventory inventory) {
        int total = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() == Material.DIAMOND) {
                total += contents[i].getAmount();
            }
        }
        return total;
    }
}
