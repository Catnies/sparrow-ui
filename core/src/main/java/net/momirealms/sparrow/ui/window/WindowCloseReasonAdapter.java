package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.proxy.bukkit.event.inventory.InventoryCloseEventProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.event.inventory.InventoryCloseReasonProxy;
import net.momirealms.sparrow.ui.util.ReflectionUtils;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class WindowCloseReasonAdapter {
    private static final boolean BUKKIT_REASON_AVAILABLE = ReflectionUtils.methodExists(InventoryCloseEvent.class, "getReason");

    private WindowCloseReasonAdapter() {
    }

    @NotNull
    public static WindowCloseReason fromBukkit(@NotNull InventoryCloseEvent event) {
        if (!BUKKIT_REASON_AVAILABLE) {
            return WindowCloseReason.UNKNOWN;
        }
        String reason = ((Enum<?>) InventoryCloseEventProxy.INSTANCE.getReason(event)).name();
        return switch (reason) {
            case "TELEPORT" -> WindowCloseReason.TELEPORT;
            case "CANT_USE" -> WindowCloseReason.CANT_USE;
            case "UNLOADED" -> WindowCloseReason.UNLOADED;
            case "OPEN_NEW" -> WindowCloseReason.OPEN_NEW;
            case "PLAYER" -> WindowCloseReason.PLAYER;
            case "DISCONNECT" -> WindowCloseReason.DISCONNECT;
            case "DEATH" -> WindowCloseReason.DEATH;
            case "PLUGIN" -> WindowCloseReason.PLUGIN;
            default -> WindowCloseReason.UNKNOWN;
        };
    }

    @NotNull
    public static Object toPaper(@NotNull WindowCloseReason reason) {
        return InventoryCloseReasonProxy.INSTANCE.valueOf(reason.name());
    }
}
