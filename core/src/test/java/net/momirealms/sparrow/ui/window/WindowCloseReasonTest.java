package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowCloseReasonTest {

    private ServerMock server;

    @BeforeAll
    static void initializeProxy() {
        BukkitProxy.init("1.21.8", List.of("paper"));
    }

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void paperReasonsMapByNameInBothDirections() {
        InventoryView view = this.server.addPlayer().getOpenInventory();
        InventoryCloseEvent.Reason[] paperReasons = InventoryCloseEvent.Reason.values();
        for (int index = 0; index < paperReasons.length; index++) {
            InventoryCloseEvent.Reason paperReason = paperReasons[index];
            WindowCloseReason reason = WindowCloseReason.valueOf(paperReason.name());

            assertEquals(reason, WindowCloseReasonAdapter.fromBukkit(new InventoryCloseEvent(view, paperReason)));
            assertEquals(reason.name(), ((Enum<?>) WindowCloseReasonAdapter.toPaper(reason)).name());
        }
    }

    @Test
    void publicWindowApiDoesNotExposeThePaperReason() {
        Class<?>[] apiTypes = {Window.class, Window.Builder.class, WindowSession.class};
        for (int typeIndex = 0; typeIndex < apiTypes.length; typeIndex++) {
            Method[] methods = apiTypes[typeIndex].getMethods();
            for (int methodIndex = 0; methodIndex < methods.length; methodIndex++) {
                assertFalse(methods[methodIndex].toGenericString().contains("InventoryCloseEvent$Reason"));
            }
        }
    }
}
