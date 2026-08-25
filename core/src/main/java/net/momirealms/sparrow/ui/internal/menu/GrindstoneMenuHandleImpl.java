package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

@SuppressWarnings("UnstableApiUsage")
final class GrindstoneMenuHandleImpl extends ContainerMenuHandle {
    private static final int RESULT_SLOT = 2;

    GrindstoneMenuHandleImpl(MenuPacketGateway packets, Player player, long generation) {
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
        // 输入变化会触发客户端本地重算, 结果槽随后由服务端覆盖.
        if (dirtySlots.get(0) || dirtySlots.get(1)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }
}
