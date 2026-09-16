package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.window.handle.AnvilMenuHandle;
import net.momirealms.sparrow.ui.window.handle.BrewingMenuHandle;
import net.momirealms.sparrow.ui.window.handle.CartographyMenuHandle;
import net.momirealms.sparrow.ui.window.handle.CrafterMenuHandle;
import net.momirealms.sparrow.ui.window.handle.EnchantmentMenuHandle;
import net.momirealms.sparrow.ui.window.handle.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuHandle;
import net.momirealms.sparrow.ui.window.handle.MerchantMenuHandle;
import net.momirealms.sparrow.ui.window.handle.RecipeBookMenuHandle;
import net.momirealms.sparrow.ui.window.handle.StonecutterMenuHandle;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaEntityExecutor;
import net.momirealms.sparrow.ui.visual.VisualLayer;
import net.momirealms.sparrow.ui.window.click.EnchantSelectClick;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowManagerThreadingTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        SparrowUiTestRuntime.restoreOwnership();
        MockBukkit.unmock();
    }

    @Test
    void arbitraryThreadLifecycleIsOrderedAndCompletesAfterEntityDispatch() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        Player player = unavailablePlayer(entityExecutor);
        AbstractWindow<?> window = window(manager, player);
        window.setTitle(Component.text("queued"));
        CompletableFuture<Window.OpenResult> opened = window.open();

        assertEquals(Component.empty(), window.title());
        assertFalse(opened.toCompletableFuture().isDone());
        assertEquals(1, entityExecutor.tasks.size());
        entityExecutor.runNext();

        assertEquals(Component.text("queued"), window.title());
        assertEquals(Window.OpenResult.VIEWER_UNAVAILABLE, opened.toCompletableFuture().join());
        assertFalse(window.isOpen());
        assertNull(manager.current(player));
    }

    @Test
    void cancellingTheReturnedFutureDoesNotStopTheQueuedOpenCommand() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        Player player = unavailablePlayer(entityExecutor);
        AbstractWindow<?> window = window(manager, player);
        window.setTitle(Component.text("queued"));
        CompletableFuture<Window.OpenResult> opened = window.open();

        assertTrue(opened.cancel(false));
        entityExecutor.runNext();

        assertEquals(Component.text("queued"), window.title(), "命令照跑, 排在它前面的标题变更被应用了");
        assertTrue(opened.isCancelled(), "取消的是调用方手上这一个, 命令完成不会把它改回来");
    }

    @Test
    void shutdownRejectsNewLifecycleCommandsWithoutScheduling() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        AbstractWindow<?> window = window(manager, unavailablePlayer(entityExecutor));
        manager.shutdown();

        assertEquals(Window.OpenResult.VIEWER_UNAVAILABLE, window.open().toCompletableFuture().join());
        assertEquals(Window.CloseResult.ALREADY_CLOSED, window.close().toCompletableFuture().join());
        assertEquals(
                "retired",
                manager.submit(window, () -> "active", () -> "retired").toCompletableFuture().join()
        );
        window.setCloseable(false);

        assertTrue(window.isCloseable());
        assertTrue(entityExecutor.tasks.isEmpty());
    }

    @Test
    void sessionCreationWaitsForTheEntityDispatchLikeOtherCommands() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor, new UnavailableMenuFactory(menu()));
        Player player = availablePlayer(entityExecutor);
        AbstractWindow<?> window = window(manager, player);
        CompletableFuture<Window.OpenResult> opened = window.open();

        assertFalse(opened.isDone(), "打开命令先排进玩家通道");
        assertEquals(1, entityExecutor.tasks.size());
        assertNull(window.session(), "会话随打开在实体线程诞生, 排队期间还没有");
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.join());
        WindowSession session = window.session();

        assertNotNull(session);
        assertEquals(List.of(window), session.chain());
        assertSame(window, manager.current(player));
    }

    @Test
    void shutdownInvokesCloseHandlerSynchronouslyExactlyOnce() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<WindowCloseReason> reasons = new ArrayList<>();
        List<WindowCloseReason> menuCloseReasons = new ArrayList<>();
        AtomicInteger menuRetires = new AtomicInteger();
        WindowManager manager = manager(
                entityExecutor,
                new UnavailableMenuFactory(menu(menuCloseReasons::add, menuRetires::incrementAndGet))
        );
        AbstractWindow<?> window = window(manager, availablePlayer(entityExecutor), List.of(reasons::add));
        CompletableFuture<Window.OpenResult> opened = window.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        manager.shutdown();
        manager.shutdown();

        assertEquals(List.of(WindowCloseReason.PLUGIN), reasons);
        assertEquals(List.of(WindowCloseReason.PLUGIN), menuCloseReasons);
        assertEquals(0, menuRetires.get());
        assertFalse(window.isOpen());
        assertNull(manager.current(window.viewer()));
    }

    @Test
    void shutdownDuringACommandTearsDownAfterItInsteadOfReentering() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<String> events = new ArrayList<>();
        WindowManager manager = manager(
                entityExecutor,
                new UnavailableMenuFactory(menu(reason -> events.add("menu:" + reason), () -> events.add("menu:retire")))
        );
        AbstractWindow<?> window = window(
                manager,
                availablePlayer(entityExecutor),
                List.of(reason -> events.add("close:" + reason))
        );
        CompletableFuture<Window.OpenResult> opened = window.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        WindowSession session = window.session();
        CompletableFuture<String> command = manager.submit(
                window,
                () -> {
                    manager.shutdown();
                    events.add("command");
                    return "done";
                },
                () -> "retired"
        ).toCompletableFuture();
        entityExecutor.runNext();

        assertEquals("done", command.join(), "关停不打断正在执行的命令");
        assertEquals(List.of("command", "menu:PLUGIN", "close:PLUGIN"), events, "收尾排在命令之后, 并且只跑一次");
        assertFalse(session.active());
        assertFalse(window.isOpen());
        assertNull(manager.current(window.viewer()));
    }

    @Test
    void rejectedEntityScheduleRetiresLocallyAndWarnsAboutSkippedHandlers() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<WindowCloseReason> reasons = new ArrayList<>();
        AtomicInteger menuRetires = new AtomicInteger();
        AtomicReference<String> retirementReport = new AtomicReference<>();
        SparrowUI.getInstance().setExceptionHandler((message, ignoredThrowable) -> {
            if (message.startsWith("Window entity scheduler retired before close handlers ran")) {
                retirementReport.set(message);
            }
        });
        try {
            WindowManager manager = manager(
                    entityExecutor,
                    new UnavailableMenuFactory(menu(ignoredReason -> {}, menuRetires::incrementAndGet))
            );
            Player player = availablePlayer(entityExecutor);
            AbstractWindow<?> window = window(manager, player, List.of(reasons::add));
            CompletableFuture<Window.OpenResult> opened = window.open();
            entityExecutor.runNext();

            assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
            entityExecutor.retired = true;
            window.setCloseable(false);

            assertTrue(reasons.isEmpty());
            assertEquals(1, menuRetires.get());
            assertTrue(window.isCloseable());
            assertFalse(window.isOpen());
            assertNull(manager.current(player));
            assertTrue(retirementReport.get() != null);
            assertTrue(retirementReport.get().contains("player=" + player.getUniqueId()));
            assertTrue(retirementReport.get().contains("window=" + window.getClass().getName()));
        } finally {
            SparrowUI.getInstance().setExceptionHandler((ignoredMessage, ignoredThrowable) -> {});
        }
    }

    @Test
    void disconnectCloseRunsHandlerBeforeQuitAndSchedulerRetirement() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        Player player = availablePlayer(entityExecutor);
        InventoryView view = inventoryView(player);
        List<WindowCloseReason> reasons = new ArrayList<>();
        List<WindowCloseReason> menuCloseReasons = new ArrayList<>();
        AtomicInteger menuRetires = new AtomicInteger();
        WindowManager manager = manager(
                entityExecutor,
                new UnavailableMenuFactory(menu(view, menuCloseReasons::add, menuRetires::incrementAndGet))
        );
        AbstractWindow<?> window = window(manager, player, List.of(reasons::add));
        CompletableFuture<Window.OpenResult> opened = window.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        invokeEventHandler(
                manager,
                "handleInventoryClose",
                InventoryCloseEvent.class,
                new InventoryCloseEvent(view, InventoryCloseEvent.Reason.DISCONNECT)
        );

        assertEquals(List.of(WindowCloseReason.DISCONNECT), reasons);
        assertTrue(menuCloseReasons.isEmpty());
        assertEquals(1, menuRetires.get());
        assertFalse(window.isOpen());
        assertNull(manager.current(player));
        invokeEventHandler(
                manager,
                "handleQuit",
                PlayerQuitEvent.class,
                new PlayerQuitEvent(
                        player,
                        Component.empty(),
                        PlayerQuitEvent.QuitReason.DISCONNECTED
                )
        );
        entityExecutor.runFixedRateRetired();

        assertEquals(List.of(WindowCloseReason.DISCONNECT), reasons);
        assertEquals(1, menuRetires.get());
    }

    @Test
    void queuedNavigationFromTheWindowOpenedByThePreviousCommandStillRuns() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor, new UnavailableMenuFactory(menu()));
        Player player = availablePlayer(entityExecutor);
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        AbstractWindow<?> third = window(manager, player);
        CompletableFuture<Window.OpenResult> opened = root.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        CompletableFuture<Window> toSecond = root.navigate(second);
        CompletableFuture<Window> toThird = second.navigate(third);
        entityExecutor.runNext();

        assertSame(second, toSecond.join());
        assertSame(third, toThird.join());
        assertEquals(List.of(root, second, third), root.session().chain());
    }

    @Test
    void disconnectFiresTheSessionEndHandlerOnceBeforeSchedulerRetirement() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        Player player = availablePlayer(entityExecutor);
        InventoryView view = inventoryView(player);
        WindowManager manager = manager(
                entityExecutor,
                new UnavailableMenuFactory(menu(view, ignoredReason -> {}, () -> {}))
        );
        AbstractWindow<?> window = window(manager, player, List.of(), List.of(endReasons::add));
        CompletableFuture<Window.OpenResult> opened = window.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        WindowSession session = window.session();
        invokeEventHandler(
                manager,
                "handleInventoryClose",
                InventoryCloseEvent.class,
                new InventoryCloseEvent(view, InventoryCloseEvent.Reason.DISCONNECT)
        );
        invokeEventHandler(
                manager,
                "handleQuit",
                PlayerQuitEvent.class,
                new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED)
        );
        entityExecutor.runFixedRateRetired();

        assertEquals(List.of(WindowCloseReason.DISCONNECT), endReasons, "整段断线只触发一次会话结束处理器");
        assertFalse(session.active());
        assertNull(window.session());
        assertNull(manager.current(player));
    }

    @Test
    void retiredOldLaneDoesNotRemoveQuickReconnectSession() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        UUID playerId = UUID.randomUUID();
        Player previousPlayer = availablePlayer(entityExecutor, playerId);
        Player reconnectedPlayer = availablePlayer(entityExecutor, playerId);
        WindowManager manager = manager(entityExecutor, new UnavailableMenuFactory(menu()));
        AbstractWindow<?> previous = window(manager, previousPlayer);
        AbstractWindow<?> replacement = window(manager, reconnectedPlayer);
        CompletableFuture<Window.OpenResult> opened = previous.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        PlayerCommandLane previousLane = currentLane(manager, previousPlayer);
        CompletableFuture<Window.OpenResult> reopened = replacement.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, reopened.toCompletableFuture().join());
        assertFalse(previous.isOpen());
        assertTrue(replacement.isOpen());
        assertTrue(manager.current(reconnectedPlayer) == replacement);
        assertTrue(currentLane(manager, reconnectedPlayer) != previousLane);
        previousLane.retire();

        assertTrue(manager.current(reconnectedPlayer) == replacement);
        manager.shutdown();
    }

    @Test
    void retiringAStaleLaneWaitsForItsRunningCommand() throws InterruptedException {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        UUID playerId = UUID.randomUUID();
        Player previousPlayer = availablePlayer(entityExecutor, playerId);
        Player reconnectedPlayer = availablePlayer(entityExecutor, playerId);
        WindowManager manager = manager(entityExecutor, new UnavailableMenuFactory(menu()));
        AbstractWindow<?> previous = window(manager, previousPlayer);
        CompletableFuture<Window.OpenResult> opened = previous.open();
        entityExecutor.runNext();

        assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
        CountDownLatch commandStarted = new CountDownLatch(1);
        CountDownLatch reconnectDone = new CountDownLatch(1);
        AtomicBoolean openDuringCommand = new AtomicBoolean();
        CompletableFuture<String> command = manager.submit(
                previous,
                () -> {
                    commandStarted.countDown();
                    reconnectDone.await();
                    openDuringCommand.set(previous.isOpen());
                    return "done";
                },
                () -> "retired"
        ).toCompletableFuture();
        Thread reconnect = new Thread(() -> {
            try {
                commandStarted.await();
                manager.submit(reconnectedPlayer, () -> "reconnected", () -> "retired").join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                reconnectDone.countDown();
            }
        });
        reconnect.start();
        entityExecutor.runNext();
        reconnect.join();

        assertEquals("done", command.join(), "旧通道里正在执行的命令照常跑完");
        assertTrue(openDuringCommand.get(), "注销不能在命令执行途中把窗口拆掉");
        assertFalse(previous.isOpen(), "命令跑完后注销收尾照常执行");
        assertNull(manager.current(previousPlayer));
    }

    @Test
    void menuCloseFailureStillInvokesCloseHandlerAndReportsFailure() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<WindowCloseReason> reasons = new ArrayList<>();
        AtomicReference<String> reportedMessage = new AtomicReference<>();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        IllegalStateException expected = new IllegalStateException("injected menu close failure");
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
            if (message.equals("Failed to close Window during shutdown")) {
                reportedMessage.set(message);
                reportedFailure.set(throwable);
            }
        });
        try {
            WindowManager manager = manager(
                    entityExecutor,
                    new UnavailableMenuFactory(menu(ignoredReason -> {
                        throw expected;
                    }, () -> {}))
            );
            AbstractWindow<?> window = window(
                    manager,
                    availablePlayer(entityExecutor),
                    List.of(reasons::add)
            );
            CompletableFuture<Window.OpenResult> opened = window.open();
            entityExecutor.runNext();

            assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
            manager.shutdown();

            assertEquals(List.of(WindowCloseReason.PLUGIN), reasons);
            assertEquals("Failed to close Window during shutdown", reportedMessage.get());
            assertEquals(expected, reportedFailure.get());
            assertFalse(window.isOpen());
            assertNull(manager.current(window.viewer()));
        } finally {
            SparrowUI.getInstance().setExceptionHandler((ignoredMessage, ignoredThrowable) -> {});
        }
    }

    @Test
    void closeHandlerFailureDoesNotPreventFollowingHandler() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<String> calls = new ArrayList<>();
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        IllegalStateException expected = new IllegalStateException("injected close handler failure");
        SparrowUI.getInstance().setExceptionHandler((ignoredMessage, throwable) -> {
            reports.incrementAndGet();
            reportedFailure.set(throwable);
        });
        try {
            WindowManager manager = manager(entityExecutor, new UnavailableMenuFactory(menu()));
            AbstractWindow<?> window = window(
                    manager,
                    availablePlayer(entityExecutor),
                    List.of(
                            reason -> calls.add("first:" + reason),
                            ignoredReason -> {
                                throw expected;
                            },
                            reason -> calls.add("last:" + reason)
                    )
            );
            CompletableFuture<Window.OpenResult> opened = window.open();
            entityExecutor.runNext();

            assertEquals(Window.OpenResult.OPENED, opened.toCompletableFuture().join());
            manager.shutdown();

            assertEquals(List.of("first:PLUGIN", "last:PLUGIN"), calls);
            assertEquals(1, reports.get());
            assertEquals(expected, reportedFailure.get());
        } finally {
            SparrowUI.getInstance().setExceptionHandler((ignoredMessage, ignoredThrowable) -> {});
        }
    }

    @Test
    void voidCommandReportsExecutionFailureOnce() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        AbstractWindow<?> window = window(manager(entityExecutor), unavailablePlayer(entityExecutor));
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SparrowUI.getInstance().setExceptionHandler((reportedMessage, throwable) -> {
            reports.incrementAndGet();
            message.set(reportedMessage);
            failure.set(throwable);
        });
        try {
            IllegalStateException expected = new IllegalStateException("injected title failure");
            window.setTitleSupplier(() -> {
                throw expected;
            });
            entityExecutor.runNext();

            assertEquals(1, reports.get());
            assertEquals("Failed to update Window title supplier", message.get());
            assertEquals(expected, failure.get().getCause());
        } finally {
            SparrowUI.getInstance().setExceptionHandler((ignoredMessage, ignoredThrowable) -> {});
        }
    }

    @Test
    void typedCommandCompletesThroughItsRetiredAction() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        AbstractWindow<?> window = window(manager, unavailablePlayer(entityExecutor));
        CompletableFuture<String> command = manager.submit(
                window,
                () -> "active",
                () -> "retired"
        );
        manager.shutdown();

        assertEquals("retired", command.toCompletableFuture().join());
    }

    @Test
    void furnaceProgressCommandsExposeOnlyEntityLaneAppliedValues() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        Player player = unavailablePlayer(entityExecutor);
        AbstractFurnaceWindow window = new FurnaceWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(1, 1)),
                        WindowLayout.Region.upper(Pane.empty(1, 1)),
                        WindowLayout.Region.upper(Pane.empty(1, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                List.of(),
                0.0,
                0.0
        );

        assertThrows(IllegalArgumentException.class, () -> window.setCookProgress(Double.NaN));
        assertTrue(entityExecutor.tasks.isEmpty());
        window.setCookProgress(0.25);
        window.setFuelProgress(0.5);

        assertEquals(0.0, window.getCookProgress());
        assertEquals(0.0, window.getFuelProgress());
        assertEquals(1, entityExecutor.tasks.size());
        entityExecutor.runNext();

        assertEquals(0.25, window.getCookProgress());
        assertEquals(0.5, window.getFuelProgress());
    }

    @Test
    void merchantCommandsValidateSynchronouslyAndExposeOnlyEntityLaneAppliedSnapshots() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        Player player = unavailablePlayer(entityExecutor);
        MerchantWindow.Trade trade = MerchantWindow.Trade.builder().build();
        MerchantWindowImpl window = new MerchantWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(3, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                0,
                -1.0,
                false,
                List.of(),
                List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> window.setLevel(6));
        assertThrows(IllegalArgumentException.class, () -> window.setProgress(Double.NaN));
        assertThrows(NullPointerException.class, () -> window.setTrades(Collections.singletonList(null)));
        assertTrue(entityExecutor.tasks.isEmpty());
        window.setLevel(3);
        window.setProgress(0.75);
        window.setRestockMessageEnabled(true);
        window.setTrades(List.of(trade));

        assertEquals(0, window.getLevel());
        assertEquals(-1.0, window.getProgress());
        assertFalse(window.isRestockMessageEnabled());
        assertTrue(window.getTrades().isEmpty());
        assertEquals(1, entityExecutor.tasks.size());
        entityExecutor.runNext();

        assertEquals(3, window.getLevel());
        assertEquals(0.75, window.getProgress());
        assertTrue(window.isRestockMessageEnabled());
        assertEquals(List.of(trade), window.getTrades());
    }

    @Test
    void enchantmentCommandsValidateSynchronouslyAndExposeOnlyEntityLaneAppliedSnapshots() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        WindowManager manager = manager(entityExecutor);
        Player player = unavailablePlayer(entityExecutor);
        EnchantmentWindow.EnchantOption option = new EnchantmentWindow.EnchantOption(3, null, 7);
        Consumer<EnchantSelectClick> handler = ignoredClick -> {};
        EnchantmentWindowImpl window = new EnchantmentWindowImpl(
                manager,
                player,
                WindowLayout.of(
                        WindowLayout.Region.upper(Pane.empty(2, 1)),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                ),
                settings(),
                new EnchantmentWindow.EnchantOption[3],
                0,
                List.of()
        );

        assertThrows(IndexOutOfBoundsException.class, () -> window.setOption(-1, option));
        assertThrows(IndexOutOfBoundsException.class, () -> window.getOption(3));
        assertTrue(entityExecutor.tasks.isEmpty());
        window.setOption(1, option);
        window.setEnchantmentSeed(73);
        window.addEnchantSelectHandler(handler);

        assertNull(window.getOption(1));
        assertEquals(0, window.getEnchantmentSeed());
        assertTrue(window.getEnchantSelectHandlers().isEmpty());
        assertEquals(1, entityExecutor.tasks.size());
        entityExecutor.runNext();

        assertEquals(option, window.getOption(1));
        assertEquals(73, window.getEnchantmentSeed());
        assertEquals(List.of(handler), window.getEnchantSelectHandlers());
    }

    private static InventoryView inventoryView(Player player) {
        return (InventoryView) Proxy.newProxyInstance(
                WindowManagerThreadingTest.class.getClassLoader(),
                new Class<?>[]{InventoryView.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayer" -> player;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "ThreadingTestInventoryView";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static <E> void invokeEventHandler(
            WindowManager manager,
            String methodName,
            Class<E> eventType,
        E event
    ) {
        try {
            Method method = WindowManager.class.getDeclaredMethod(methodName, eventType);
            method.setAccessible(true);
            method.invoke(manager, event);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("WindowManager event handler failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to invoke WindowManager event handler", exception);
        }
    }

    private static WindowManager manager(ControlledEntityExecutor entityExecutor) {
        return manager(entityExecutor, new UnavailableMenuFactory());
    }

    private static WindowManager manager(ControlledEntityExecutor entityExecutor, MenuFactory menuFactory) {
        SparrowUiTestRuntime.installOwnership(() -> entityExecutor.owned);
        return new WindowManager(SparrowUiTestRuntime.plugin(), menuFactory, new FoliaEntityExecutor(SparrowUiTestRuntime.plugin()));
    }

    @SuppressWarnings("unchecked")
    private static PlayerCommandLane currentLane(WindowManager manager, Player player) {
        try {
            Field lanesField = WindowManager.class.getDeclaredField("lanes");
            lanesField.setAccessible(true);
            Map<UUID, PlayerCommandLane> lanes = (Map<UUID, PlayerCommandLane>) lanesField.get(manager);
            return lanes.get(player.getUniqueId());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read the current PlayerCommandLane", exception);
        }
    }

    private static final class UnavailableMenuFactory implements MenuFactory {
        private final MenuHandle normal;
        private UnavailableMenuFactory() {
            this(null);
        }
        private UnavailableMenuFactory(MenuHandle normal) {
            this.normal = normal;
        }
        @Override
        public @NonNull MenuHandle normal(@NonNull Player viewer, int rows, long generation) {
            if (this.normal != null) {
                return this.normal;
            }
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        public @NonNull MenuHandle hopper(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        public @NonNull AnvilMenuHandle anvil(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public MenuHandle dispenser(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public MenuHandle dropper(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public MenuHandle grindstone(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public MenuHandle smithing(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public BrewingMenuHandle brewing(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public CartographyMenuHandle cartography(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public CrafterMenuHandle crafter(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public RecipeBookMenuHandle crafting(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public FurnaceMenuHandle furnace(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public FurnaceMenuHandle smoker(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public FurnaceMenuHandle blastFurnace(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public EnchantmentMenuHandle enchantment(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public StonecutterMenuHandle stonecutter(@NonNull Player viewer, long generation) {
            throw new AssertionError("unavailable player must not create a menu");
        }
        @Override
        @NonNull
        public MerchantMenuHandle merchant(
                @NonNull Player viewer,
                long generation,
                @NonNull MerchantWindow window,
                @NonNull BiConsumer<? super String, ? super Throwable> reporter
        ) {
            throw new AssertionError("unavailable player must not create a menu");
        }
    }

    private static AbstractWindow<?> window(WindowManager manager, Player player) {
        return window(manager, player, List.of());
    }

    private static AbstractWindow<?> window(
            WindowManager manager,
            Player player,
            List<Consumer<WindowCloseReason>> closeHandlers
    ) {
        return window(manager, player, closeHandlers, List.of());
    }

    private static AbstractWindow<?> window(
            WindowManager manager,
            Player player,
            List<Consumer<WindowCloseReason>> closeHandlers,
            List<Consumer<WindowCloseReason>> sessionEndHandlers
    ) {
        return new NormalWindowImpl(
                manager,
                player,
                WindowLayout.split(Pane.empty(9, 1), Pane.empty(9, 4)),
                settings(closeHandlers, sessionEndHandlers)
        );
    }

    private static AbstractWindow.Settings settings() {
        return settings(List.of());
    }

    private static AbstractWindow.Settings settings(List<Consumer<WindowCloseReason>> closeHandlers) {
        return settings(closeHandlers, List.of());
    }

    private static AbstractWindow.Settings settings(
            List<Consumer<WindowCloseReason>> closeHandlers,
            List<Consumer<WindowCloseReason>> sessionEndHandlers
    ) {
        return new AbstractWindow.Settings(
                Component::empty,
                true,
                List.of(),
                closeHandlers,
                List.of(),
                false,
                null,
                WindowSession.Kind.STACK,
                sessionEndHandlers,
                0,
                List.of(),
                VisualLayer.NONE,
                VisualLayer.NONE
        );
    }

    private static MenuHandle menu() {
        return menu(ignoredReason -> {}, () -> {});
    }

    private static MenuHandle menu(Consumer<WindowCloseReason> closeHandler, Runnable retireHandler) {
        return menu(null, closeHandler, retireHandler);
    }

    private static MenuHandle menu(
            InventoryView view,
            Consumer<WindowCloseReason> closeHandler,
            Runnable retireHandler
    ) {
        return (MenuHandle) Proxy.newProxyInstance(
                WindowManagerThreadingTest.class.getClassLoader(),
                new Class<?>[]{MenuHandle.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "cursor" -> arguments == null || arguments.length == 0 ? ItemStack.empty() : null;
                    case "close" -> {
                        WindowCloseReason reason = arguments == null || arguments.length == 0
                                ? WindowCloseReason.PLUGIN
                                : (WindowCloseReason) arguments[0];
                        closeHandler.accept(reason);
                        yield null;
                    }
                    case "retire" -> {
                        retireHandler.run();
                        yield null;
                    }
                    case "drainInputs" -> List.of();
                    case "accepts", "hasInputOverflowed" -> false;
                    case "containerId", "stateId" -> 0;
                    case "view" -> {
                        if (view == null) {
                            throw new AssertionError("threading test does not expose an InventoryView");
                        }
                        yield view;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "ThreadingTestMenu";
                    default -> null;
                }
        );
    }

    private static Player availablePlayer(ControlledEntityExecutor entityExecutor) {
        return availablePlayer(entityExecutor, UUID.randomUUID());
    }

    private static Player availablePlayer(ControlledEntityExecutor entityExecutor, UUID playerId) {
        return (Player) Proxy.newProxyInstance(
                WindowManagerThreadingTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getScheduler" -> entityExecutor;
                    case "isValid", "isConnected" -> true;
                    case "isSleeping" -> false;
                    case "hashCode" -> playerId.hashCode();
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "AvailablePlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static Player unavailablePlayer(ControlledEntityExecutor entityExecutor) {
        UUID playerId = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                WindowManagerThreadingTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getScheduler" -> entityExecutor;
                    case "isValid", "isConnected", "isSleeping" -> false;
                    case "hashCode" -> playerId.hashCode();
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "UnavailablePlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static final class ControlledEntityExecutor implements EntityScheduler {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final ScheduledTask acceptedTask = scheduledTask();
        private boolean owned;
        private boolean retired;
        private Runnable fixedRateRetired;
        private RuntimeException runFailure;
        @Override
        public boolean execute(@NonNull Plugin plugin, @NonNull Runnable task, @NonNull Runnable retired, long delay) {
            if (this.retired) {
                return false;
            }
            this.tasks.addLast(task);
            return true;
        }
        @Override
        public ScheduledTask run(@NonNull Plugin plugin, @NonNull Consumer<ScheduledTask> task, @NonNull Runnable retired) {
            if (this.runFailure != null) {
                RuntimeException failure = this.runFailure;
                this.runFailure = null;
                throw failure;
            }
            if (this.retired) {
                return null;
            }
            this.tasks.addLast(() -> task.accept(this.acceptedTask));
            return this.acceptedTask;
        }
        @Override
        public ScheduledTask runDelayed(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                @NonNull Runnable retired,
                long delay
        ) {
            throw new AssertionError("unavailable player must not start a delayed task");
        }
        @Override
        public ScheduledTask runAtFixedRate(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                @NonNull Runnable retired,
                long initialDelay,
                long period
        ) {
            if (this.retired) {
                return null;
            }
            this.fixedRateRetired = retired;
            return this.acceptedTask;
        }
        private void runNext() {
            this.owned = true;
            try {
                this.tasks.removeFirst().run();
            } finally {
                this.owned = false;
            }
        }
        private void runFixedRateRetired() {
            Runnable retired = this.fixedRateRetired;
            this.fixedRateRetired = null;
            retired.run();
        }
    }

    private static ScheduledTask scheduledTask() {
        return (ScheduledTask) Proxy.newProxyInstance(
                WindowManagerThreadingTest.class.getClassLoader(),
                new Class<?>[]{ScheduledTask.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "AcceptedScheduledTask";
                    default -> null;
                }
        );
    }
}
