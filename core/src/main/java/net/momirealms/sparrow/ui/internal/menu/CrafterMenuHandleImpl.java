package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class CrafterMenuHandleImpl extends PaperMenuHandle implements CrafterMenuHandle {
    private static final int CRAFTING_SLOTS = 9;
    private static final int UPPER_SIZE = CRAFTING_SLOTS + 1;
    private static final int LOWER_START = CRAFTING_SLOTS;

    private final boolean[] disabledSlots = new boolean[CRAFTING_SLOTS];
    private final BitSet dirtyData = new BitSet(CRAFTING_SLOTS);
    private final BitSet queuedData = new BitSet(CRAFTING_SLOTS);

    CrafterMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.CRAFTER_3x3,
                InventoryType.CRAFTER,
                MenuType.CRAFTER_3X3,
                UPPER_SIZE,
                LOWER_START,
                generation
        );
        this.dirtyData.set(0, CRAFTING_SLOTS);
    }

    @Override
    public void setSlotDisabled(int slot, boolean disabled) {
        CrafterMenuHandleImpl.checkSlot(slot);
        if (this.disabledSlots[slot] == disabled) {
            return;
        }
        this.disabledSlots[slot] = disabled;
        this.dirtyData.set(slot);
        this.forceRemoteSlot(slot);
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.queuedData.clear();
        if (forceFull) {
            this.queuedData.set(0, CRAFTING_SLOTS);
        } else {
            this.queuedData.or(this.dirtyData);
        }
        for (
                int slot = this.queuedData.nextSetBit(0);
                slot >= 0;
                slot = this.queuedData.nextSetBit(slot + 1)
        ) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(),
                    slot,
                    this.disabledSlots[slot] ? 1 : 0
            ));
        }
    }

    @Override
    protected void commitPackets() {
        this.dirtyData.andNot(this.queuedData);
        this.queuedData.clear();
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= CRAFTING_SLOTS) {
            throw new IndexOutOfBoundsException("crafter slot out of bounds: " + slot);
        }
    }
}
