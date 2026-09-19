package net.momirealms.sparrow.ui.plugin.scheduler;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FoliaExecutorTest {

    @Test
    void overloadsRouteToGlobalOrTheRequestedChunk() throws Exception {
        Server original = MockBukkit.mock();
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        try {
            Plugin plugin = MockBukkit.createMockPlugin();
            World world = Bukkit.getWorlds().isEmpty() ? MockBukkit.getMock().addSimpleWorld("region") : Bukkit.getWorlds().getFirst();
            List<String> calls = new ArrayList<>();
            ScheduledTask task = (ScheduledTask) Proxy.newProxyInstance(
                    this.getClass().getClassLoader(), new Class<?>[]{ScheduledTask.class},
                    (proxy, method, args) -> null
            );
            GlobalRegionScheduler global = (GlobalRegionScheduler) Proxy.newProxyInstance(
                    this.getClass().getClassLoader(), new Class<?>[]{GlobalRegionScheduler.class},
                    (proxy, method, args) -> {
                        assertSame(plugin, args[0]);
                        calls.add("global:" + method.getName());
                        return method.getReturnType() == void.class ? null : task;
                    }
            );
            RegionScheduler region = (RegionScheduler) Proxy.newProxyInstance(
                    this.getClass().getClassLoader(), new Class<?>[]{RegionScheduler.class},
                    (proxy, method, args) -> {
                        assertSame(plugin, args[0]);
                        assertSame(world, args[1]);
                        assertEquals(-3, args[2]);
                        assertEquals(7, args[3]);
                        calls.add("region:" + method.getName());
                        return method.getReturnType() == void.class ? null : task;
                    }
            );
            Server server = (Server) Proxy.newProxyInstance(
                    this.getClass().getClassLoader(), new Class<?>[]{Server.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getGlobalRegionScheduler" -> global;
                        case "getRegionScheduler" -> region;
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
            );
            serverField.set(null, server);
            FoliaExecutor executor = new FoliaExecutor(plugin);
            Runnable runnable = () -> {};
            executor.run(runnable);
            executor.run(runnable, world, -3, 7);
            executor.runDelayed(runnable);
            executor.runDelayed(runnable, world, -3, 7);
            executor.runLater(runnable, 0);
            executor.runLater(runnable, 0, world, -3, 7);
            executor.runLater(runnable, 2);
            executor.runLater(runnable, 2, world, -3, 7);
            executor.runRepeating(runnable, 1, 2);
            executor.runRepeating(runnable, 1, 2, world, -3, 7);
            assertEquals(List.of(
                    "global:execute", "region:execute", "global:execute", "region:execute",
                    "global:run", "region:run", "global:runDelayed", "region:runDelayed",
                    "global:runAtFixedRate", "region:runAtFixedRate"
            ), calls);
        } finally {
            serverField.set(null, original);
            MockBukkit.unmock();
        }
    }
}
