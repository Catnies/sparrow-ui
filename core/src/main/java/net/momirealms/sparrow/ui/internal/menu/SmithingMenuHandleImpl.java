package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * Paper 锻造台菜单句柄, 负责纠正输入变化后客户端预测的结果槽.
 */
@SuppressWarnings("UnstableApiUsage")
final class SmithingMenuHandleImpl extends ContainerMenuHandle {
    private static final int RESULT_SLOT = 3;

    SmithingMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.SMITHING,
                InventoryType.SMITHING,
                org.bukkit.inventory.MenuType.SMITHING,
                4,
                generation
        );
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (dirtySlots.get(0) || dirtySlots.get(1) || dirtySlots.get(2)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }
}
