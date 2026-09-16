package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsafeInventoryAccessTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void rootUnsafeReadsExposeCurrentInternalStateWhileSafeReadsStillCopy() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setItem(reason(), 0, diamonds(4));
        ItemStack unsafeItem = inventory.unsafeItemAt(0);
        ItemStack[] unsafeSnapshot = inventory.unsafeSnapshot();

        assertSame(unsafeItem, inventory.unsafeItemAt(0));
        assertSame(unsafeSnapshot, inventory.unsafeSnapshot());
        assertSame(unsafeItem, unsafeSnapshot[0]);
        assertNotSame(unsafeItem, inventory.itemAt(0));
        ItemStack[] safeSnapshot = inventory.snapshot();

        assertNotSame(unsafeSnapshot, safeSnapshot);
        assertNotSame(unsafeItem, safeSnapshot[0]);
    }

    @Test
    void unsafeSnapshotsFollowStateSwapsWithoutCopyingUntouchedItems() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setItem(reason(), 0, diamonds(2));
        inventory.setItem(reason(), 2, new ItemStack(Material.EMERALD, 3));
        ItemStack[] before = inventory.unsafeSnapshot();
        ItemStack untouched = inventory.unsafeItemAt(2);

        assertSame(before, inventory.unsafeSnapshot());
        inventory.setItem(reason(), 1, diamonds(4));
        ItemStack[] after = inventory.unsafeSnapshot();

        assertNotSame(before, after);
        assertNull(before[1]);
        assertSame(untouched, after[2]);
        assertSame(untouched, inventory.unsafeItemAt(2));
        assertNotSame(untouched, inventory.snapshot()[2]);
    }

    @Test
    void predicateQueriesReceiveInternalItems() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(2), new ItemStack(Material.EMERALD, 3)});
        ItemStack first = inventory.unsafeItemAt(0);
        ItemStack second = inventory.unsafeItemAt(1);
        AtomicReference<ItemStack> containsItem = new AtomicReference<>();

        assertTrue(inventory.contains(item -> {
            containsItem.set(item);
            return true;
        }));

        assertSame(first, containsItem.get());
        List<ItemStack> counted = new ArrayList<>();

        assertEquals(2, inventory.count(item -> {
            counted.add(item);
            return true;
        }));

        assertSame(first, counted.get(0));
        assertSame(second, counted.get(1));
    }

    @Test
    void engineWritesReplaceTheSlotInstance() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(2));
        ItemStack before = inventory.unsafeItemAt(0);
        inventory.setItem(reason(), 0, diamonds(5));
        ItemStack grown = inventory.unsafeItemAt(0);

        assertNotSame(before, grown);
        assertEquals(5, grown.getAmount());
        inventory.setItem(reason(), 0, new ItemStack(Material.EMERALD, 1));

        assertNotSame(grown, inventory.unsafeItemAt(0));
        assertEquals(Material.EMERALD, inventory.unsafeItemAt(0).getType());
    }

    @Test
    void equalContentWritesKeepTheSlotInstance() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(2));
        ItemStack kept = inventory.unsafeItemAt(0);
        AtomicReference<SlotChange> observed = new AtomicReference<>();
        inventory.subscribePostUpdate(event -> observed.set(event.slotChanges().getFirst()));
        TransactionResult result = inventory.trySetItem(reason(), 0, diamonds(2));

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertTrue(observed.get().isUnchanged());
        assertSame(kept, inventory.unsafeItemAt(0));
    }

    @Test
    void slotChangeUnsafeReadsExposeEventObjectsAndRawAfterRemainsCompatible() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(reason(), 0, diamonds(2));
        AtomicReference<SlotChange> observed = new AtomicReference<>();
        inventory.subscribePostUpdate(event -> observed.set(event.slotChanges().getFirst()));
        inventory.setItem(reason(), 0, diamonds(5));
        SlotChange change = observed.get();

        assertSame(change.unsafeBefore(), change.unsafeBefore());
        assertSame(change.unsafeAfter(), change.unsafeAfter());
        assertSame(change.unsafeAfter(), change.unsafeAfter());
        assertSame(change.unsafeAfter(), inventory.unsafeItemAt(0));
        assertNotSame(change.unsafeBefore(), change.before());
        assertNotSame(change.unsafeAfter(), change.after());
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
