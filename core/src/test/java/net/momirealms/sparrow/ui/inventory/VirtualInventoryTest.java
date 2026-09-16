package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualInventoryTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void keepsGivenUuidAndRandomizesWhenAbsent() {
        UUID uuid = UUID.randomUUID();

        assertSame(uuid, new VirtualInventory(uuid, 3).uuid());
        assertNotEquals(new VirtualInventory(3).uuid(), new VirtualInventory(3).uuid());
    }

    @Test
    void rejectsNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualInventory(-1));
    }

    @Test
    void constructorSnapshotsInitialContents() {
        ItemStack diamond = new ItemStack(Material.DIAMOND, 5);
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamond, null});
        diamond.setAmount(1);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(2, inventory.size());
    }

    @Test
    void slotMaxStackSizesDefaultAndOverride() {
        VirtualInventory inventory = new VirtualInventory(3);

        assertEquals(SparrowInventory.DEFAULT_MAX_STACK_SIZE, inventory.slotMaxStackSize(0));
        inventory.setMaxStackSize(1, 10);

        assertEquals(SparrowInventory.DEFAULT_MAX_STACK_SIZE, inventory.slotMaxStackSize(0));
        assertEquals(10, inventory.slotMaxStackSize(1));
        inventory.setMaxStackSizes(new int[]{1, 2, 3});

        assertEquals(1, inventory.slotMaxStackSize(0));
        assertEquals(3, inventory.slotMaxStackSize(2));
    }

    @Test
    void rejectsInvalidMaxStackConfiguration() {
        VirtualInventory inventory = new VirtualInventory(2);

        assertThrows(IllegalArgumentException.class, () -> inventory.setMaxStackSize(0, 0));
        assertThrows(IllegalArgumentException.class, () -> inventory.setMaxStackSizes(new int[]{5}));
        assertThrows(IllegalArgumentException.class, () -> inventory.setMaxStackSizes(new int[]{5, 0}));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.slotMaxStackSize(2));
    }

    @Test
    void iterationOrderDefaultsToNaturalAndAcceptsOverridePerCategory() {
        VirtualInventory inventory = new VirtualInventory(3);
        SlotOrder reversed = SlotOrder.of(2, 1, 0);
        inventory.setIterationOrder(OperationCategory.ADD, reversed);

        assertSame(reversed, inventory.iterationOrder(OperationCategory.ADD));
        assertEquals(0, inventory.iterationOrder(OperationCategory.COLLECT).slotAt(0));
        assertEquals(0, inventory.iterationOrder(OperationCategory.OTHER).slotAt(0));
    }

    @Test
    void rejectsMismatchedIterationOrderSize() {
        VirtualInventory inventory = new VirtualInventory(3);

        assertThrows(IllegalArgumentException.class, () -> inventory.setIterationOrder(OperationCategory.ADD, SlotOrder.of(1, 0)));
    }

    @Test
    void reverseIterationOrderFlipsCurrentOrderOfSingleCategory() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.reverseIterationOrder(OperationCategory.ADD);

        assertEquals(2, inventory.iterationOrder(OperationCategory.ADD).slotAt(0));
        assertEquals(0, inventory.iterationOrder(OperationCategory.ADD).slotAt(2));
        assertEquals(0, inventory.iterationOrder(OperationCategory.COLLECT).slotAt(0));
        assertEquals(0, inventory.iterationOrder(OperationCategory.OTHER).slotAt(0));
    }

    @Test
    void reverseIterationOrderReversesCustomOrderInsteadOfNaturalOrder() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setIterationOrder(OperationCategory.ADD, SlotOrder.of(1, 2, 0));
        inventory.reverseIterationOrder(OperationCategory.ADD);

        assertEquals(0, inventory.iterationOrder(OperationCategory.ADD).slotAt(0));
        assertEquals(2, inventory.iterationOrder(OperationCategory.ADD).slotAt(1));
        assertEquals(1, inventory.iterationOrder(OperationCategory.ADD).slotAt(2));
    }

    @Test
    void reverseIterationOrderWithoutCategoryFlipsAllCategories() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.reverseIterationOrder();

        assertEquals(1, inventory.iterationOrder(OperationCategory.ADD).slotAt(0));
        assertEquals(1, inventory.iterationOrder(OperationCategory.COLLECT).slotAt(0));
        assertEquals(1, inventory.iterationOrder(OperationCategory.OTHER).slotAt(0));
    }
}
