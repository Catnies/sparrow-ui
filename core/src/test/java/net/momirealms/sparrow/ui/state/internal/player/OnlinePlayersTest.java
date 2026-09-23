package net.momirealms.sparrow.ui.state.internal.player;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.AttachSupport;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.time.ManualDelayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlinePlayersTest {
    private PlayerSignalRuntime runtime;
    private ManualDelayer delayer;
    private ListSignal<Player> players;

    @BeforeEach
    void setUp() {
        PlayerSignalTestRuntime.install();
        this.runtime = PlayerSignalTestRuntime.runtime();
        this.delayer = PlayerSignalTestRuntime.delayer();
        this.players = Signals.onlinePlayers();
    }

    @AfterEach
    void tearDown() {
        PlayerSignalTestRuntime.restore();
    }

    @Test
    @SuppressWarnings("removal")
    void eventsImmediatelyUpdateReadsAndMappedValuesButNotifyNextTick() {
        assertSame(this.players, Signals.onlinePlayers());
        assertSame(this.players, this.players.asReadOnly());
        assertFalse(this.players instanceof MutableListSignal<?>);
        Signal<Integer> count = this.players.map(List::size);
        AtomicInteger notifications = new AtomicInteger();
        assertEquals(0, count.get());
        long version = this.runtime.version();
        try (Subscription ignored = count.onDirty(notifications::incrementAndGet)) {
            Player first = MockBukkit.getMock().addPlayer();
            assertEquals(version + 1, this.runtime.version());
            assertEquals(List.of(first), this.players.get());
            assertEquals(1, count.get());
            assertEquals(0, notifications.get());
            this.delayer.advance(1);
            assertEquals(1, notifications.get());

            // 退出事件发出时服务端仍持有该玩家, 名单已经按事件更新
            Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(first, Component.empty()));
            assertTrue(Bukkit.getOnlinePlayers().contains(first));
            assertTrue(this.players.isEmpty());
            assertEquals(0, count.get());
            assertEquals(1, notifications.get());
            this.delayer.advance(1);
            assertEquals(2, notifications.get());
            assertEquals(0, this.delayer.pending());
        }
    }

    @Test
    void initializationIncludesAlreadyOnlinePlayersWithoutScheduling() {
        Player first = MockBukkit.getMock().addPlayer();
        Player second = MockBukkit.getMock().addPlayer();
        this.runtime.close();
        try (PlayerSignalRuntime initialized = new PlayerSignalRuntime(MockBukkit.createMockPlugin(), this.delayer)) {
            initialized.initialize();
            assertEquals(List.of(first, second), initialized.onlinePlayers().get());
            assertSame(initialized.get(), initialized.get());
            assertEquals(0, this.delayer.pending());
        }
    }

    @Test
    void snapshotsAndIteratorsRetainTheirMembershipAcrossChanges() {
        Player first = MockBukkit.getMock().addPlayer();
        List<Player> snapshot = this.players.get();
        var iterator = this.players.iterator();
        List<Player> subList = this.players.subList(0, 1);
        List<Player> reversed = this.players.reversed();
        var stream = this.players.stream();
        assertSame(snapshot, this.players.get());
        assertNotSame(this.players, snapshot);
        Player second = MockBukkit.getMock().addPlayer();
        this.quit(first);
        assertEquals(List.of(second), this.players.get());
        assertEquals(List.of(first), snapshot);
        assertSame(first, iterator.next());
        assertFalse(iterator.hasNext());
        assertEquals(snapshot, subList);
        assertEquals(snapshot, reversed);
        assertEquals(snapshot, stream.toList());
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, subList::clear);
        assertThrows(UnsupportedOperationException.class, () -> reversed.add(second));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.set(0, second));
    }

    @Test
    void burstOfTwoHundredJoinsAtEightHundredOnlineBuildsOneSnapshotOnDemand() throws Exception {
        for (int index = 0; index < 800; index++) {
            this.join(AttachSupport.player());
        }
        List<Player> initial = this.players.get();
        assertEquals(800, initial.size());
        this.delayer.advance(1);
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = this.players.onDirty(notifications::incrementAndGet);
             var workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> joins = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                Player player = AttachSupport.player();
                joins.add(workers.submit(() -> this.join(player)));
            }
            for (int index = 0; index < joins.size(); index++) {
                joins.get(index).get(5, TimeUnit.SECONDS);
            }
            assertEquals(1_000, this.runtime.version());
            assertNull(this.cachedSnapshot());
            assertEquals(1, this.delayer.pending());
            assertEquals(0, notifications.get());
            this.delayer.advance(1);
            assertEquals(1, notifications.get());
            assertNull(this.cachedSnapshot());
            List<Player> current = this.players.get();
            assertEquals(1_000, current.size());
            assertEquals(1_000, new HashSet<>(current).size());
            assertSame(current, this.cachedSnapshot());
            assertSame(current, this.players.get());
            assertEquals(800, initial.size());
            assertEquals(0, this.delayer.pending());
        }
    }

    @Test
    void readsBetweenEventsSeeEveryIntermediateState() {
        Player first = AttachSupport.player();
        Player second = AttachSupport.player();
        this.join(first);
        List<Player> one = this.players.get();
        this.join(second);
        List<Player> two = this.players.get();
        this.quit(first);
        assertEquals(List.of(second), this.players.get());
        assertEquals(List.of(first), one);
        assertEquals(List.of(first, second), two);
        assertEquals(1, this.delayer.pending());
    }

    @Test
    void duplicateEventsDoNotInvalidateAndOldQuitPreservesReplacement() {
        UUID uuid = UUID.randomUUID();
        Player first = this.player(uuid);
        Player replacement = this.player(uuid);
        this.join(first);
        List<Player> snapshot = this.players.get();
        this.delayer.advance(1);
        long version = this.runtime.version();
        this.join(first);
        this.quit(AttachSupport.player());
        assertEquals(version, this.runtime.version());
        assertSame(snapshot, this.players.get());
        assertEquals(0, this.delayer.pending());
        this.join(replacement);
        this.quit(first);
        assertEquals(version + 1, this.runtime.version());
        assertEquals(1, this.players.size());
        assertSame(replacement, this.players.getFirst());
        this.quit(replacement);
        assertTrue(this.players.isEmpty());
    }

    @Test
    void changesDuringNotificationRunOutsideLockAndScheduleTheFollowingTick() throws Exception {
        Player first = AttachSupport.player();
        Player second = AttachSupport.player();
        AtomicInteger notifications = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor();
             Subscription ignored = this.players.onDirty(() -> {
                 if (notifications.incrementAndGet() == 1) {
                     try {
                         worker.submit(() -> this.join(second)).get(5, TimeUnit.SECONDS);
                     } catch (Exception exception) {
                         throw new AssertionError(exception);
                     }
                 }
             })) {
            this.join(first);
            this.delayer.advance(1);
            assertEquals(1, notifications.get());
            assertEquals(List.of(first, second), this.players.get());
            assertEquals(1, this.delayer.pending());
            this.delayer.advance(1);
            assertEquals(2, notifications.get());
            assertEquals(0, this.delayer.pending());
        }
    }

    @Test
    void concurrentReadsKeepEachSnapshotStableWhilePlayersJoinAndQuit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> writer = workers.submit(() -> {
                start.await();
                for (int index = 0; index < 500; index++) {
                    Player player = AttachSupport.player();
                    this.join(player);
                    this.quit(player);
                }
                return null;
            });
            Future<?> reader = workers.submit(() -> {
                start.await();
                for (int index = 0; index < 1_000; index++) {
                    List<Player> snapshot = this.players.get();
                    int size = snapshot.size();
                    assertEquals(size, snapshot.stream().count());
                    assertEquals(size, new ArrayList<>(snapshot).size());
                    assertTrue(size <= 1);
                }
                return null;
            });
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
            assertTrue(this.players.isEmpty());
            assertEquals(1, this.delayer.pending());
        }
    }

    @Test
    void pageCanReadBeforeNotificationAndClampsAfterQuit() {
        Player first = MockBukkit.getMock().addPlayer();
        Player second = MockBukkit.getMock().addPlayer();
        Player third = MockBukkit.getMock().addPlayer();
        this.delayer.advance(1);
        Page<Player> page = Page.of(this.players, 2);
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = page.content().onDirty(notifications::incrementAndGet)) {
            page.setPage(1);
            assertEquals(List.of(third), page.content().get());
            notifications.set(0);
            this.quit(third);
            assertEquals(0, page.page().get());
            assertEquals(List.of(first, second), page.content().get());
            assertEquals(0, notifications.get());
            this.delayer.advance(1);
            assertTrue(notifications.get() > 0);
        }
    }

    @Test
    void closeCancelsPendingWorkClearsPlayersAndRetiresSubscriptions() {
        MockBukkit.getMock().addPlayer();
        Signal<Integer> count = this.players.map(List::size);
        assertEquals(1, count.get());
        AtomicInteger notifications = new AtomicInteger();
        Subscription subscription = this.players.onDirty(notifications::incrementAndGet);
        this.runtime.close();
        assertEquals(0, this.delayer.pending());
        assertTrue(this.players.isEmpty());
        assertEquals(0, count.get());
        assertTrue(subscription.isClosed());
        assertTrue(this.players.onDirty(() -> {}).isClosed());
        assertEquals(0, Arrays.stream(PlayerJoinEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener() == this.runtime).count());
        assertThrows(UnsupportedOperationException.class, this.players::clear);
        assertThrows(UnsupportedOperationException.class, () -> this.players.get().clear());
        this.join(AttachSupport.player());
        this.delayer.advance(1);
        assertTrue(this.players.isEmpty());
        assertEquals(0, notifications.get());
    }

    @Test
    void taskAlreadyDispatchedBeforeCloseCannotPublishOrRepopulatePlayers() {
        this.delayer.ignoreCancel();
        this.join(AttachSupport.player());
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = this.players.onDirty(notifications::incrementAndGet)) {
            this.runtime.close();
            this.delayer.advance(1);
            assertTrue(this.players.isEmpty());
            assertEquals(0, notifications.get());
            assertEquals(0, this.delayer.pending());
        }
    }

    private Object cachedSnapshot() throws ReflectiveOperationException {
        Field field = PlayerSignalRuntime.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return field.get(this.runtime);
    }

    private void join(Player player) {
        this.event("handleJoin", new PlayerJoinEvent(player, Component.empty()));
    }

    @SuppressWarnings("removal")
    private void quit(Player player) {
        this.event("handleQuit", new PlayerQuitEvent(player, Component.empty()));
    }

    // 并发测试直接调用监听器, MockBukkit 的事件总线只接受主线程同步事件
    private void event(String handler, Object event) {
        try {
            Method method = PlayerSignalRuntime.class.getDeclaredMethod(handler, event.getClass());
            method.setAccessible(true);
            method.invoke(this.runtime, event);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> arguments[0] == proxy;
                    case "toString" -> uuid.toString();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
