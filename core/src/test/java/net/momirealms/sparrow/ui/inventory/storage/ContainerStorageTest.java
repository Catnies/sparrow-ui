package net.momirealms.sparrow.ui.inventory.storage;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.PlayerStub;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftInventoryCrafting;
import org.bukkit.craftbukkit.inventory.CraftInventoryPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventorySaddledMount;
import org.bukkit.craftbukkit.inventory.CraftResultInventory;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.HorseMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerStorageTest {

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
    void craftInventoryTakesContainerChannel() {
        Inventory chest = new CraftInventory(new SimpleContainer(9));

        assertInstanceOf(FixedContainerStorage.class, BukkitStorage.of(chest, Inventory::getContents));
    }

    @Test
    void foreignInventoryStaysOnBukkitChannel() {
        Inventory chest = Bukkit.createInventory(null, 9);

        assertInstanceOf(BukkitStorage.class, BukkitStorage.of(chest, Inventory::getContents));
    }

    @Test
    void subclassThatKeepsTheSlotsTakesContainerChannel() {
        Inventory chest = new UntouchedSlotsInventory(new SimpleContainer(9));

        assertInstanceOf(FixedContainerStorage.class, BukkitStorage.of(chest, Inventory::getContents));
    }

    @Test
    void subclassWithItsOwnSlotsStaysOnBukkitChannel() {
        Inventory chest = new ShiftedSlotsInventory(new SimpleContainer(9));

        assertInstanceOf(BukkitStorage.class, BukkitStorage.of(chest, Inventory::getContents));
    }

    @Test
    void foreignPlayerInventoryStaysOnBukkitChannel() {
        Inventory inventory = this.server.addPlayer().getInventory();

        assertInstanceOf(BukkitStorage.class, BukkitStorage.of(inventory, Inventory::getStorageContents));
    }

    @Test
    void readsAndWritesLandOnNmsContainer() {
        Container container = new SimpleContainer(9);
        Inventory chest = new CraftInventory(container);
        ExternalStorage storage = BukkitStorage.of(chest, Inventory::getContents);
        storage.write(2, diamonds(5));

        assertEquals(5, ItemUtils.amountOf(container.getItem(2).getBukkitStack()));
        assertEquals(5, ItemUtils.amountOf(storage.read(2)));
        assertNull(storage.read(0));
        storage.write(2, null);

        assertNull(storage.read(2));
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, container.getItem(2));
    }

    @Test
    void batchReadGivesTheSameContentAsSlotReads() {
        Container container = new SimpleContainer(9);
        Inventory chest = new CraftInventory(container);
        ExternalStorage storage = BukkitStorage.of(chest, Inventory::getContents);
        storage.write(0, diamonds(3));
        storage.write(8, diamonds(7));
        @Nullable ItemStack[] contents = storage.readAll();

        assertEquals(9, contents.length);
        for (int slot = 0; slot < contents.length; slot++) {
            assertEquals(ItemUtils.amountOf(storage.read(slot)), ItemUtils.amountOf(contents[slot]));
        }

        assertEquals(3, ItemUtils.amountOf(contents[0]));
        assertEquals(7, ItemUtils.amountOf(contents[8]));
        assertNull(contents[1]);
    }

    @Test
    void writeStoresHandleWithoutCopying() {
        Container container = new SimpleContainer(9);
        Inventory chest = new CraftInventory(container);
        ExternalStorage storage = BukkitStorage.of(chest, Inventory::getContents);
        ItemStack written = diamonds(5);
        storage.write(0, written);

        assertSame(written, container.getItem(0).getBukkitStack());
    }

    @Test
    void wrapsAnNmsContainerWithoutAnyBukkitInventory() {
        Container container = new SimpleContainer(9);
        ExternalStorage storage = ExternalStorage.ofContainer(container);

        assertEquals(9, storage.size());
        assertEquals(64, storage.maxStackSize(0));
        assertEquals(new SlotKey(container, 2), storage.keyOf(2));
        storage.write(2, diamonds(5));

        assertEquals(5, ItemUtils.amountOf(container.getItem(2).getBukkitStack()));
        assertNull(ReferencingInventory.of(storage).referencedInventory());
    }

    @Test
    void keepsBukkitInventoryAsReferencedHandle() {
        Inventory chest = new CraftInventory(new SimpleContainer(9));

        assertNotSame(chest, BukkitStorage.of(chest, Inventory::getContents).keyOf(0).owner());
        assertSame(chest, ReferencingInventory.fromContents(chest).referencedInventory());
    }

    @Test
    void sameContainerReferencedTwiceSharesSlotKeys() {
        Container container = new SimpleContainer(9);

        assertEquals(
                BukkitStorage.of(new CraftInventory(container), Inventory::getContents).keyOf(3),
                BukkitStorage.of(new CraftInventory(container), Inventory::getContents).keyOf(3)
        );
    }

    @Test
    void doubleChestSharesSlotKeysAcrossWrappers() {
        Container left = new SimpleContainer(27);
        Container right = new SimpleContainer(27);

        assertEquals(
                BukkitStorage.of(new CraftInventory(new CompoundContainer(left, right)), Inventory::getContents).keyOf(30),
                BukkitStorage.of(new CraftInventory(new CompoundContainer(left, right)), Inventory::getContents).keyOf(30)
        );
    }

    @Test
    void doubleChestSlotKeysReachIntoTheHalfThatHoldsThem() {
        Container left = new SimpleContainer(27);
        Container right = new SimpleContainer(27);
        ExternalStorage whole = BukkitStorage.of(new CraftInventory(new CompoundContainer(left, right)), Inventory::getContents);

        assertEquals(BukkitStorage.of(new CraftInventory(left), Inventory::getContents).keyOf(5), whole.keyOf(5));
        assertEquals(BukkitStorage.of(new CraftInventory(right), Inventory::getContents).keyOf(5), whole.keyOf(32));
    }

    @Test
    void doubleChestHalvesDoNotShareSlotKeys() {
        Container left = new SimpleContainer(27);
        Container right = new SimpleContainer(27);
        ExternalStorage whole = BukkitStorage.of(new CraftInventory(new CompoundContainer(left, right)), Inventory::getContents);

        assertNotEquals(whole.keyOf(5), whole.keyOf(32));
    }

    @Test
    void playerInventorySlotKeysFollowTheOwner() {
        Player player = this.server.addPlayer();

        assertEquals(
                new SlotKey(player.getUniqueId(), 4),
                BukkitStorage.of(player.getInventory(), Inventory::getStorageContents).keyOf(4)
        );
    }

    @Test
    void playerInventoryWithEquipmentTakesContainerChannel() {
        CraftInventoryPlayer inventory = playerInventoryOnNms(this.server);

        assertInstanceOf(PlayerContainerStorage.class, BukkitStorage.of(inventory, Inventory::getContents));
        assertInstanceOf(PlayerContainerStorage.class, BukkitStorage.of(inventory, Inventory::getStorageContents));
    }

    @Test
    void playerSegmentSizeFollowsTheRequestedContents() {
        CraftInventoryPlayer inventory = playerInventoryOnNms(this.server);

        assertEquals(43, BukkitStorage.of(inventory, Inventory::getContents).size());
        assertEquals(36, BukkitStorage.of(inventory, Inventory::getStorageContents).size());
    }

    @Test
    void playerEquipmentSlotsKeepTheSameSlotNumbers() {
        CraftInventoryPlayer inventory = playerInventoryOnNms(this.server);
        ExternalStorage storage = BukkitStorage.of(inventory, Inventory::getContents);
        storage.write(39, diamonds(1));

        assertEquals(1, ItemUtils.amountOf(inventory.getInventory().equipmentAt(3).getBukkitStack()));
        assertEquals(1, ItemUtils.amountOf(storage.read(39)));
        assertNull(storage.read(35));
        assertEquals(99, storage.maxStackSize(39));
    }

    @Test
    void playerEquipmentSlotKeysStillFollowTheOwner() {
        CraftInventoryPlayer inventory = playerInventoryOnNms(this.server);

        assertEquals(
                new SlotKey(inventory.getHolder().getUniqueId(), 40),
                BukkitStorage.of(inventory, Inventory::getContents).keyOf(40)
        );
    }

    @Test
    void splicedStorageReadsAndWritesLandOnThePartThatHoldsTheSlot() {
        Container left = new SimpleContainer(27);
        Container right = new SimpleContainer(27);
        ExternalStorage storage = ExternalStorage.ofContainer(new CompoundContainer(left, right));
        storage.write(30, diamonds(4));

        assertEquals(4, ItemUtils.amountOf(right.getItem(3).getBukkitStack()));
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, left.getItem(3));
        assertEquals(4, ItemUtils.amountOf(storage.read(30)));
    }

    @Test
    void splicedStorageBatchReadRunsAcrossEveryPart() {
        Container left = new SimpleContainer(27);
        Container right = new SimpleContainer(27);
        ExternalStorage storage = ExternalStorage.ofContainer(new CompoundContainer(left, right));
        storage.write(0, diamonds(3));
        storage.write(53, diamonds(7));
        @Nullable ItemStack[] contents = storage.readAll();

        assertEquals(54, contents.length);
        for (int slot = 0; slot < contents.length; slot++) {
            assertEquals(ItemUtils.amountOf(storage.read(slot)), ItemUtils.amountOf(contents[slot]));
        }

        assertEquals(3, ItemUtils.amountOf(contents[0]));
        assertEquals(7, ItemUtils.amountOf(contents[53]));
        assertNull(contents[27]);
    }

    @Test
    void splicedStorageMaxStackSizeComesFromThePartThatHoldsTheSlot() {
        Container left = new SimpleContainer(9);
        Container right = new SmallStackContainer(9);
        ExternalStorage storage = ExternalStorage.ofContainer(new CompoundContainer(left, right));

        assertEquals(64, storage.maxStackSize(0));
        assertEquals(16, storage.maxStackSize(9));
    }

    @Test
    void nestedContainersFlattenIntoOneSequence() {
        Container first = new SimpleContainer(3);
        Container second = new SimpleContainer(3);
        Container third = new SimpleContainer(3);
        ExternalStorage storage = ExternalStorage.ofContainer(new CompoundContainer(new CompoundContainer(first, second), third));

        assertEquals(9, storage.size());
        storage.write(4, diamonds(2));
        storage.write(7, diamonds(6));

        assertEquals(2, ItemUtils.amountOf(second.getItem(1).getBukkitStack()));
        assertEquals(6, ItemUtils.amountOf(third.getItem(1).getBukkitStack()));
        assertEquals(new SlotKey(second, 1), storage.keyOf(4));
        assertEquals(new SlotKey(third, 1), storage.keyOf(7));
    }

    private static CraftInventoryPlayer playerInventoryOnNms(ServerMock server) {
        net.minecraft.world.entity.player.Inventory nmsInventory = new net.minecraft.world.entity.player.Inventory();
        CraftInventoryPlayer inventory = new CraftInventoryPlayer(nmsInventory);
        nmsInventory.owner(PlayerStub.addTo(server, inventory));
        return inventory;
    }

    @Test
    void craftingInventoryPutsTheResultSlotFirst() {
        Container result = new SimpleContainer(1);
        Container matrix = new SimpleContainer(9);
        Inventory crafting = new CraftInventoryCrafting(matrix, result);
        ExternalStorage storage = BukkitStorage.of(crafting, Inventory::getContents);

        assertEquals(10, storage.size());
        storage.write(0, diamonds(1));
        storage.write(1, diamonds(2));

        assertEquals(1, ItemUtils.amountOf(result.getItem(0).getBukkitStack()));
        assertEquals(2, ItemUtils.amountOf(matrix.getItem(0).getBukkitStack()));
        assertEquals(new SlotKey(result, 0), storage.keyOf(0));
        assertEquals(new SlotKey(matrix, 0), storage.keyOf(1));
    }

    @Test
    void resultInventoryReferencesOnlyTheIngredientSection() {
        Container ingredients = new SimpleContainer(3);
        Container result = new SimpleContainer(1);
        Inventory anvil = new CraftResultInventory(ingredients, result);
        ExternalStorage storage = BukkitStorage.of(anvil, Inventory::getContents);

        assertEquals(3, storage.size());
        storage.write(0, diamonds(5));

        assertEquals(5, ItemUtils.amountOf(ingredients.getItem(0).getBukkitStack()));
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, result.getItem(0));
        assertEquals(new SlotKey(ingredients, 0), storage.keyOf(0));
    }

    @Test
    void saddledMountPutsSaddleAndArmorBeforeTheMainSection() {
        Horse mount = new HorseMock(this.server, UUID.randomUUID());
        Container saddle = new SimpleContainer(1);
        Container armor = new SimpleContainer(1);
        Container main = new SimpleContainer(15, mount);
        Inventory inventory = new CraftInventorySaddledMount(main, armor, saddle);
        ExternalStorage storage = BukkitStorage.of(inventory, Inventory::getContents);

        assertInstanceOf(SplicedStorage.class, storage);
        assertEquals(17, storage.size());
        storage.write(0, diamonds(1));
        storage.write(1, diamonds(2));
        storage.write(2, diamonds(3));

        assertEquals(1, ItemUtils.amountOf(saddle.getItem(0).getBukkitStack()));
        assertEquals(2, ItemUtils.amountOf(armor.getItem(0).getBukkitStack()));
        assertEquals(3, ItemUtils.amountOf(main.getItem(0).getBukkitStack()));
    }

    @Test
    void mountEquipmentSlotKeysFollowTheMount() {
        Horse mount = new HorseMock(this.server, UUID.randomUUID());
        Container main = new SimpleContainer(15, mount);
        ExternalStorage first = mountStorage(main);
        ExternalStorage second = mountStorage(main);

        assertEquals(new SlotKey(mount.getUniqueId(), 0), first.keyOf(0));
        assertEquals(first.keyOf(0), second.keyOf(0));
        assertEquals(first.keyOf(1), second.keyOf(1));
        assertNotEquals(first.keyOf(0), first.keyOf(1));
        assertNotEquals(first.keyOf(0), first.keyOf(2));
        assertEquals(new SlotKey(mount.getUniqueId(), 2), first.keyOf(2));
        assertEquals(first.keyOf(2), second.keyOf(2));
    }

    @Test
    void mountStorageDiesWithTheMount() {
        Horse mount = new HorseMock(this.server, UUID.randomUUID());
        ExternalStorage storage = mountStorage(new SimpleContainer(15, mount));

        assertTrue(storage.alive());
        mount.remove();

        assertFalse(storage.alive());
    }

    private static ExternalStorage mountStorage(Container main) {
        return BukkitStorage.of(
                new CraftInventorySaddledMount(main, new SimpleContainer(1), new SimpleContainer(1)),
                Inventory::getContents
        );
    }

    @Test
    void splicedStorageReadsAndWritesThroughTheRightSegment() {
        Container left = new SimpleContainer(27);
        Container right = new SimpleContainer(27);
        ReferencingInventory inventory = ReferencingInventory.fromContents(
                new CraftInventory(new CompoundContainer(left, right)));
        inventory.setItem(30, diamonds(4));

        assertEquals(4, ItemUtils.amountOf(right.getItem(3).getBukkitStack()));
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, left.getItem(3));
        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(30)));
    }

    @Test
    void splicedStorageSeesEverySlotOfTheLastSegment() {
        Container result = new SimpleContainer(1);
        Container matrix = new SimpleContainer(4);
        matrix.setItem(3, net.minecraft.world.item.ItemStack.wrap(diamonds(7)));
        ReferencingInventory inventory = ReferencingInventory.fromContents(
                new CraftInventoryCrafting(matrix, result));

        assertEquals(5, inventory.size());
        assertNull(inventory.itemAt(0));
        assertEquals(7, ItemUtils.amountOf(inventory.itemAt(4)));
    }

    @Test
    void mountInventoryReadsAndWritesThroughTheRightSegment() {
        Horse mount = new HorseMock(this.server, UUID.randomUUID());
        Container saddle = new SimpleContainer(1);
        Container armor = new SimpleContainer(1);
        Container main = new SimpleContainer(15, mount);
        ReferencingInventory inventory = ReferencingInventory.fromContents(
                new CraftInventorySaddledMount(main, armor, saddle));
        inventory.setItem(2, diamonds(6));

        assertEquals(6, ItemUtils.amountOf(main.getItem(0).getBukkitStack()));
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, saddle.getItem(0));
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, armor.getItem(0));
        assertEquals(6, ItemUtils.amountOf(inventory.itemAt(2)));
    }

    @Test
    void playerContentsReachTheEquipmentSection() {
        CraftInventoryPlayer bukkitInventory = playerInventoryOnNms(this.server);
        ReferencingInventory inventory = ReferencingInventory.fromContents(bukkitInventory);

        assertEquals(43, inventory.size());
        inventory.setItem(39, diamonds(1));

        assertEquals(1, ItemUtils.amountOf(bukkitInventory.getInventory().equipmentAt(3).getBukkitStack()));
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(39)));
    }

    @Test
    void playerStorageContentsMoveTheHotbarToTheEnd() {
        CraftInventoryPlayer bukkitInventory = playerInventoryOnNms(this.server);
        ReferencingInventory inventory = ReferencingInventory.fromPlayerStorageContents(bukkitInventory);

        assertEquals(36, inventory.size());
        inventory.setItem(0, diamonds(1));
        inventory.setItem(27, diamonds(2));

        assertEquals(1, ItemUtils.amountOf(bukkitInventory.getItem(9)));
        assertEquals(2, ItemUtils.amountOf(bukkitInventory.getItem(0)));
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(2, ItemUtils.amountOf(inventory.itemAt(27)));
    }

    @Test
    void containerChannelComparesWithoutWrappingEachSlot() {
        ExternalStorage storage = ExternalStorage.ofContainer(new SimpleContainer(3));
        storage.write(1, diamonds(5));

        assertTrue(storage.contentEquals(0, null));
        assertFalse(storage.contentEquals(0, diamonds(1)));
        assertTrue(storage.contentEquals(1, diamonds(5)));
        assertFalse(storage.contentEquals(1, diamonds(4)));
        assertFalse(storage.contentEquals(1, new ItemStack(Material.EMERALD, 5)));
        assertFalse(storage.contentEquals(1, null));
    }

    @Test
    void splicedStorageComparesInThePartThatHoldsTheSlot() {
        Container left = new SimpleContainer(2);
        Container right = new SimpleContainer(2);
        ExternalStorage storage = ExternalStorage.ofContainer(new CompoundContainer(left, right));
        storage.write(3, diamonds(2));

        assertEquals(2, ItemUtils.amountOf(right.getItem(1).getBukkitStack()));
        assertTrue(storage.contentEquals(3, diamonds(2)));
        assertFalse(storage.contentEquals(3, null));
        assertTrue(storage.contentEquals(1, null));
    }

    @Test
    void refreshReadsNothingWhileTheContainerStaysPut() {
        ExternalStorage inner = ExternalStorage.ofContainer(new SimpleContainer(9));
        ReadCountingStorage storage = new ReadCountingStorage(inner);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        storage.slotReads = 0;
        storage.bulkReads = 0;
        inventory.refresh();
        inventory.refresh();

        assertEquals(0, storage.slotReads);
        assertEquals(0, storage.bulkReads);
        inner.write(4, diamonds(3));
        inventory.refresh();

        assertEquals(1, storage.slotReads);
        assertEquals(1, storage.bulkReads);
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static final class UntouchedSlotsInventory extends CraftInventory {
        private UntouchedSlotsInventory(Container container) {
            super(container);
        }
    }

    private static final class ShiftedSlotsInventory extends CraftInventory {
        private ShiftedSlotsInventory(Container container) {
            super(container);
        }
        @Override
        public ItemStack getItem(int slot) {
            return super.getItem(this.getSize() - 1 - slot);
        }
        @Override
        public void setItem(int slot, ItemStack item) {
            super.setItem(this.getSize() - 1 - slot, item);
        }
    }

    private static final class ReadCountingStorage implements ExternalStorage {
        private final ExternalStorage delegate;
        private int slotReads;
        private int bulkReads;
        private ReadCountingStorage(ExternalStorage delegate) {
            this.delegate = delegate;
        }
        @Override
        public int size() {
            return this.delegate.size();
        }
        @Override
        @Nullable
        public ItemStack read(int slot) {
            this.slotReads++;
            return this.delegate.read(slot);
        }
        @Override
        public @Nullable ItemStack @NotNull [] readAll() {
            this.bulkReads++;
            return this.delegate.readAll();
        }
        @Override
        public boolean contentEquals(int slot, @Nullable ItemStack expected) {
            return this.delegate.contentEquals(slot, expected);
        }
        @Override
        public void write(int slot, @Nullable ItemStack item) {
            this.delegate.write(slot, item);
        }
        @Override
        public int maxStackSize(int slot) {
            return this.delegate.maxStackSize(slot);
        }
    }

    private static final class SmallStackContainer implements Container {
        private final SimpleContainer contents;
        private SmallStackContainer(int size) {
            this.contents = new SimpleContainer(size);
        }
        @Override
        public int getContainerSize() {
            return this.contents.getContainerSize();
        }
        @Override
        public int getMaxStackSize() {
            return 16;
        }
        @Override
        public net.minecraft.world.item.ItemStack getItem(int slot) {
            return this.contents.getItem(slot);
        }
        @Override
        public void setItem(int slot, net.minecraft.world.item.ItemStack item) {
            this.contents.setItem(slot, item);
        }
    }
}
