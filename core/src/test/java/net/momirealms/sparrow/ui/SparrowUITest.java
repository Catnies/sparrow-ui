package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.window.SparrowUiTestRuntime;
import org.bukkit.World;
import org.bukkit.event.server.PluginDisableEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        SchedulerAdapter<World> previousScheduler = (SchedulerAdapter<World>) schedulerField.get(ui);
        List<String> calls = new ArrayList<>();
        SchedulerAdapter<World> scheduler = (SchedulerAdapter<World>) Proxy.newProxyInstance(
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
}
