package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalHostBindingTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void paneBindDeliversOwnerAndLetsCallbackPull() {
        MutableSignal<String> season = Signal.of("spring");
        NormalPane pane = Pane.empty(3, 1);
        List<Pane> owners = new ArrayList<>();
        List<String> values = new ArrayList<>();
        pane.bind(season, host -> {
            owners.add(host);
            values.add(season.get());
        });
        season.set("summer");

        assertEquals(List.of(pane), owners, "回调首参应是绑定的 Pane 自身");
        assertEquals(List.of("summer"), values);
    }

    @Test
    void inventoryBindCanWriteSlotsThroughTheTransaction() {
        MutableSignal<Integer> amount = Signal.of(0);
        VirtualInventory inventory = new VirtualInventory(3);
        List<Integer> writes = new ArrayList<>();
        inventory.bind(amount, host -> {
            host.setItem(UpdateReason.Program.INSTANCE, 0, null);
            writes.add(amount.get());
        });
        amount.set(1);

        assertEquals(List.of(1), writes, "回调里写槽位走的是事务, 没有旁路");
    }

    @Test
    void inventoryBindDeliversOwnerAndLetsCallbackPull() {
        MutableSignal<String> signal = Signal.of("a");
        VirtualInventory inventory = new VirtualInventory(3);
        List<SparrowInventory> owners = new ArrayList<>();
        List<String> values = new ArrayList<>();
        inventory.bind(signal, host -> {
            owners.add(host);
            values.add(signal.get());
        });
        signal.set("b");

        assertEquals(List.of(inventory), owners);
        assertEquals(List.of("b"), values);
    }

    @Test
    void windowBindDeliversOwnerAndLetsCallbackPull() {
        MutableSignal<String> season = Signal.of("spring");
        WindowStub window = new WindowStub(player());
        List<Window> owners = new ArrayList<>();
        List<String> values = new ArrayList<>();
        window.bind(season, host -> {
            owners.add(host);
            values.add(season.get());
        });
        window.open();
        season.set("summer");

        assertEquals(List.of(window), owners);
        assertEquals(List.of("summer"), values);
    }

    @Test
    void windowBindStaysDetachedUntilFirstOpen() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractSignal<String> internal = (AbstractSignal<String>) season;
        WindowStub window = new WindowStub(player());
        List<String> values = new ArrayList<>();
        Subscription subscription = window.bind(season, host -> values.add(season.get()));

        assertEquals(0, internal.entryCount(), "首次打开前不该占着上游的订阅表");
        season.set("summer");

        assertEquals(List.of(), values, "首次打开前不回调");
        assertFalse(subscription.isClosed(), "声明还在, 等首次打开");
        window.open();
        season.set("autumn");

        assertEquals(1, internal.entryCount());
        assertEquals(List.of("autumn"), values);
    }

    @Test
    void windowBindSuspendsWhileClosedAndResumesOnReopen() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractSignal<String> internal = (AbstractSignal<String>) season;
        WindowStub window = new WindowStub(player());
        List<String> values = new ArrayList<>();
        Subscription subscription = window.bind(season, host -> values.add(season.get()));
        window.open();

        assertEquals(1, internal.entryCount());
        window.close();

        assertEquals(0, internal.entryCount(), "关闭后不该继续占着上游的订阅表");
        season.set("summer");

        assertEquals(List.of(), values, "关闭期间不再回调");
        assertFalse(subscription.isClosed(), "声明还在, 只是挂起");
        window.open();

        assertEquals(1, internal.entryCount());
        season.set("autumn");

        assertEquals(List.of("autumn"), values);
    }

    @Test
    void bindingCanBeReleasedEarlyWithTheReturnedSubscription() {
        MutableSignal<String> season = Signal.of("spring");
        NormalPane pane = Pane.empty(3, 1);
        List<String> values = new ArrayList<>();
        Subscription subscription = pane.bind(season, host -> values.add(season.get()));
        subscription.close();
        season.set("summer");

        assertEquals(List.of(), values);
    }

    @Test
    void signalDoesNotPinThePane() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractSignal<String> internal = (AbstractSignal<String>) season;
        NormalPane pane = Pane.empty(3, 1);
        WeakReference<Pane> probe = new WeakReference<>(pane);
        pane.bind(season, host -> {
        });

        assertEquals(1, internal.entryCount());
        pane = null;
        GcSupport.awaitCollected(probe);
        season.set("summer");

        assertEquals(0, internal.entryCount(), "持有方被回收后绑定应自动消亡");
    }

    @Test
    void signalDoesNotPinTheWindow() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractSignal<String> internal = (AbstractSignal<String>) season;
        Player viewer = player();
        WindowStub window = new WindowStub(viewer);
        WeakReference<Window> probe = new WeakReference<>(window);
        window.bind(season, host -> {
        });
        window.open();

        assertEquals(1, internal.entryCount());
        window = null;
        GcSupport.awaitCollected(probe);
        season.set("summer");

        assertEquals(0, internal.entryCount());
        Reference.reachabilityFence(viewer);
    }

    @Test
    void signalDoesNotPinTheInventory() {
        MutableSignal<String> signal = Signal.of("a");
        AbstractSignal<String> internal = (AbstractSignal<String>) signal;
        VirtualInventory inventory = new VirtualInventory(3);
        WeakReference<SparrowInventory> probe = new WeakReference<>(inventory);
        inventory.bind(signal, host -> {
        });
        inventory = null;
        GcSupport.awaitCollected(probe);
        signal.set("b");

        assertEquals(0, internal.entryCount());
    }

    @Test
    void signalDoesNotPinTheInventoryVisualEvenWhenItsHandleIsRetained() {
        MutableSignal<String> signal = Signal.of("a");
        AbstractSignal<String> internal = (AbstractSignal<String>) signal;
        VisualBindingProbe probe = bindInventoryVisual(signal);
        GcSupport.awaitCollected(probe.host());
        GcSupport.awaitCollected(probe.visualizerCapture());
        signal.set("b");

        assertTrue(probe.handle().isClosed());
        assertEquals(0, internal.entryCount());
    }

    @Test
    void signalDoesNotPinTheWindowCursorVisual() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractSignal<String> internal = (AbstractSignal<String>) season;
        Player viewer = player();
        VisualBindingProbe probe = bindWindowCursorVisual(season, viewer);
        GcSupport.awaitCollected(probe.host());
        GcSupport.awaitCollected(probe.visualizerCapture());
        season.set("summer");

        assertTrue(probe.handle().isClosed());
        assertEquals(0, internal.entryCount());
        Reference.reachabilityFence(viewer);
    }

    @Test
    void closingHostBindingReleasesItsCallbackCaptureWhileHostRemainsAlive() {
        MutableSignal<String> signal = Signal.of("a");
        AbstractSignal<String> internal = (AbstractSignal<String>) signal;
        VirtualInventory inventory = new VirtualInventory(3);
        CallbackBindingProbe probe = bindCaptured(inventory, signal);
        probe.handle().close();
        GcSupport.awaitCollected(probe.capture());
        signal.set("b");

        assertTrue(probe.handle().isClosed());
        assertEquals(0, internal.entryCount());
        Reference.reachabilityFence(inventory);
    }

    @Test
    void multipleHostsShareOneSignal() {
        MutableSignal<String> season = Signal.of("spring");
        NormalPane first = Pane.empty(3, 1);
        NormalPane second = Pane.empty(3, 1);
        List<Pane> notified = new ArrayList<>();
        first.bind(season, host -> notified.add(host));
        second.bind(season, host -> notified.add(host));
        season.set("summer");

        assertEquals(2, notified.size());
        assertSame(first, notified.get(0));
        assertSame(second, notified.get(1));
    }

    private static VisualBindingProbe bindInventoryVisual(Signal<?> signal) {
        VirtualInventory inventory = new VirtualInventory(3);
        Object visualizerCapture = new Object();
        inventory.visual().setVisualizerProvider(ignoredItem -> {
            Reference.reachabilityFence(visualizerCapture);
            return null;
        });
        Subscription handle = inventory.visual().bind(signal);
        return new VisualBindingProbe(
                new WeakReference<>(inventory),
                new WeakReference<>(visualizerCapture),
                handle
        );
    }

    private static VisualBindingProbe bindWindowCursorVisual(Signal<?> signal, Player viewer) {
        WindowStub window = new WindowStub(viewer);
        Object visualizerCapture = new Object();
        window.cursorVisual().setVisualizerProvider(ignoredItem -> {
            Reference.reachabilityFence(visualizerCapture);
            return null;
        });
        Subscription handle = window.cursorVisual().bind(signal);
        return new VisualBindingProbe(
                new WeakReference<>(window),
                new WeakReference<>(visualizerCapture),
                handle
        );
    }

    private static CallbackBindingProbe bindCaptured(SparrowInventory inventory, Signal<?> signal) {
        Object captured = new Object();
        Subscription handle = inventory.bind(signal, ignoredInventory -> Reference.reachabilityFence(captured));
        return new CallbackBindingProbe(new WeakReference<>(captured), handle);
    }

    private static Player player() {
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                SignalHostBindingTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "SignalHostBindingPlayerStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private record VisualBindingProbe(
            WeakReference<?> host,
            WeakReference<Object> visualizerCapture,
            Subscription handle
    ) {
    }

    private record CallbackBindingProbe(WeakReference<Object> capture, Subscription handle) {
    }
}
