package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.state.GcSupport;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class InventoryContentSignalTest {

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
    void contentSignalIsCreatedOnFirstCallAndReused() {
        VirtualInventory inventory = new VirtualInventory(3);

        assertNull(inventory.updateChannelIfPresent(), "从未调用 contentSignal() 时不应创建事务订阅器");
        Signal<Long> first = inventory.contentSignal();

        assertNotNull(inventory.updateChannelIfPresent(), "contentSignal() 靠 post 订阅驱动, 应已创建事务订阅器");
        assertSame(first, inventory.contentSignal(), "contentSignal() 应恒返回同一个实例");
    }

    @Test
    void commitInvalidatesContentSignalOncePerTransaction() {
        VirtualInventory inventory = new VirtualInventory(3);
        AtomicInteger invalidations = new AtomicInteger();
        Subscription subscription = inventory.contentSignal().onDirty(invalidations::incrementAndGet);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, diamonds(1));

        assertEquals(1, invalidations.get(), "一笔事务应恰好触发一次失效");
        inventory.setItem(UpdateReason.Program.INSTANCE, 1, diamonds(2));

        assertEquals(2, invalidations.get(), "第二笔事务再触发一次");
        assertFalse(subscription.isClosed());
    }

    @Test
    void operationWithoutSlotChangesDoesNotInvalidate() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, diamonds(SparrowInventory.DEFAULT_MAX_STACK_SIZE));
        AtomicInteger invalidations = new AtomicInteger();
        Subscription subscription = inventory.contentSignal().onDirty(invalidations::incrementAndGet);
        AddResult add = inventory.tryAdd(UpdateReason.Program.INSTANCE, new ItemStack(Material.COAL, 3));

        assertEquals(3, add.remaining(), "前提: 这次 add 确实一件都没放进去");
        assertEquals(0, invalidations.get(), "没有槽位变更的操作不应触发失效");
        assertFalse(subscription.isClosed());
    }

    @Test
    void externalStorageChangeInvalidatesContentSignal() {
        org.bukkit.inventory.Inventory chest = Bukkit.createInventory(null, 9);
        ReferencingInventory referencing = ReferencingInventory.create(
                chest,
                org.bukkit.inventory.Inventory::getContents,
                java.util.function.UnaryOperator.identity(),
                false
        );
        AtomicInteger invalidations = new AtomicInteger();
        Subscription subscription = referencing.contentSignal().onDirty(invalidations::incrementAndGet);
        chest.setItem(4, diamonds(6));
        referencing.refresh();

        assertEquals(1, invalidations.get(), "外部变更被吸收后同样应递增");
        assertFalse(subscription.isClosed());
    }

    @Test
    void derivedSignalRecomputesFromLatestContent() {
        VirtualInventory inventory = new VirtualInventory(3);
        MutableSignal<Material> query = Signal.of(Material.DIAMOND);
        Signal<Integer> matches = Signals.combine(
                inventory.contentSignal(),
                query,
                (ignoredRevision, material) -> countOf(inventory, material)
        );

        assertEquals(0, matches.get());
        inventory.setItem(UpdateReason.Program.INSTANCE, 0, diamonds(5));

        assertEquals(5, matches.get(), "提交后派生应读到新内容");
        inventory.setItem(UpdateReason.Program.INSTANCE, 2, new ItemStack(Material.COAL, 7));

        assertEquals(5, matches.get(), "另一种物品不影响钻石计数");
        query.set(Material.COAL);

        assertEquals(7, matches.get(), "查询条件变化同样驱动重算");
    }

    @Test
    void heldSubscriptionDoesNotKeepInventoryAlive() {
        VirtualInventory inventory = new VirtualInventory(3);
        Subscription subscription = inventory.contentSignal().onDirty(() -> {
        });
        WeakReference<SparrowInventory> probe = new WeakReference<>(inventory);
        inventory = null;
        GcSupport.awaitCollected(probe);

        assertFalse(subscription.isClosed(), "Inventory 被回收不影响凭证自身");
    }

    private static int countOf(SparrowInventory inventory, Material material) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack item = inventory.itemAt(slot);
            if (item != null && item.getType() == material) {
                total += ItemUtils.amountOf(item);
            }
        }
        return total;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
