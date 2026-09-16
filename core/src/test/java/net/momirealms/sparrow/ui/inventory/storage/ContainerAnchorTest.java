package net.momirealms.sparrow.ui.inventory.storage;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.PlayerStub;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerAnchorTest {

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
    void blockContainerDiesWithItsBlockEntity() {
        ChestBlockEntity chest = new ChestBlockEntity(9);
        ExternalStorage storage = storageOf(chest);

        assertTrue(storage.alive());
        chest.setRemoved();

        assertFalse(storage.alive());
    }

    @Test
    void entityContainerDiesWithItsEntity() {
        ChestMinecart minecart = new ChestMinecart(9);
        ExternalStorage storage = storageOf(minecart);

        assertTrue(storage.alive());
        minecart.setRemoved();

        assertFalse(storage.alive());
    }

    @Test
    void doubleChestDiesWithEitherHalf() {
        ChestBlockEntity left = new ChestBlockEntity(27);
        ChestBlockEntity right = new ChestBlockEntity(27);
        ExternalStorage storage = storageOf(new CompoundContainer(left, right));

        assertTrue(storage.alive());
        right.setRemoved();

        assertFalse(storage.alive());
        ChestBlockEntity otherLeft = new ChestBlockEntity(27);
        ExternalStorage other = storageOf(new CompoundContainer(otherLeft, new ChestBlockEntity(27)));
        otherLeft.setRemoved();

        assertFalse(other.alive());
    }

    @Test
    void lecternDiesWithItsBlockEntity() {
        LecternBlockEntity lectern = new LecternBlockEntity();
        ExternalStorage storage = storageOf(lectern.bookAccess);

        assertTrue(storage.alive());
        lectern.setRemoved();

        assertFalse(storage.alive());
    }

    @Test
    void merchantContainerDiesWithItsMerchant() {
        Entity villager = new Entity();
        ExternalStorage storage = storageOf(new MerchantContainer(villager));

        assertTrue(storage.alive());
        villager.setRemoved();

        assertFalse(storage.alive());
    }

    @Test
    void containerWithoutAnchorNeverDies() {
        ExternalStorage storage = storageOf(new SimpleContainer(9));

        assertTrue(storage.alive());
    }

    @Test
    void inventoryOnBukkitChannelNeverDies() {
        Inventory foreign = Bukkit.createInventory(null, 9);

        assertTrue(BukkitStorage.of(foreign, Inventory::getContents).alive());
    }

    @Test
    void playerInventoryOnBukkitChannelDiesWhenTheOwnerLeaves() {
        PlayerStub player = PlayerStub.addTo(this.server);
        ExternalStorage storage = BukkitStorage.of(player.getInventory(), Inventory::getContents);

        assertTrue(storage.alive());
        player.disconnect();

        assertFalse(storage.alive());
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

    private static final class ChestMinecart extends Entity implements Container {
        private final SimpleContainer contents;
        private ChestMinecart(int size) {
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
