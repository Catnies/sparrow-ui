package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.ui.PlayerConnectionTestSupport;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class SparrowUiTestRuntime {

    private static final Plugin PLUGIN = (Plugin) Proxy.newProxyInstance(
            SparrowUiTestRuntime.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> "SparrowUiTest";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                case "toString" -> "SparrowUiTestPlugin";
                default -> throw new UnsupportedOperationException(method.getName());
            }

    );
    private static Server previousServer;
    private static boolean ownershipInstalled;
    private static Plugin previousPlugin;
    private static boolean pluginInstalled;
    private static final AtomicInteger ASYNC_RUNS = new AtomicInteger();
    private static SchedulerAdapter previousScheduler;
    private static final Executor ASYNC = command -> {
        ASYNC_RUNS.incrementAndGet();
        command.run();
    };
    private static final SchedulerAdapter SCHEDULER = (SchedulerAdapter) Proxy.newProxyInstance(
            SparrowUiTestRuntime.class.getClassLoader(),
            new Class<?>[]{SchedulerAdapter.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("async")) return ASYNC;
                if (method.getName().equals("executeAsync")) {
                    ASYNC.execute((Runnable) arguments[0]);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            }
    );
    private SparrowUiTestRuntime() {
    }

    public static Plugin plugin() {
        return PLUGIN;
    }

    public static int asyncRuns() {
        return ASYNC_RUNS.get();
    }

    public static synchronized void installPlugin() {
        if (pluginInstalled) {
            restorePlugin();
        }
        try {
            Field pluginField = SparrowUI.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            previousPlugin = (Plugin) pluginField.get(SparrowUI.getInstance());
            pluginField.set(SparrowUI.getInstance(), PLUGIN);
            Field schedulerField = SparrowUI.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            previousScheduler = (SchedulerAdapter) schedulerField.get(SparrowUI.getInstance());
            schedulerField.set(SparrowUI.getInstance(), SCHEDULER);
            pluginInstalled = true;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to install the SparrowUI test plugin", exception);
        }
    }

    public static synchronized void restorePlugin() {
        if (!pluginInstalled) {
            return;
        }
        try {
            Field pluginField = SparrowUI.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(SparrowUI.getInstance(), previousPlugin);
            Field schedulerField = SparrowUI.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            schedulerField.set(SparrowUI.getInstance(), previousScheduler);
            previousScheduler = null;
            previousPlugin = null;
            pluginInstalled = false;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to restore the SparrowUI test plugin", exception);
        }
    }

    public static synchronized void installOwnership(@NotNull BooleanSupplier ownership) {
        installOwnership(ignoredTarget -> ownership.getAsBoolean(), target -> target instanceof Entity);
    }

    public static synchronized void installOwnership(@NotNull Predicate<Object> ownership) {
        installOwnership(ownership, ignoredTarget -> true);
    }

    private static synchronized void installOwnership(
            Predicate<Object> ownership,
            Predicate<Object> handledTarget
    ) {
        PlayerConnectionTestSupport.install();
        if (ownershipInstalled) {
            restoreOwnership();
        }
        ASYNC_RUNS.set(0);
        Server delegate = server();
        previousServer = delegate;
        ownershipInstalled = true;
        setServer((Server) Proxy.newProxyInstance(
                SparrowUiTestRuntime.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("isOwnedByCurrentRegion")
                            && arguments != null
                            && arguments.length == 1
                            && handledTarget.test(arguments[0])) {
                        return ownership.test(arguments[0]);
                    }
                    if (delegate == null) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        ));
    }

    public static synchronized void restoreOwnership() {
        if (!ownershipInstalled) {
            return;
        }
        setServer(previousServer);
        previousServer = null;
        ownershipInstalled = false;
    }

    static synchronized void install(@NotNull WindowManager windowManager) {
        try {
            Field managerField = SparrowUI.class.getDeclaredField("windowManager");
            managerField.setAccessible(true);
            managerField.set(SparrowUI.getInstance(), windowManager);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to install the SparrowUI test WindowManager", exception);
        }
    }

    private static Server server() {
        try {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            return (Server) serverField.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read the Bukkit test server", exception);
        }
    }

    private static void setServer(Server server) {
        try {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(null, server);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to install the Bukkit test server", exception);
        }
    }
}
