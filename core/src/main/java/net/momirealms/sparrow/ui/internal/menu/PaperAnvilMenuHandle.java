package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.world.inventory.MenuType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class PaperAnvilMenuHandle extends PaperMenuHandle implements AnvilMenuHandle {
    private static final int ENCHANTMENT_COST_DATA_SLOT = 0;

    private final net.minecraft.world.item.ItemStack placeholder;
    private int enchantmentCost;
    private boolean textFieldAlwaysEnabled;
    private boolean resultAlwaysValid;
    private boolean dataDirty = true;
    private boolean dataQueued;

    PaperAnvilMenuHandle(
            PacketListener packets,
            Player player,
            long generation
    ) {
        super(
                packets,
                player,
                MenuType.ANVIL,
                InventoryType.ANVIL,
                org.bukkit.inventory.MenuType.ANVIL,
                3,
                generation
        );
        this.placeholder = PaperAnvilMenuHandle.createPlaceholder();
    }

    @Override
    public void handleRename(@NotNull String text) {
        this.forceRemoteSlot(2);
        this.dataDirty = true;
    }

    @Override
    public void setEnchantmentCost(int enchantmentCost) {
        if (this.enchantmentCost != enchantmentCost) {
            this.enchantmentCost = enchantmentCost;
            this.dataDirty = true;
        }
    }

    @Override
    public void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled) {
        this.textFieldAlwaysEnabled = textFieldAlwaysEnabled;
    }

    @Override
    public void setResultAlwaysValid(boolean resultAlwaysValid) {
        this.resultAlwaysValid = resultAlwaysValid;
    }

    @Override
    protected void prepareMenuSynchronization(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (forceFull || dirtySlots.get(1)) {
            this.dataDirty = true;
        }
    }

    @Override
    protected void appendMenuDataPackets(
            @NotNull List<Packet<? super ClientGamePacketListener>> outgoing,
            boolean forceFull
    ) {
        this.dataQueued = forceFull || this.dataDirty;
        if (this.dataQueued) {
            outgoing.add(new ClientboundContainerSetDataPacket(
                    this.containerId(),
                    ENCHANTMENT_COST_DATA_SLOT,
                    this.enchantmentCost
            ));
        }
    }

    @Override
    protected void commitMenuDataPackets() {
        if (this.dataQueued) {
            this.dataDirty = false;
            this.dataQueued = false;
        }
    }

    @Override
    protected net.minecraft.world.item.ItemStack toClientItem(int rawSlot, ItemStack item) {
        if (item.isEmpty() && rawSlot == 0 && this.textFieldAlwaysEnabled) {
            return this.placeholder.copy();
        }
        if (item.isEmpty() && rawSlot == 2 && this.resultAlwaysValid) {
            return this.placeholder.copy();
        }
        return super.toClientItem(rawSlot, item);
    }

    private static net.minecraft.world.item.ItemStack createPlaceholder() {
        ItemStack placeholder = new ItemStack(Material.BARRIER);
        ItemMeta meta = placeholder.getItemMeta();
        meta.customName(Component.empty());
        meta.setHideTooltip(true);
        meta.setItemModel(NamespacedKey.minecraft("air"));
        placeholder.setItemMeta(meta);
        return PaperMenuHandle.toNms(placeholder);
    }
}
