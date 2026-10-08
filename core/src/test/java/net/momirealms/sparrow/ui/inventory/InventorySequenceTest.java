package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySequenceTest {

    private final Bindings bindings = new Bindings();

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
    void keepsMembersInInsertionOrderWithoutDuplicates() {
        VirtualInventory first = new VirtualInventory(9);
        VirtualInventory second = new VirtualInventory(9);
        InventorySequence sequence = InventorySequence.of(first, second, first);

        assertEquals(List.of(first, second), sequence.inventories());
        sequence.remove(first);

        assertEquals(List.of(second), sequence.inventories());
    }

    @Test
    void memberContentChangeInvalidatesTheSequence() {
        VirtualInventory chest = new VirtualInventory(9);
        InventorySequence sequence = InventorySequence.of(chest);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sequence.signal().onDirty(invalidations::incrementAndGet));
        chest.setItem(reason(), 0, diamonds(3));

        assertEquals(1, invalidations.get());
    }

    @Test
    void addingAndRemovingMembersInvalidatesTheSequence() {
        VirtualInventory present = new VirtualInventory(9);
        VirtualInventory added = new VirtualInventory(9);
        InventorySequence sequence = InventorySequence.of(present);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sequence.signal().onDirty(invalidations::incrementAndGet));
        sequence.add(added);

        assertEquals(1, invalidations.get());
        added.setItem(reason(), 0, diamonds(1));

        assertEquals(2, invalidations.get());
        sequence.remove(added);

        assertEquals(3, invalidations.get());
        added.setItem(reason(), 1, diamonds(1));

        assertEquals(3, invalidations.get());
    }

    @Test
    void retiredMemberLeavesTheSequence() {
        ReferencingInventory alive = ReferencingInventory.of(new ArrayStorage(9));
        ReferencingInventory dying = ReferencingInventory.of(new ArrayStorage(9));
        InventorySequence sequence = InventorySequence.of(alive, dying);
        dying.retire();

        assertEquals(List.of(alive), sequence.inventories());
    }

    @Test
    void droppingRetiredMemberInvalidatesTheSequenceOnce() {
        ReferencingInventory alive = ReferencingInventory.of(new ArrayStorage(9));
        ReferencingInventory dying = ReferencingInventory.of(new ArrayStorage(9));
        InventorySequence sequence = InventorySequence.of(alive, dying);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sequence.signal().onDirty(invalidations::incrementAndGet));
        dying.retire();
        int afterRetire = invalidations.get();
        sequence.inventories();

        assertEquals(afterRetire + 1, invalidations.get());
        sequence.inventories();
        sequence.inventories();

        assertEquals(afterRetire + 1, invalidations.get());
    }

    @Test
    void retiredMemberIsCollectedAfterItLeaves() {
        ReferencingInventory alive = ReferencingInventory.of(new ArrayStorage(9));
        ReferencingInventory dying = ReferencingInventory.of(new ArrayStorage(9));
        InventorySequence sequence = InventorySequence.of(alive, dying);
        this.bindings.bind(() -> sequence.signal().onDirty(() -> {
        }));
        WeakReference<ReferencingInventory> probe = new WeakReference<>(dying);
        dying.retire();
        dying = null;
        sequence.inventories();
        GcSupport.awaitCollected(probe);

        assertFalse(sequence.inventories().isEmpty());
    }

    @Test
    void retiredMemberLeavesTheLinksAndLaterMembersMoveUp() {
        VirtualInventory first = new VirtualInventory(2);
        ReferencingInventory dying = ReferencingInventory.of(new ArrayStorage(3));
        VirtualInventory last = new VirtualInventory(2);
        InventorySequence sequence = InventorySequence.of(first, dying, last);

        assertEquals(7, sequence.links().get().size());
        dying.retire();

        assertEquals(
                List.of(Element.inventory(first, 0), Element.inventory(first, 1), Element.inventory(last, 0), Element.inventory(last, 1)),
                sequence.links().get()
        );
    }

    @Test
    void linksOnlyInvalidateWhenTheAliveMembersChange() {
        VirtualInventory chest = new VirtualInventory(9);
        ReferencingInventory dying = ReferencingInventory.of(new ArrayStorage(9));
        InventorySequence sequence = InventorySequence.of(chest, dying);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sequence.links().onDirty(invalidations::incrementAndGet));
        chest.setItem(reason(), 0, diamonds(3));

        assertEquals(0, invalidations.get());
        dying.retire();

        assertEquals(1, invalidations.get());
        sequence.add(new VirtualInventory(9));

        assertEquals(2, invalidations.get());
    }

    @Test
    void pagedLinksCloseTheGapLeftByARetiredMember() {
        VirtualInventory first = new VirtualInventory(2);
        ReferencingInventory dying = ReferencingInventory.of(new ArrayStorage(2));
        VirtualInventory last = new VirtualInventory(2);
        InventorySequence sequence = InventorySequence.of(first, dying, last);
        Page<Element.InventoryLink> pages = Page.of(sequence.links(), 4);
        NormalPane pane = Pane.builder("VVVV")
                .addIngredient('V', pages, Function.identity(), Runnable::run)
                .build();
        pages.setPage(1);

        assertEquals(Element.inventory(last, 0), pane.element(0));
        dying.retire();

        assertEquals(0, pages.page().get());
        assertEquals(Element.inventory(first, 0), pane.element(0));
        assertEquals(Element.inventory(last, 0), pane.element(2));
        assertEquals(Element.inventory(last, 1), pane.element(3));
    }

    @Test
    void addAndRemoveReportWhetherTheyChangedAnything() {
        VirtualInventory chest = new VirtualInventory(9);
        InventorySequence sequence = InventorySequence.of();

        assertTrue(sequence.add(chest));
        assertFalse(sequence.add(chest));
        assertTrue(sequence.remove(chest));
        assertFalse(sequence.remove(chest));
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static final class ArrayStorage implements ExternalStorage {
        private final @org.jetbrains.annotations.Nullable ItemStack[] contents;
        private ArrayStorage(int size) {
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
    }
}
