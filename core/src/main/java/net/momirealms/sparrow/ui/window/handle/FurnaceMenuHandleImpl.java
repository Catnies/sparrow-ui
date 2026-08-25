package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class FurnaceMenuHandleImpl extends AbstractRecipeBookMenuHandle implements FurnaceMenuHandle {
    private static final int UPPER_SIZE = 3;

    private final DataSlots dataSlots = new DataSlots();

    FurnaceMenuHandleImpl(
            @NotNull MenuPacketGateway packets,
            @NotNull Player player,
            @NotNull Object menuType,
            @NotNull InventoryType inventoryType,
            @NotNull MenuType bukkitMenuType,
            long generation
    ) {
        super(packets, player, menuType, inventoryType, bukkitMenuType, UPPER_SIZE, generation);
    }

    @Override
    public void setCookProgress(double progress) {
        this.dataSlots.setCookProgress(progress);
    }

    @Override
    public void setFuelProgress(double progress) {
        this.dataSlots.setFuelProgress(progress);
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.dataSlots.queue(forceFull);
        for (
                int slot = this.dataSlots.nextQueuedSlot(0);
                slot >= 0;
                slot = this.dataSlots.nextQueuedSlot(slot + 1)
        ) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(),
                    slot,
                    this.dataSlots.value(slot)
            ));
        }
    }

    @Override
    protected void commitPackets() {
        this.dataSlots.commit();
    }

    // 两个比例映射到原版四个 data slot, total 固定为同一量程.
    static final class DataSlots {
        // 原版 data slot 布局
        static final int FUEL_REMAINING_SLOT = 0;
        static final int FUEL_TOTAL_SLOT = 1;
        static final int COOK_ELAPSED_SLOT = 2;
        static final int COOK_TOTAL_SLOT = 3;
        static final int PROGRESS_SCALE = 200;
        private static final int DATA_SLOT_COUNT = 4;

        // 待发送与当前批次
        private final BitSet dirtySlots = new BitSet(DATA_SLOT_COUNT);
        private final BitSet queuedSlots = new BitSet(DATA_SLOT_COUNT);

        // 当前比例值
        private int fuelRemaining;
        private int cookElapsed;

        void setCookProgress(double progress) {
            int nextElapsed = (int) Math.round(progress * PROGRESS_SCALE);
            if (this.cookElapsed != nextElapsed) {
                this.cookElapsed = nextElapsed;
                this.dirtySlots.set(COOK_ELAPSED_SLOT);
            }
        }

        void setFuelProgress(double progress) {
            int nextRemaining = (int) Math.round(progress * PROGRESS_SCALE);
            if (this.fuelRemaining != nextRemaining) {
                this.fuelRemaining = nextRemaining;
                this.dirtySlots.set(FUEL_REMAINING_SLOT);
            }
        }

        void queue(boolean forceFull) {
            this.queuedSlots.clear();
            if (forceFull) {
                this.queuedSlots.set(0, DATA_SLOT_COUNT);
            } else {
                this.queuedSlots.or(this.dirtySlots);
            }
        }

        int nextQueuedSlot(int fromIndex) {
            return this.queuedSlots.nextSetBit(fromIndex);
        }

        int value(int slot) {
            return switch (slot) {
                case FUEL_REMAINING_SLOT -> this.fuelRemaining;
                case FUEL_TOTAL_SLOT, COOK_TOTAL_SLOT -> PROGRESS_SCALE;
                case COOK_ELAPSED_SLOT -> this.cookElapsed;
                default -> throw new IndexOutOfBoundsException("furnace data slot out of bounds: " + slot);
            };
        }

        void commit() {
            this.dirtySlots.andNot(this.queuedSlots);
            this.queuedSlots.clear();
        }
    }
}
