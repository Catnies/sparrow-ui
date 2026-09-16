package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottleGuardCleanupTest {

    private ServerMock server;
    private Player player;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.player = this.server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void stateIsLazyAndFirstPlayerStaysInline() throws ReflectiveOperationException {
        ThrottleGuard guard = new ThrottleGuard(10, () -> 0L);
        Item item = Item.builder().build();

        assertNull(timestamps(guard));
        assertTrue(guard.test(item, click(this.player)));
        Object itemTimestamps = itemTimestamps(guard, item);

        assertEquals(this.player.getUniqueId(), playerId(itemTimestamps));
        assertEquals(0, timestamp(itemTimestamps));
        assertNull(shared(itemTimestamps));
    }

    @Test
    void singlePlayerUpdatesTimestampWithoutCreatingMap() throws ReflectiveOperationException {
        AtomicLong time = new AtomicLong();
        ThrottleGuard guard = new ThrottleGuard(10, time::get);
        Item item = Item.builder().build();

        assertTrue(guard.test(item, click(this.player)));
        time.set(5);

        assertFalse(guard.test(item, click(this.player)));
        time.set(10);

        assertTrue(guard.test(item, click(this.player)));
        Object itemTimestamps = itemTimestamps(guard, item);

        assertEquals(this.player.getUniqueId(), playerId(itemTimestamps));
        assertEquals(10, timestamp(itemTimestamps));
        assertNull(shared(itemTimestamps));
    }

    @Test
    void expiredFirstPlayerIsReplacedWithoutCreatingMap() throws ReflectiveOperationException {
        AtomicLong time = new AtomicLong();
        ThrottleGuard guard = new ThrottleGuard(10, time::get);
        Item item = Item.builder().build();
        Player second = this.server.addPlayer();

        assertTrue(guard.test(item, click(this.player)));
        time.set(10);

        assertTrue(guard.test(item, click(second)));
        Object itemTimestamps = itemTimestamps(guard, item);

        assertEquals(second.getUniqueId(), playerId(itemTimestamps));
        assertEquals(10, timestamp(itemTimestamps));
        assertNull(shared(itemTimestamps));
    }

    @Test
    void secondActivePlayerCreatesSharedMap() throws ReflectiveOperationException {
        AtomicLong time = new AtomicLong();
        ThrottleGuard guard = new ThrottleGuard(10, time::get);
        Item item = Item.builder().build();
        Player second = this.server.addPlayer();

        assertTrue(guard.test(item, click(this.player)));
        time.set(5);

        assertTrue(guard.test(item, click(second)));
        HashMap<UUID, Long> shared = shared(itemTimestamps(guard, item));

        assertNotNull(shared);
        assertEquals(Set.of(this.player.getUniqueId(), second.getUniqueId()), shared.keySet());
    }

    @Test
    void sharedStateDropsExpiredPlayersAndReturnsToInline() throws ReflectiveOperationException {
        AtomicLong time = new AtomicLong();
        ThrottleGuard guard = new ThrottleGuard(10, time::get);
        Item item = Item.builder().build();
        Player second = this.server.addPlayer();

        assertTrue(guard.test(item, click(this.player)));
        time.set(5);

        assertTrue(guard.test(item, click(second)));
        time.set(10);

        assertFalse(guard.test(item, click(second)));
        Object itemTimestamps = itemTimestamps(guard, item);

        assertEquals(second.getUniqueId(), playerId(itemTimestamps));
        assertEquals(5, timestamp(itemTimestamps));
        assertNull(shared(itemTimestamps));
    }

    @Test
    void sharedStateReturnsToInlineWhenAllPlayersExpire() throws ReflectiveOperationException {
        AtomicLong time = new AtomicLong();
        ThrottleGuard guard = new ThrottleGuard(10, time::get);
        Item item = Item.builder().build();
        Player second = this.server.addPlayer();

        assertTrue(guard.test(item, click(this.player)));
        time.set(5);

        assertTrue(guard.test(item, click(second)));
        time.set(15);

        assertTrue(guard.test(item, click(this.player)));
        Object itemTimestamps = itemTimestamps(guard, item);

        assertEquals(this.player.getUniqueId(), playerId(itemTimestamps));
        assertEquals(15, timestamp(itemTimestamps));
        assertNull(shared(itemTimestamps));
    }

    @Test
    void concurrentFirstClicksAcceptOnlyOne() throws Exception {
        int workers = 8;
        ThrottleGuard guard = new ThrottleGuard(10, () -> 0L);
        Item item = Item.builder().build();
        ItemClick click = click(this.player);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>(workers);
        try {
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();

                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return guard.test(item, click);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }

            assertEquals(1, accepted);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static ItemClick click(Player player) {
        return new ItemClick(player, ClickType.LEFT, new WindowStub(player), ItemStack.empty(), 0);
    }

    @SuppressWarnings("unchecked")
    private static WeakHashMap<Item, ?> timestamps(ThrottleGuard guard) throws ReflectiveOperationException {
        Field field = ThrottleGuard.class.getDeclaredField("timestamps");
        field.setAccessible(true);
        return (WeakHashMap<Item, ?>) field.get(guard);
    }

    private static Object itemTimestamps(ThrottleGuard guard, Item item) throws ReflectiveOperationException {
        WeakHashMap<Item, ?> timestamps = timestamps(guard);

        assertNotNull(timestamps);
        Object itemTimestamps = timestamps.get(item);

        assertNotNull(itemTimestamps);
        return itemTimestamps;
    }

    private static UUID playerId(Object itemTimestamps) throws ReflectiveOperationException {
        return (UUID) field(itemTimestamps.getClass(), "playerId").get(itemTimestamps);
    }

    private static long timestamp(Object itemTimestamps) throws ReflectiveOperationException {
        return field(itemTimestamps.getClass(), "timestamp").getLong(itemTimestamps);
    }

    @SuppressWarnings("unchecked")
    private static HashMap<UUID, Long> shared(Object itemTimestamps) throws ReflectiveOperationException {
        return (HashMap<UUID, Long>) field(itemTimestamps.getClass(), "shared").get(itemTimestamps);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
