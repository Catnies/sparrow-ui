package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

/**
 * Paper 投掷器菜单句柄.
 */
@SuppressWarnings("UnstableApiUsage")
final class DropperMenuHandleImpl extends PaperMenuHandle {

    DropperMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.GENERIC_3x3,
                InventoryType.DROPPER,
                org.bukkit.inventory.MenuType.GENERIC_3X3,
                9,
                generation
        );
    }
}
