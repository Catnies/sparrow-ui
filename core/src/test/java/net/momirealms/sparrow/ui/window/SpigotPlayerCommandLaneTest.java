package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpigotPlayerCommandLaneTest {

    private Plugin plugin;
    private Player player;
    private boolean playerValid;
    private boolean playerOnline;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin();
        this.playerValid = false;
        this.playerOnline = false;
        this.player = (Player) Proxy.newProxyInstance(
                SpigotPlayerCommandLaneTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isValid" -> this.playerValid;
                    case "isOnline" -> this.playerOnline;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "OfflineSpigotPlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void mainThreadSubmissionAfterQuitUsesTheRetiredAction() {
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<PlayerCommandLane> retiredLane = new AtomicReference<>();
        PlayerCommandLane lane = new PlayerCommandLane(this.player, new BukkitExecutor(this.plugin), retiredLane::set);
        String result = lane.submit(
                () -> {
                    actions.incrementAndGet();
                    return "active";
                },
                () -> "retired"
        ).toCompletableFuture().join();

        assertEquals("retired", result);
        assertEquals(0, actions.get());
        assertSame(lane, retiredLane.get());
    }

    @Test
    void deadButOnlinePlayerStillOwnsTheMainThreadLane() {
        this.playerValid = false;
        this.playerOnline = true;
        AtomicReference<PlayerCommandLane> retiredLane = new AtomicReference<>();
        PlayerCommandLane lane = new PlayerCommandLane(this.player, new BukkitExecutor(this.plugin), retiredLane::set);
        String result = lane.submit(() -> "active", () -> "retired").toCompletableFuture().join();

        assertEquals("active", result);
        assertNull(retiredLane.get());
    }
}
