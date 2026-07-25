package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * Paper 砂轮菜单句柄, 负责纠正输入变化后客户端预测的结果槽.
 */
@SuppressWarnings("UnstableApiUsage")
final class GrindstoneMenuHandleImpl extends PaperMenuHandle {
    private static final int RESULT_SLOT = 2;

    GrindstoneMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.GRINDSTONE,
                InventoryType.GRINDSTONE,
                org.bukkit.inventory.MenuType.GRINDSTONE,
                3,
                generation
        );
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (dirtySlots.get(0) || dirtySlots.get(1)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }
}
