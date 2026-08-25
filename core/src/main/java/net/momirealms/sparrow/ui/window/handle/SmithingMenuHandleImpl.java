package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

@SuppressWarnings("UnstableApiUsage")
final class SmithingMenuHandleImpl extends ContainerMenuHandle {
    private static final int RESULT_SLOT = 3;

    SmithingMenuHandleImpl(MenuPacketGateway packets, Player player, long generation) {
        super(packets, player, MenuTypeProxy.SMITHING, InventoryType.SMITHING, org.bukkit.inventory.MenuType.SMITHING, 4, generation);
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        // 输入变化会触发客户端本地重算, 结果槽随后由服务端覆盖.
        if (dirtySlots.get(0) || dirtySlots.get(1) || dirtySlots.get(2)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }
}
