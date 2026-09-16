package net.momirealms.sparrow.ui.inventory.storage;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.state.GcSupport;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.ref.WeakReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerWeakHoldTest {

    private static final long FIRST_POS = 12345L;
    private static final long SECOND_POS = 54321L;
    private ServerMock server;
    private Level level;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
        });
        World world = this.server.addSimpleWorld("weak-hold");
        this.level = new Level(world);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void worldContainerIsNotHeldByTheStorage() {
        ChestBlockEntity chest = this.placedChest(FIRST_POS);
        ExternalStorage storage = storageOf(chest);
        WeakReference<Object> probe = new WeakReference<>(chest);

        assertTrue(storage.alive());
        chest = null;
        GcSupport.awaitCollected(probe);

        assertFalse(storage.alive());
    }

    @Test
    void selfMadeContainerIsPinnedByTheStorage() {
        SimpleContainer selfMade = new SimpleContainer(9);
        ExternalStorage storage = storageOf(selfMade);
        WeakReference<Object> probe = new WeakReference<>(selfMade);
        selfMade = null;
        GcSupport.pressure();

        assertNotNull(probe.get());
        assertTrue(storage.alive());
        storage.write(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND, 1));

        assertNotNull(storage.read(0));
    }

    @Test
    void blockEntityWithoutALevelIsPinnedByTheStorage() {
        ChestBlockEntity loose = new ChestBlockEntity(9);
        ExternalStorage storage = storageOf(loose);
        WeakReference<Object> probe = new WeakReference<>(loose);
        loose = null;
        GcSupport.pressure();

        assertNotNull(probe.get());
        assertTrue(storage.alive());
    }

    @Test
    void slotKeyDoesNotHoldTheBlockEntity() {
        ChestBlockEntity chest = this.placedChest(FIRST_POS);
        SlotKey key = storageOf(chest).keyOf(0);
        WeakReference<Object> probe = new WeakReference<>(chest);
        chest = null;
        GcSupport.awaitCollected(probe);

        assertNotNull(key);
    }

    @Test
    void slotKeysOfTheSameBlockMatchAcrossInstances() {
        ExternalStorage first = storageOf(this.placedChest(FIRST_POS));
        ExternalStorage second = storageOf(this.placedChest(FIRST_POS));
        ExternalStorage elsewhere = storageOf(this.placedChest(SECOND_POS));

        assertEquals(first.keyOf(0), second.keyOf(0));
        assertNotEquals(first.keyOf(0), first.keyOf(1));
        assertNotEquals(first.keyOf(0), elsewhere.keyOf(0));
    }

    @Test
    void referencingInventoryRetiresAfterTheBlockEntityIsCollected() {
        ChestBlockEntity chest = this.placedChest(FIRST_POS);
        ReferencingInventory inventory = ReferencingInventory.fromContents(new CraftInventory(chest));
        WeakReference<Object> probe = new WeakReference<>(chest);
        chest = null;
        GcSupport.awaitCollected(probe);
        inventory.refresh();

        assertTrue(inventory.retired());
    }

    private ChestBlockEntity placedChest(long packedPos) {
        ChestBlockEntity chest = new ChestBlockEntity(9);
        chest.placeAt(this.level, packedPos);
        return chest;
    }

    private static ExternalStorage storageOf(Container container) {
        return BukkitStorage.of(new CraftInventory(container), Inventory::getContents);
    }

    private static final class ChestBlockEntity extends BlockEntity implements Container {
        private final SimpleContainer contents;
        private ChestBlockEntity(int size) {
            this.contents = new SimpleContainer(size);
        }
        @Override
        public int getContainerSize() {
            return this.contents.getContainerSize();
        }
        @Override
        public int getMaxStackSize() {
            return this.contents.getMaxStackSize();
        }
        @Override
        public ItemStack getItem(int slot) {
            return this.contents.getItem(slot);
        }
        @Override
        public void setItem(int slot, ItemStack item) {
            this.contents.setItem(slot, item);
        }
    }
}
