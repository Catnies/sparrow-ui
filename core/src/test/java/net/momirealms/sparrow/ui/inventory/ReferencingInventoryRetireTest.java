package net.momirealms.sparrow.ui.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencingInventoryRetireTest {

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
    void refreshRetiresWhenStorageIsGone() {
        MortalStorage storage = new MortalStorage(9);
        ReferencingInventory inventory = ReferencingInventory.of(storage);

        assertFalse(inventory.retired());
        storage.alive = false;
        inventory.refresh();

        assertTrue(inventory.retired());
    }

    @Test
    void writeEntryRetiresWhenTheStorageIsAlreadyGone() {
        MortalStorage storage = new MortalStorage(9);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        storage.alive = false;

        assertFalse(inventory.retired());
        assertInstanceOf(TransactionResult.Conflicted.class, inventory.trySetItem(reason(), 0, diamonds(3)));
        assertTrue(inventory.retired());
        assertNull(storage.contents[0]);
    }

    @Test
    void retiredInventoryReadsEmpty() {
        MortalStorage storage = new MortalStorage(9);
        storage.contents[2] = diamonds(5);
        ReferencingInventory inventory = ReferencingInventory.of(storage);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(2)));
        inventory.retire();

        assertNull(inventory.itemAt(2));
        assertNull(inventory.unsafeItemAt(2));
        assertNull(inventory.snapshot()[2]);
        assertNull(inventory.unsafeSnapshot()[2]);
    }

    @Test
    void retiredInventoryRejectsWrites() {
        MortalStorage storage = new MortalStorage(9);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        inventory.retire();

        assertInstanceOf(TransactionResult.Conflicted.class, inventory.trySetItem(reason(), 0, diamonds(3)));
        assertNull(storage.contents[0]);
    }

    @Test
    void retireNotifiesDisplaysWithoutFakingContentChange() {
        MortalStorage storage = new MortalStorage(9);
        storage.contents[0] = diamonds(1);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        AtomicInteger visualInvalidations = new AtomicInteger();
        AtomicInteger postEvents = new AtomicInteger();
        Subscription visual = inventory.visual().attach(0, visualInvalidations::incrementAndGet);
        Subscription post = inventory.subscribePostUpdate(event -> postEvents.incrementAndGet());
        inventory.retire();

        assertEquals(1, visualInvalidations.get());
        assertEquals(0, postEvents.get());
        visual.close();
        post.close();
    }

    @Test
    void retireIsIdempotent() {
        MortalStorage storage = new MortalStorage(9);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        AtomicInteger visualInvalidations = new AtomicInteger();
        Subscription visual = inventory.visual().attach(0, visualInvalidations::incrementAndGet);
        inventory.retire();
        inventory.retire();

        assertEquals(1, visualInvalidations.get());
        visual.close();
    }

    @Test
    void retiredInventoryStopsAbsorbingExternalChanges() {
        MortalStorage storage = new MortalStorage(9);
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        AtomicInteger postEvents = new AtomicInteger();
        Subscription post = inventory.subscribePostUpdate(event -> postEvents.incrementAndGet());
        inventory.retire();
        storage.contents[0] = diamonds(4);
        inventory.refresh();

        assertEquals(0, postEvents.get());
        assertNull(inventory.itemAt(0));
        post.close();
    }

    @Test
    void retiredInventoryIsCollectedOnceDropped() {
        ReferencingInventory inventory = ReferencingInventory.of(new MortalStorage(9));
        inventory.contentSignal();
        inventory.subscribePostUpdate(event -> {
        });
        inventory.retire();
        WeakReference<ReferencingInventory> probe = new WeakReference<>(inventory);
        inventory = null;
        GcSupport.awaitCollected(probe);
    }

    @Test
    void retireLetsGoOfTheReferencedContainer() {
        ReferencingInventory[] holder = new ReferencingInventory[1];
        WeakReference<Object> container = referenceFreshContainer(holder);
        GcSupport.pressure();

        assertNotNull(container.get());
        holder[0].retire();
        GcSupport.awaitCollected(container);
    }

    @Test
    void retiredInventoryStopsPointingAtTheContainer() {
        Container container = new SimpleContainer(9);
        ReferencingInventory inventory = ReferencingInventory.fromContents(new CraftInventory(container));
        SlotKey before = inventory.physicalKey(0);

        assertEquals(new SlotKey(container, 0), before);
        inventory.retire();

        assertNull(inventory.referencedInventory());
        assertEquals(new SlotKey(inventory, 0), inventory.physicalKey(0));
        assertEquals(SparrowInventory.DEFAULT_MAX_STACK_SIZE, inventory.slotMaxStackSize(0));
    }

    private static WeakReference<Object> referenceFreshContainer(ReferencingInventory[] holder) {
        Container container = new SimpleContainer(9);
        holder[0] = ReferencingInventory.fromContents(new CraftInventory(container));
        return new WeakReference<>(container);
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static final class MortalStorage implements ExternalStorage {
        private final @org.jetbrains.annotations.Nullable ItemStack[] contents;
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
}
