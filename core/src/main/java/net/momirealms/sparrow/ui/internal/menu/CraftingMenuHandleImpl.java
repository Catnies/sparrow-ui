package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

@SuppressWarnings("UnstableApiUsage")
final class CraftingMenuHandleImpl extends AbstractRecipeBookMenuHandle {
    private static final int RESULT_SLOT = 0;
    private static final int CRAFTING_SLOT_START = 1;
    private static final int CRAFTING_SLOT_END = 10;

    CraftingMenuHandleImpl(MenuPacketGateway packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.CRAFTING,
                InventoryType.WORKBENCH,
                org.bukkit.inventory.MenuType.CRAFTING,
                10,
                generation
        );
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        // 合成输入变化会触发客户端本地重算, 结果槽随后由服务端覆盖.
        if (CraftingMenuHandleImpl.hasDirtyCraftingSlot(dirtySlots)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }

    static boolean hasDirtyCraftingSlot(@NotNull BitSet dirtySlots) {
        int firstDirty = dirtySlots.nextSetBit(CRAFTING_SLOT_START);
        return firstDirty >= CRAFTING_SLOT_START && firstDirty < CRAFTING_SLOT_END;
    }
}
