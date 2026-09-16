package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.TransactionResult;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.click.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionDraftTest {

    private PlayerMock player;
    private List<Throwable> reportedExceptions;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        this.player = server.addPlayer();
        this.reportedExceptions = new CopyOnWriteArrayList<>();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> this.reportedExceptions.add(throwable));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void writtenCursorIsAlwaysIsolated() {
        FakeContext context = new FakeContext();
        ItemStack handlerItem = new ItemStack(Material.DIAMOND, 3);
        InteractionDraft handlerDraft = InteractionDraft.empty();
        handlerDraft.cursor(handlerItem);
        handlerDraft.seal();
        handlerDraft.apply(context);

        assertNotSame(handlerItem, context.cursor);
        assertEquals(3, context.cursor.getAmount());
        ItemStack plannedItem = new ItemStack(Material.EMERALD, 2);
        InteractionDraft plannerDraft = InteractionDraft.cursorAfter(plannedItem);
        plannerDraft.seal();
        plannerDraft.apply(context);

        assertNotSame(plannedItem, context.cursor);
        assertEquals(2, context.cursor.getAmount());
    }

    @Test
    void preHandlerShrinksTheSlotAndReturnsTheRemainderToTheCursor() {
        VirtualInventory inventory = new VirtualInventory(1);
        InteractionDraft interaction = InteractionDraft.cursorAfter(ItemStack.empty());
        inventory.subscribePreUpdate(event -> {
            InteractionDraft draft = event.interaction();

            assertNotNull(draft);
            assertTrue(draft.cursor().isEmpty());
            event.setAfter(0, coal(10));
            draft.cursor(coal(54));
        });
        TransactionResult result = this.commit(inventory, coal(64), interaction);

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(0)));
        FakeContext context = new FakeContext();
        interaction.apply(context);

        assertEquals(54, context.cursor.getAmount());
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void frameworkNeverRefillsWhatAHandlerDroppedOnItsOwn() {
        VirtualInventory inventory = new VirtualInventory(1);
        InteractionDraft interaction = InteractionDraft.cursorAfter(ItemStack.empty());
        inventory.subscribePreUpdate(event -> event.setAfter(0, coal(10)));

        assertInstanceOf(TransactionResult.Committed.class, this.commit(inventory, coal(64), interaction));
        FakeContext context = new FakeContext();
        interaction.apply(context);

        assertTrue(context.cursor.isEmpty());
    }

    @Test
    void laterHandlersSeeWhatEarlierHandlersWroteIntoTheSameDraft() {
        VirtualInventory inventory = new VirtualInventory(1);
        InteractionDraft interaction = InteractionDraft.cursorAfter(ItemStack.empty());
        AtomicReference<ItemStack> observed = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> event.interaction().cursor(coal(54)));
        inventory.subscribePreUpdate(event -> {
            InteractionDraft draft = event.interaction();
            observed.set(draft.cursor());
            draft.offhand(coal(1));
            draft.drop(coal(2));
            draft.drop(coal(3));
        });

        assertInstanceOf(TransactionResult.Committed.class, this.commit(inventory, coal(64), interaction));
        assertEquals(54, ItemUtils.amountOf(observed.get()));
        FakeContext context = new FakeContext();
        interaction.apply(context);

        assertEquals(54, context.cursor.getAmount());
        assertEquals(1, ItemUtils.amountOf(context.offhand));
        assertEquals(List.of(2, 3), amountsOf(context.drops));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void apiWritesAndExternalSyncExposeNoInteractionDraft() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger preCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            preCalls.incrementAndGet();

            assertNull(event.interaction());
        });

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(UpdateReason.Program.INSTANCE, 0, coal(1)));
        assertEquals(1, preCalls.get());
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void draftRejectsWritesOnceTheTransactionLeavesItsPreStage() {
        VirtualInventory inventory = new VirtualInventory(1);
        InteractionDraft interaction = InteractionDraft.cursorAfter(ItemStack.empty());
        AtomicReference<InteractionDraft> escaped = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> escaped.set(event.interaction()));

        assertInstanceOf(TransactionResult.Committed.class, this.commit(inventory, coal(64), interaction));
        InteractionDraft draft = escaped.get();

        assertSame(interaction, draft);
        assertThrows(IllegalStateException.class, () -> draft.cursor(coal(1)));
        assertThrows(IllegalStateException.class, () -> draft.offhand(coal(1)));
        assertThrows(IllegalStateException.class, () -> draft.drop(coal(1)));
    }

    @Test
    void escapedEventCannotReachTheDraftAfterItsHandlerReturns() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicReference<InventoryPreUpdateEvent> escaped = new AtomicReference<>();
        inventory.subscribePreUpdate(escaped::set);

        assertInstanceOf(TransactionResult.Committed.class,
                this.commit(inventory, coal(64), InteractionDraft.cursorAfter(ItemStack.empty())));

        assertThrows(IllegalStateException.class, () -> escaped.get().interaction());
    }

    @Test
    void cancelledTransactionNeitherCommitsNorRunsTheDraft() {
        VirtualInventory inventory = new VirtualInventory(1);
        InteractionDraft interaction = InteractionDraft.cursorAfter(ItemStack.empty());
        AtomicInteger callbackCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            event.interaction().cursor(coal(54));
            event.setCancelled(true);
        });
        PlannedRoot basis = inventory.openPlan();
        TransactionResult result = InventoryTransactions.commit(
                this.reason(),
                new TransactionDraft(List.of(new TransactionScope(basis, List.of(new SlotChange(0, basis.planned()[0], coal(64)))))),
                interaction,
                false,
                callbackCalls::incrementAndGet,
                List.of(),
                () -> true
        );

        assertSame(TransactionResult.Cancelled.INSTANCE, result);
        assertNull(inventory.itemAt(0));
        assertEquals(0, callbackCalls.get());
        assertEquals(0, this.reportedExceptions.size());
    }

    private TransactionResult commit(VirtualInventory inventory, ItemStack after, InteractionDraft interaction) {
        PlannedRoot basis = inventory.openPlan();
        TransactionScope scope = new TransactionScope(basis, List.of(new SlotChange(0, basis.planned()[0], after)));
        return InventoryTransactions.commit(
                this.reason(),
                new TransactionDraft(List.of(scope)),
                interaction,
                false,
                null,
                List.of(),
                () -> true
        );
    }

    private UpdateReason reason() {
        return new PlayerUpdateReason.Click(this.player, ClickType.LEFT, -1);
    }

    private static ItemStack coal(int amount) {
        return new ItemStack(Material.COAL, amount);
    }

    private static List<Integer> amountsOf(List<ItemStack> items) {
        List<Integer> amounts = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            amounts.add(items.get(i).getAmount());
        }
        return List.copyOf(amounts);
    }

    private static final class FakeContext implements ClickSemantics.Context {
        private ItemStack cursor = ItemStack.empty();
        @Nullable private ItemStack offhand;
        private final List<ItemStack> drops = new ArrayList<>();
        @Override
        @NotNull
        public ItemStack cursor() {
            return this.cursor;
        }
        @Override
        public void cursor(@NotNull ItemStack cursor) {
            this.cursor = ItemUtils.copyOrEmpty(cursor);
        }
        @Override
        @Nullable
        public ItemStack offhand() {
            return this.offhand;
        }
        @Override
        public void offhand(@Nullable ItemStack item) {
            this.offhand = item;
        }
        @Override
        public void drop(@NotNull ItemStack item) {
            this.drops.add(item);
        }
        @Override
        @NotNull
        public Player viewer() {
            throw new UnsupportedOperationException();
        }
        @Override
        @Nullable
        public ClickSemantics.LinkedSlot linkAt(int windowSlot) {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean frozenAt(int windowSlot) {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean displayedEmptyAt(int windowSlot) {
            throw new UnsupportedOperationException();
        }
        @Override
        @Nullable
        public ClickSemantics.LinkedSlot hotbarLink(int hotbarButton) {
            throw new UnsupportedOperationException();
        }
        @Override
        @NotNull
        public List<ClickSemantics.LinkedInventory> linkedInventories() {
            throw new UnsupportedOperationException();
        }
        @Override
        public void markDirty(int windowSlot) {
            throw new UnsupportedOperationException();
        }
    }
}
