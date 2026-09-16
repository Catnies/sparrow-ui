package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRequestsTest {
    private static final UpdateReason CUSTOM_REASON = new UpdateReason() {};

    private List<Throwable> reportedExceptions;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.reportedExceptions = new ArrayList<>();
        SparrowUI.getInstance().setExceptionHandler((message, exception) -> this.reportedExceptions.add(exception));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        assertTrue(this.reportedExceptions.isEmpty(), this.reportedExceptions.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void explicitRequestsPreserveReasonAndCommitOnce(String name, BiFunction<VirtualInventory, UpdateReason, TransactionResult> request, Function<VirtualInventory, TransactionResult> programRequest) {
        VirtualInventory inventory = preparedInventory();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            assertSame(CUSTOM_REASON, event.reason());
            preCalls.incrementAndGet();
        });
        inventory.subscribePostUpdate(event -> {
            assertSame(CUSTOM_REASON, event.reason());
            postCalls.incrementAndGet();
        });

        TransactionResult.Committed result = assertInstanceOf(TransactionResult.Committed.class, request.apply(inventory, CUSTOM_REASON));

        assertEquals(1, result.rootChanges().size());
        assertSame(inventory, result.rootChanges().getFirst().inventory());
        assertFalse(result.rootChanges().getFirst().slotChanges().isEmpty());
        assertEquals(1, preCalls.get());
        assertEquals(1, postCalls.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void programRequestsHonorCancellationWithoutWritingOrPublishingPost(String name, BiFunction<VirtualInventory, UpdateReason, TransactionResult> request, Function<VirtualInventory, TransactionResult> programRequest) {
        VirtualInventory inventory = preparedInventory();
        ItemStack[] before = inventory.snapshot();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            assertSame(UpdateReason.Program.INSTANCE, event.reason());
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertSame(TransactionResult.Cancelled.INSTANCE, programRequest.apply(inventory));

        assertArrayEquals(before, inventory.snapshot());
        assertEquals(1, preCalls.get());
        assertEquals(0, postCalls.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void staleRequestsDoNotReplayPreOrOverwriteItsIndependentWrite(String name, BiFunction<VirtualInventory, UpdateReason, TransactionResult> request, Function<VirtualInventory, TransactionResult> programRequest) {
        VirtualInventory inventory = preparedInventory();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            preCalls.incrementAndGet();
            inventory.setItem(2, new ItemStack(Material.EMERALD));
        });
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertSame(TransactionResult.Conflicted.INSTANCE, request.apply(inventory, CUSTOM_REASON));

        assertEquals(diamonds(10), inventory.itemAt(0));
        assertEquals(new ItemStack(Material.EMERALD), inventory.itemAt(2));
        assertEquals(1, preCalls.get());
        assertEquals(1, postCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedBulkRequestsReportFullRemainingAndZeroTaken(boolean cancel) {
        VirtualInventory inventory = preparedInventory();
        inventory.subscribePreUpdate(event -> {
            if (cancel) {
                event.setCancelled(true);
            } else {
                inventory.setItem(2, new ItemStack(Material.EMERALD));
            }
        });
        TransactionResult expected = cancel ? TransactionResult.Cancelled.INSTANCE : TransactionResult.Conflicted.INSTANCE;

        AddResult added = inventory.tryAdd(diamonds(3));
        AddResult placed = inventory.tryPutItem(0, diamonds(3));
        CollectResult collected = inventory.tryCollect(diamonds(1), 3);
        RemoveResult removed = inventory.tryRemove(item -> item.getType() == Material.DIAMOND, 3);

        assertSame(expected, added.result());
        assertSame(expected, placed.result());
        assertSame(expected, collected.result());
        assertSame(expected, removed.result());
        assertEquals(3, added.remaining());
        assertEquals(3, placed.remaining());
        assertEquals(0, collected.collected());
        assertEquals(0, removed.removed());
        assertEquals(diamonds(10), inventory.itemAt(0));
    }

    @Test
    void requestsKeepPlacementFilteringAndPartialCounts() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSize(0, 8);
        inventory.setAccessRule(1, context -> false);
        inventory.setItem(0, diamonds(5));

        AddResult added = inventory.tryAdd(diamonds(7));
        CollectResult collected = inventory.tryCollect(diamonds(1), 5);
        RemoveResult removed = inventory.tryRemove(item -> item.getType() == Material.DIAMOND, 2);

        assertInstanceOf(TransactionResult.Committed.class, added.result());
        assertInstanceOf(TransactionResult.Committed.class, collected.result());
        assertInstanceOf(TransactionResult.Committed.class, removed.result());
        assertEquals(4, added.remaining());
        assertEquals(5, collected.collected());
        assertEquals(2, removed.removed());
        assertEquals(diamonds(1), inventory.itemAt(0));
    }

    @Test
    void conflictedModifierRunsOnceAndEditsOnlyItsCopy() {
        VirtualInventory inventory = preparedInventory();
        AtomicInteger modifierCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> inventory.setItem(2, diamonds(1)));

        TransactionResult result = inventory.tryModifyItem(0, item -> {
            modifierCalls.incrementAndGet();
            item.setAmount(2);
            return item;
        });

        assertSame(TransactionResult.Conflicted.INSTANCE, result);
        assertEquals(1, modifierCalls.get());
        assertEquals(diamonds(10), inventory.itemAt(0));
    }

    private static VirtualInventory preparedInventory() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setItem(0, diamonds(10));
        return inventory;
    }

    private static Stream<Arguments> requests() {
        return Stream.of(
                request("setItem", (inventory, reason) -> inventory.trySetItem(reason, 0, diamonds(3)), inventory -> inventory.trySetItem(0, diamonds(3))),
                request("putItem", (inventory, reason) -> inventory.tryPutItem(reason, 0, diamonds(3)).result(), inventory -> inventory.tryPutItem(0, diamonds(3)).result()),
                request("modifyItem", (inventory, reason) -> inventory.tryModifyItem(reason, 0, item -> diamonds(3)), inventory -> inventory.tryModifyItem(0, item -> diamonds(3))),
                request("changeAmount", (inventory, reason) -> inventory.tryChangeAmount(reason, 0, 3), inventory -> inventory.tryChangeAmount(0, 3)),
                request("add", (inventory, reason) -> inventory.tryAdd(reason, diamonds(3)).result(), inventory -> inventory.tryAdd(diamonds(3)).result()),
                request("collect", (inventory, reason) -> inventory.tryCollect(reason, diamonds(1), 3).result(), inventory -> inventory.tryCollect(diamonds(1), 3).result()),
                request("remove", (inventory, reason) -> inventory.tryRemove(reason, item -> true, 3).result(), inventory -> inventory.tryRemove(item -> true, 3).result()),
                request("clear", VirtualInventory::tryClear, VirtualInventory::tryClear)
        );
    }

    private static Arguments request(String name, BiFunction<VirtualInventory, UpdateReason, TransactionResult> request, Function<VirtualInventory, TransactionResult> programRequest) {
        return Arguments.of(name, request, programRequest);
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
