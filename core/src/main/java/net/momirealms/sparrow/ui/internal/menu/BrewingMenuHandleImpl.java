package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class BrewingMenuHandleImpl extends PaperMenuHandle implements BrewingMenuHandle {
    private static final int BREW_TIME_DATA_SLOT = 0;
    private static final int FUEL_USES_DATA_SLOT = 1;

    private int brewTicks;
    private int fuelUses;
    private boolean brewDirty = true;
    private boolean fuelDirty = true;
    private boolean brewQueued;
    private boolean fuelQueued;

    BrewingMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.BREWING_STAND,
                InventoryType.BREWING,
                org.bukkit.inventory.MenuType.BREWING_STAND,
                5,
                generation
        );
    }

    @Override
    public void setBrewProgress(double progress) {
        int nextTicks = progress == 0.0 ? 0 : (int) Math.round((1.0 - progress) * 400.0);
        if (this.brewTicks != nextTicks) {
            this.brewTicks = nextTicks;
            this.brewDirty = true;
        }
    }

    @Override
    public void setFuelProgress(double progress) {
        int nextUses = (int) Math.round(progress * 20.0);
        if (this.fuelUses != nextUses) {
            this.fuelUses = nextUses;
            this.fuelDirty = true;
        }
    }

    @Override
    protected void appendMenuDataPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.brewQueued = forceFull || this.brewDirty;
        this.fuelQueued = forceFull || this.fuelDirty;
        if (this.brewQueued) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(),
                    BREW_TIME_DATA_SLOT,
                    this.brewTicks
            ));
        }
        if (this.fuelQueued) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(),
                    FUEL_USES_DATA_SLOT,
                    this.fuelUses
            ));
        }
    }

    @Override
    protected void commitMenuDataPackets() {
        if (this.brewQueued) {
            this.brewDirty = false;
            this.brewQueued = false;
        }
        if (this.fuelQueued) {
            this.fuelDirty = false;
            this.fuelQueued = false;
        }
    }
}
