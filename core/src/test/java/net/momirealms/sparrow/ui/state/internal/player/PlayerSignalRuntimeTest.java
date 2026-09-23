package net.momirealms.sparrow.ui.state.internal.player;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MutablePlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
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
        MutablePlayerKeyedSignal<Integer> keyed = PlayerKeyedSignal.of(uuid -> loads.incrementAndGet());
        PlayerKeyedSignal<Integer> async = PlayerKeyedSignal.async(-1, Runnable::run, uuid -> 42);
        Player player = MockBukkit.getMock().addPlayer();
        assertEquals(0, loads.get());
        assertTrue(keyed.keys().get().isEmpty());
        Signal<Integer> handle = keyed.at(player);
        assertEquals(1, handle.get());
        async.get(player);
        assertEquals(Set.of(player.getUniqueId()), async.keys().get());
        UUID offline = UUID.randomUUID();
        assertEquals(2, keyed.get(offline));

        this.quit(player);
        assertEquals(Set.of(offline), keyed.keys().get());
        assertTrue(async.keys().get().isEmpty());
        assertEquals(3, handle.get());
        assertSame(handle, keyed.at(player.getUniqueId()));
    }

    @Test
    void registryDoesNotRetainUnusedKeyedSignals() {
        WeakReference<PlayerKeyedSignal<Integer>> probe = new WeakReference<>(PlayerKeyedSignal.of(uuid -> 1));
        GcSupport.awaitCollected(probe);
        this.quit(MockBukkit.getMock().addPlayer());
    }

    @Test
    void evictionCallbacksCanRegisterSignalsOnAnotherThread() {
        Player player = MockBukkit.getMock().addPlayer();
        MutablePlayerKeyedSignal<Integer> keyed = PlayerKeyedSignal.of(uuid -> 1);
        keyed.get(player);
        AtomicInteger notifications = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor();
             Subscription ignored = keyed.keys().onDirty(() -> {
                 try {
                     worker.submit(() -> PlayerKeyedSignal.of(uuid -> 2)).get(5, TimeUnit.SECONDS);
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
        MutablePlayerKeyedSignal<Integer> keyed = PlayerKeyedSignal.of(uuid -> 1);
        keyed.set(player, 7);
        assertEquals(1, Arrays.stream(PlayerQuitEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener() == this.runtime).count());
        this.runtime.close();
        this.runtime.close();
        assertEquals(0, Arrays.stream(PlayerQuitEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener() == this.runtime).count());
        this.quit(player);
        assertEquals(7, keyed.get(player));
    }

    @SuppressWarnings("removal")
    private void quit(Player player) {
        Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.empty()));
    }
}
