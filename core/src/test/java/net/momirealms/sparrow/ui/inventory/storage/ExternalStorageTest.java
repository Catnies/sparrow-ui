package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalStorageTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void readAllDefaultsToPerSlotReads() {
        MemoryStorage storage = new MemoryStorage(3);
        storage.write(0, new ItemStack(Material.DIAMOND, 5));
        storage.write(2, new ItemStack(Material.EMERALD, 2));
        @Nullable ItemStack[] contents = storage.readAll();

        assertEquals(3, contents.length);
        assertEquals(List.of(0, 1, 2), storage.readSlots);
        assertSame(storage.items[0], contents[0]);
        assertNull(contents[1]);
        assertSame(storage.items[2], contents[2]);
    }

    @Test
    void slotKeyDefaultsToStorageItself() {
        MemoryStorage storage = new MemoryStorage(1);

        assertEquals(new SlotKey(storage, 0), storage.keyOf(0));
    }

    private static final class MemoryStorage implements ExternalStorage {
        private final @Nullable ItemStack[] items;
        private final List<Integer> readSlots = new ArrayList<>();
        private MemoryStorage(int size) {
            this.items = new ItemStack[size];
        }
        @Override
        public int size() {
            return this.items.length;
        }
        @Override
        @Nullable
        public ItemStack read(int slot) {
            this.readSlots.add(slot);
            return this.items[slot];
        }
        @Override
        public void write(int slot, @Nullable ItemStack item) {
            this.items[slot] = item;
        }
        @Override
        public int maxStackSize(int slot) {
            return 99;
        }
    }
}
