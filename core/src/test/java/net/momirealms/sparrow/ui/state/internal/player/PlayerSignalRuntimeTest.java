package net.momirealms.sparrow.ui.state.internal.player;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSignalRuntimeTest {
    private PlayerSignalRuntime runtime;

    @BeforeEach
    void setUp() {
        PlayerSignalTestRuntime.install();
        this.runtime = PlayerSignalTestRuntime.runtime();
    }

    @AfterEach
    void tearDown() {
        PlayerSignalTestRuntime.restore();
    }

    @Test
    void quitEvictsUuidPartitionsImmediatelyAndHandlesCanReload() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<UUID, Integer> keyed = KeyedSignal.of(uuid -> loads.incrementAndGet());
        KeyedSignal<UUID, Integer> async = KeyedSignal.async(-1, Runnable::run, uuid -> 42);
        MutableKeyedSignal<UUID, Integer> retained = KeyedSignal.of(uuid -> 0);
        Signals.evictOnQuit(keyed);
        Signals.evictOnQuit(keyed);
        Signals.evictOnQuit(async);
        Player player = MockBukkit.getMock().addPlayer();
        UUID uuid = player.getUniqueId();
        assertEquals(0, loads.get());
        assertTrue(keyed.keys().get().isEmpty());
        Signal<Integer> handle = keyed.at(uuid);
        assertEquals(1, handle.get());
        async.get(uuid);
        retained.set(uuid, 7);
        assertEquals(Set.of(player.getUniqueId()), async.keys().get());
        UUID offline = UUID.randomUUID();
        assertEquals(2, keyed.get(offline));

        this.quit(player);
        assertEquals(Set.of(offline), keyed.keys().get());
        assertTrue(async.keys().get().isEmpty());
        assertEquals(7, retained.get(uuid));
        assertEquals(3, handle.get());
        assertSame(handle, keyed.at(player.getUniqueId()));
    }

    @Test
    void registryDoesNotRetainUnusedKeyedSignals() {
        WeakReference<KeyedSignal<UUID, Integer>> probe = this.registerWeakSignal();
        GcSupport.awaitCollected(probe);
        this.quit(MockBukkit.getMock().addPlayer());
    }

    @Test
    void evictionCallbacksCanRegisterSignalsOnAnotherThread() {
        Player player = MockBukkit.getMock().addPlayer();
        MutableKeyedSignal<UUID, Integer> keyed = KeyedSignal.of(uuid -> 1);
        Signals.evictOnQuit(keyed);
        keyed.get(player.getUniqueId());
        AtomicInteger notifications = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor();
             Subscription ignored = keyed.keys().onDirty(() -> {
                 try {
                     worker.submit(() -> Signals.evictOnQuit(KeyedSignal.of(uuid -> 2))).get(5, TimeUnit.SECONDS);
                     notifications.incrementAndGet();
                 } catch (Exception exception) {
                     throw new AssertionError(exception);
                 }
             })) {
            this.quit(player);
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void closeUnregistersListenerAndLeavesExistingPartitionsUsable() {
        Player player = MockBukkit.getMock().addPlayer();
        MutableKeyedSignal<UUID, Integer> keyed = KeyedSignal.of(uuid -> 1);
        Signals.evictOnQuit(keyed);
        keyed.set(player.getUniqueId(), 7);
        assertEquals(1, Arrays.stream(PlayerQuitEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener() == this.runtime).count());
        this.runtime.close();
        this.runtime.close();
        assertEquals(0, Arrays.stream(PlayerQuitEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener() == this.runtime).count());
        this.quit(player);
        assertEquals(7, keyed.get(player.getUniqueId()));
    }

    private WeakReference<KeyedSignal<UUID, Integer>> registerWeakSignal() {
        KeyedSignal<UUID, Integer> signal = KeyedSignal.of(uuid -> 1);
        Signals.evictOnQuit(signal);
        return new WeakReference<>(signal);
    }

    @SuppressWarnings("removal")
    private void quit(Player player) {
        Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.empty()));
    }
}
