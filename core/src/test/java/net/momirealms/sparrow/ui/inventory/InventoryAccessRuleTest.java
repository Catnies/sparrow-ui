package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryAccessRuleTest {
    private List<Throwable> errors;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.errors = new ArrayList<>();
        SparrowUI.getInstance().setExceptionHandler((message, error) -> this.errors.add(error));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        assertTrue(this.errors.isEmpty(), this.errors.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exactRequests")
    void exactRequestsRejectBeforePreAndKeepAllSlots(String name, Function<VirtualInventory, TransactionResult> request) {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(4), diamonds(5)});
        ItemStack[] original = inventory.snapshot();
        AtomicInteger pre = new AtomicInteger();
        AtomicInteger post = new AtomicInteger();
        inventory.setAccessRule(0, context -> false);
        inventory.subscribePreUpdate(event -> pre.incrementAndGet());
        inventory.subscribePostUpdate(event -> post.incrementAndGet());

        assertSame(TransactionResult.Cancelled.INSTANCE, request.apply(inventory));
        assertArrayEquals(original, inventory.snapshot());
        assertEquals(0, pre.get());
        assertEquals(0, post.get());
        inventory.clear();
        assertTrue(inventory.isEmpty());
        assertEquals(1, post.get());
    }

    static Stream<Arguments> exactRequests() {
        return Stream.of(
                Arguments.of("set", (Function<VirtualInventory, TransactionResult>) inventory -> inventory.trySetItem(0, diamonds(2))),
                Arguments.of("modify", (Function<VirtualInventory, TransactionResult>) inventory -> inventory.tryModifyItem(0, item -> null)),
                Arguments.of("amount", (Function<VirtualInventory, TransactionResult>) inventory -> inventory.tryChangeAmount(0, -1)),
                Arguments.of("clear", (Function<VirtualInventory, TransactionResult>) VirtualInventory::tryClear)
        );
    }

    @Test
    void bulkRequestsSkipRejectedChangesAndReturnActualPlannedCounts() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(4), diamonds(2)});
        inventory.setMaxStackSizes(new int[]{5, 5});
        inventory.setAccessRule(0, context -> false);

        assertEquals(3, inventory.tryPutItem(0, diamonds(3)).remaining());
        assertEquals(5, inventory.tryAdd(diamonds(8)).remaining());
        assertEquals(5, inventory.tryCollect(diamonds(1), 8).collected());
        assertEquals(4, inventory.itemAmount(0));
        inventory.setItem(1, diamonds(3));
        assertEquals(3, inventory.tryRemove(item -> true, 8).removed());
        assertEquals(4, inventory.itemAmount(0));
    }

    @Test
    void contextDescribesActualFlowAndPreservesProgramReason() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(4)});
        UpdateReason reason = new UpdateReason() {};
        List<AccessContext> seen = new ArrayList<>();
        inventory.setAccessRule(context -> {
            seen.add(context);
            return true;
        });
        inventory.tryChangeAmount(reason, 0, -2);
        inventory.trySetItem(reason, 0, new ItemStack(Material.EMERALD, 3));

        AccessContext take = seen.get(0);
        assertSame(inventory, take.inventory());
        assertSame(reason, take.reason());
        assertEquals(0, take.slot());
        assertNull(take.player());
        assertNull(take.window());
        assertEquals(diamonds(4), take.before());
        assertEquals(diamonds(2), take.after());
        assertFalse(take.isAdd());
        assertTrue(take.isRemove());
        assertEquals(diamonds(2), take.removedItem());
        AccessContext swap = seen.get(1);
        assertEquals(diamonds(2), swap.removedItem());
        assertEquals(new ItemStack(Material.EMERALD, 3), swap.addedItem());
    }

    @Test
    void ownerAndMaterialConstraintsBothApplyWhileProgramAuthorityBypassesThem() {
        var owner = MockBukkit.getMock().addPlayer();
        var visitor = MockBukkit.getMock().addPlayer();
        UpdateReason ownerReason = new PlayerUpdateReason.Click(owner, ClickType.LEFT, -1);
        UpdateReason visitorReason = new PlayerUpdateReason.Click(visitor, ClickType.LEFT, -1);
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setAccessRule(context -> context.player() == owner);
        inventory.setAccessRule(0, context -> !context.isAdd() || context.addedItem().getType() == Material.DIAMOND);

        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.trySetItem(visitorReason, 0, diamonds(1)));
        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.trySetItem(ownerReason, 0, new ItemStack(Material.EMERALD)));
        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(ownerReason, 0, diamonds(2)));
        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.tryClear(visitorReason));
        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.tryClear());
        inventory.clear();
        assertTrue(inventory.isEmpty());
    }

    @Test
    void preCanReplaceApprovedInputAndIncludeARuleProtectedRewardInventory() {
        VirtualInventory input = new VirtualInventory(1);
        VirtualInventory reward = new VirtualInventory(1);
        input.setAccessRule(context -> context.isAdd() && context.addedItem().getType() == Material.STONE);
        reward.setAccessRule(context -> false);
        AtomicInteger rewardPre = new AtomicInteger();
        reward.subscribePreUpdate(event -> rewardPre.incrementAndGet());
        input.subscribePreUpdate(event -> {
            event.setAfter(0, null);
            event.include(reward);
            event.setAfter(reward, 0, diamonds(1));
        });

        assertInstanceOf(TransactionResult.Committed.class, input.trySetItem(0, new ItemStack(Material.STONE)));
        assertTrue(input.isEmpty());
        assertEquals(diamonds(1), reward.itemAt(0));
        assertEquals(0, rewardPre.get());
    }

    @Test
    void simulationsSeparateCapacityFromRequestPermissionWithoutEventsOrRefresh() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3), null});
        inventory.setMaxStackSizes(new int[]{5, 5});
        AtomicInteger rules = new AtomicInteger();
        inventory.setAccessRule(context -> {
            rules.incrementAndGet();
            return context.slot() == 1;
        });
        AtomicInteger events = new AtomicInteger();
        inventory.subscribePreUpdate(event -> events.incrementAndGet());
        inventory.subscribePostUpdate(event -> events.incrementAndGet());

        assertEquals(0, inventory.simulateAdd(diamonds(7)));
        assertArrayEquals(new int[]{0, 1}, inventory.simulateAdd(List.of(diamonds(4), diamonds(4))));
        assertEquals(3, inventory.simulateCollect(diamonds(1), 9));
        assertTrue(inventory.mayPlace(diamonds(7)));
        assertTrue(inventory.mayPickup(diamonds(3)));
        assertEquals(0, rules.get());
        assertEquals(2, inventory.simulateTryAdd(diamonds(7)));
        assertArrayEquals(new int[]{0, 3}, inventory.simulateTryAdd(diamonds(4), diamonds(4)));
        assertEquals(0, inventory.simulateTryCollect(diamonds(1), 9));
        assertEquals(0, events.get());
        assertEquals(3, inventory.itemAmount(0));
        assertNull(inventory.itemAt(1));
    }

    @Test
    void frozenRequestSimulationUsesPlayerReasonButProgramAndCapacityStillWork() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3), null});
        inventory.frozen(true);
        UpdateReason playerReason = new PlayerUpdateReason.Click(MockBukkit.getMock().addPlayer(), ClickType.LEFT, -1);
        assertEquals(4, inventory.simulateTryAdd(playerReason, diamonds(4)));
        assertArrayEquals(new int[]{4, 2}, inventory.simulateTryAdd(playerReason, List.of(diamonds(4), diamonds(2))));
        assertEquals(0, inventory.simulateTryCollect(playerReason, diamonds(1), 3));
        assertEquals(0, inventory.simulateTryAdd(diamonds(4)));
        assertEquals(3, inventory.simulateTryCollect(diamonds(1), 3));
        assertEquals(0, inventory.simulateAdd(diamonds(4)));
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
