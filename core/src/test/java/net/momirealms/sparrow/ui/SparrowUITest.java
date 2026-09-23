package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.window.SparrowUiTestRuntime;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SparrowUITest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void uninitializedAccessorsRequireExplicitSetupWithoutResolvingThePaperClassLoader() throws Exception {
        WithoutPaperLoader loader = new WithoutPaperLoader();
        Class<?> type = loader.loadClass(SparrowUI.class.getName());
        Object ui = type.getMethod("getInstance").invoke(null);

        for (String accessor : List.of("getPlugin", "scheduler", "playerSignals", "windowManager", "networkManager")) {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> type.getMethod(accessor).invoke(ui));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("SparrowUI is not initialized. Call SparrowUI.getInstance().setUp(plugin) first.", failure.getCause().getMessage());
        }
        assertEquals(0, loader.paperClassRequests);
    }

    @Test
    void configuredPluginIsReturnedWithoutResolvingThePaperClassLoader() throws Exception {
        WithoutPaperLoader loader = new WithoutPaperLoader();
        Class<?> type = loader.loadClass(SparrowUI.class.getName());
        Object ui = type.getMethod("getInstance").invoke(null);
        Plugin plugin = MockBukkit.createMockPlugin();
        Field pluginField = type.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        pluginField.set(ui, plugin);

        assertSame(plugin, type.getMethod("getPlugin").invoke(ui));
        assertEquals(0, loader.paperClassRequests);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disableHandlersAreIsolatedBeforeTheSharedSchedulerShutsDown() throws Exception {
        SparrowUiTestRuntime.installPlugin();
        SparrowUI ui = SparrowUI.getInstance();
        Field handlersField = SparrowUI.class.getDeclaredField("disableHandlers");
        handlersField.setAccessible(true);
        HandlerList<Runnable> handlers = (HandlerList<Runnable>) handlersField.get(ui);
        List<Runnable> previous = handlers.snapshot();
        Field schedulerField = SparrowUI.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        SchedulerAdapter previousScheduler = (SchedulerAdapter) schedulerField.get(ui);
        List<String> calls = new ArrayList<>();
        SchedulerAdapter scheduler = (SchedulerAdapter) Proxy.newProxyInstance(
                SparrowUITest.class.getClassLoader(),
                new Class<?>[]{SchedulerAdapter.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "shutdownScheduler", "shutdownExecutor" -> {
                        calls.add(method.getName());
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        schedulerField.set(ui, scheduler);
        RuntimeException expected = new IllegalStateException("injected disable failure");
        AtomicReference<String> reportedMessage = new AtomicReference<>();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        ui.setExceptionHandler((message, throwable) -> {
            reportedMessage.set(message);
            reportedFailure.set(throwable);
        });
        try {
            ui.addDisableHandler(() -> calls.add("first"));
            ui.addDisableHandler(() -> {
                throw expected;
            });
            ui.addDisableHandler(() -> calls.add("last"));
            Method handleDisable = SparrowUI.class.getDeclaredMethod("handlePluginDisable", PluginDisableEvent.class);
            handleDisable.setAccessible(true);
            handleDisable.invoke(ui, new PluginDisableEvent(SparrowUiTestRuntime.plugin()));

            assertEquals(List.of("first", "last", "shutdownScheduler", "shutdownExecutor"), calls);
            assertEquals("Failed to run a disable handler", reportedMessage.get());
            assertSame(expected, reportedFailure.get());
        } finally {
            handlers.set(previous);
            schedulerField.set(ui, previousScheduler);
            ui.setExceptionHandler((ignoredMessage, ignoredThrowable) -> {});
            SparrowUiTestRuntime.restorePlugin();
        }
    }

    private static final class WithoutPaperLoader extends ClassLoader {
        private int paperClassRequests;

        private WithoutPaperLoader() {
            super(SparrowUI.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader")) {
                this.paperClassRequests++;
                throw new ClassNotFoundException(name);
            }
            if (!name.equals(SparrowUI.class.getName())) return super.loadClass(name, resolve);
            Class<?> loaded = this.findLoadedClass(name);
            if (loaded == null) {
                try (InputStream input = this.getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] bytes = input.readAllBytes();
                    loaded = this.defineClass(name, bytes, 0, bytes.length);
                } catch (IOException exception) {
                    throw new ClassNotFoundException(name, exception);
                }
            }
            if (resolve) {
                this.resolveClass(loaded);
            }
            return loaded;
        }
    }
}
