package net.momirealms.sparrow.ui.window.handle;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.window.handle.ProtocolInventoryView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;
import java.util.Arrays;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInventoryViewTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void contiguousLayoutPlacesThePlayerInventoryAfterTheTopInventory() {
        Player player = this.server.addPlayer();
        Inventory upper = Bukkit.createInventory(null, InventoryType.STONECUTTER);
        ProtocolInventoryView view = new ProtocolInventoryView(
                player,
                upper,
                2,
                InventoryType.STONECUTTER,
                MenuType.STONECUTTER
        );
        ItemStack[] slots = ProtocolInventoryViewTest.emptySlots(38);
        slots[0] = new ItemStack(Material.STONE);
        slots[2] = new ItemStack(Material.DIAMOND);
        slots[37] = new ItemStack(Material.GOLD_INGOT);
        view.initialize(slots, ItemStack.empty(), Component.empty());

        assertEquals(38, view.countSlots());
        assertEquals(Material.STONE, upper.getItem(0).getType());
        assertEquals(Material.DIAMOND, view.getItem(2).getType());
        assertEquals(Material.GOLD_INGOT, view.getItem(37).getType());
        assertSame(player.getInventory(), view.getInventory(2));
        assertEquals(9, view.convertSlot(2));
        assertEquals(8, view.convertSlot(37));
    }

    @Test
    void directBottomInventoryWriteTargetsPlayerStorageWithoutTouchingTheEventCopy() {
        Player player = this.server.addPlayer();
        Inventory upper = Bukkit.createInventory(null, InventoryType.STONECUTTER);
        ProtocolInventoryView view = new ProtocolInventoryView(
                player,
                upper,
                2,
                InventoryType.STONECUTTER,
                MenuType.STONECUTTER
        );
        ItemStack[] slots = ProtocolInventoryViewTest.emptySlots(38);
        slots[2] = new ItemStack(Material.STONE);
        view.initialize(slots, ItemStack.empty(), Component.empty());
        view.getBottomInventory().setItem(view.convertSlot(2), new ItemStack(Material.DIAMOND));

        assertEquals(Material.DIAMOND, player.getInventory().getItem(9).getType());
        assertEquals(Material.STONE, view.getItem(2).getType());
        BitSet touched = new BitSet();
        view.drainEventTouchedSlots(touched);

        assertTrue(touched.isEmpty());
        view.drainTouchedSlots(touched);

        assertTrue(touched.isEmpty());
    }

    @Test
    void crafterLayoutMapsItsTrailingResultBackIntoTheTopInventory() {
        Player player = this.server.addPlayer();
        Inventory upper = new InventoryMock(null, 10, InventoryType.CRAFTER);
        ProtocolInventoryView view = new ProtocolInventoryView(
                player,
                upper,
                9,
                InventoryType.CRAFTER,
                MenuType.CRAFTER_3X3
        );
        ItemStack[] slots = ProtocolInventoryViewTest.emptySlots(46);
        slots[9] = new ItemStack(Material.STONE);
        slots[44] = new ItemStack(Material.GOLD_INGOT);
        slots[45] = new ItemStack(Material.DIAMOND);
        view.initialize(slots, ItemStack.empty(), Component.empty());

        assertEquals(46, view.countSlots());
        assertEquals(Material.DIAMOND, upper.getItem(9).getType());
        assertSame(upper, view.getInventory(45));
        assertSame(player.getInventory(), view.getInventory(9));
        assertEquals(9, view.convertSlot(45));
        assertEquals(9, view.convertSlot(9));
        assertEquals(8, view.convertSlot(44));
        assertEquals(InventoryType.SlotType.QUICKBAR, view.getSlotType(36));
        upper.setItem(9, new ItemStack(Material.EMERALD));
        slots[45] = new ItemStack(Material.GOLD_INGOT);
        view.resetForEvent(slots, new BitSet(), ItemStack.empty());

        assertEquals(Material.GOLD_INGOT, upper.getItem(9).getType());
    }

    @Test
    void layoutRejectsAnInvalidLowerRangeBeforeUse() {
        Player player = this.server.addPlayer();
        Inventory upper = Bukkit.createInventory(null, InventoryType.STONECUTTER);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolInventoryView(
                        player,
                        upper,
                        3,
                        InventoryType.STONECUTTER,
                        MenuType.STONECUTTER
                )
        );
    }

    @Test
    void eventResetRestoresTopRenderedAndTouchedSlotsWithoutReplacingUnchangedLowerSlots() {
        Player player = this.server.addPlayer();
        Inventory upper = Bukkit.createInventory(null, InventoryType.STONECUTTER);
        ProtocolInventoryView view = new ProtocolInventoryView(
                player,
                upper,
                2,
                InventoryType.STONECUTTER,
                MenuType.STONECUTTER
        );
        ItemStack[] initial = ProtocolInventoryViewTest.emptySlots(38);
        initial[0] = new ItemStack(Material.DIAMOND);
        initial[2] = new ItemStack(Material.IRON_INGOT);
        initial[3] = new ItemStack(Material.COPPER_INGOT);
        view.initialize(initial, new ItemStack(Material.DIAMOND), Component.empty());
        view.getTopInventory().setItem(0, new ItemStack(Material.EMERALD));
        view.setItem(2, new ItemStack(Material.EMERALD));
        view.setCursor(new ItemStack(Material.GOLD_INGOT));
        BitSet eventTouched = new BitSet();
        view.drainEventTouchedSlots(eventTouched);

        assertTrue(eventTouched.get(2));
        assertEquals(Material.GOLD_INGOT, view.takeEventCursor().getType());
        ItemStack[] authoritative = ProtocolInventoryViewTest.emptySlots(38);
        authoritative[0] = new ItemStack(Material.STONE);
        authoritative[2] = new ItemStack(Material.GOLD_INGOT);
        authoritative[3] = new ItemStack(Material.COAL);
        authoritative[4] = new ItemStack(Material.APPLE);
        BitSet rendered = new BitSet();
        rendered.set(4);
        view.resetForEvent(authoritative, rendered, new ItemStack(Material.DIAMOND));

        assertEquals(Material.STONE, view.getItem(0).getType());
        assertEquals(Material.GOLD_INGOT, view.getItem(2).getType());
        assertEquals(Material.COPPER_INGOT, view.getItem(3).getType());
        assertEquals(Material.APPLE, view.getItem(4).getType());
        assertEquals(Material.DIAMOND, view.getCursor().getType());
        BitSet nextEventTouched = new BitSet();
        view.drainEventTouchedSlots(nextEventTouched);

        assertTrue(nextEventTouched.isEmpty());
        assertNull(view.takeEventCursor());
        BitSet touched = new BitSet();
        view.drainTouchedSlots(touched);

        assertTrue(touched.get(2));
        assertFalse(touched.get(4));
        assertTrue(view.takeCursorTouched());
    }

    private static ItemStack[] emptySlots(int size) {
        ItemStack[] slots = new ItemStack[size];
        Arrays.fill(slots, ItemStack.empty());
        return slots;
    }
}
