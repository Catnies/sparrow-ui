package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.proxy.bukkit.event.inventory.InventoryCloseEventProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.event.inventory.InventoryCloseReasonProxy;
import net.momirealms.sparrow.ui.util.ReflectionUtils;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// getReason 是比较新的 API, 老服务端上没有这个方法, 那边一律给 UNKNOWN.
@ApiStatus.Internal
public final class WindowCloseReasonAdapter {
    private static final boolean BUKKIT_REASON_AVAILABLE = ReflectionUtils.methodExists(InventoryCloseEvent.class, "getReason");

    private WindowCloseReasonAdapter() {
    }

    // Bukkit -> Sparrow; 认不出的名字给 UNKNOWN, 不往外抛
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

    // Sparrow -> Paper; 两边这套名字是对齐的, 按名字取对方的枚举值
    @NotNull
    public static Object toPaper(@NotNull WindowCloseReason reason) {
        return InventoryCloseReasonProxy.INSTANCE.valueOf(reason.name());
    }
}
