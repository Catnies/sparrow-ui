package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.TooltipDisplayProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Paper 铁砧菜单句柄, 负责文本框可用性、结果槽有效性与经验消耗数据同步.
 */
@SuppressWarnings("UnstableApiUsage")
final class AnvilMenuHandleImpl extends PaperMenuHandle implements AnvilMenuHandle {
    private static final int ENCHANTMENT_COST_DATA_SLOT = 0;
    private static final Object PLACEHOLDER = AnvilMenuHandleImpl.createPlaceholder(); // NMS ItemStack 不可见占位快照

    private int enchantmentCost;
    private boolean textFieldAlwaysEnabled;
    private boolean resultAlwaysValid;
    private boolean dataDirty = true;
    private boolean dataQueued;

    AnvilMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.INSTANCE.ANVIL(),
                InventoryType.ANVIL,
                org.bukkit.inventory.MenuType.ANVIL,
                3,
                generation
        );
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
    protected void appendMenuDataPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.dataQueued = forceFull || this.dataDirty;
        if (this.dataQueued) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
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
    protected Object toClientItem(int rawSlot, ItemStack item) {
        if (item.isEmpty() && rawSlot == 0 && this.textFieldAlwaysEnabled) {
            return PLACEHOLDER;
        }
        if (item.isEmpty() && rawSlot == 2 && this.resultAlwaysValid) {
            return PLACEHOLDER;
        }
        return super.toClientItem(rawSlot, item);
    }

    private static Object createPlaceholder() {
        Object placeholder = ItemStackProxy.INSTANCE.newInstance(ItemsProxy.INSTANCE.barrier()); // NMS ItemStack
        ItemStackProxy.INSTANCE.set(
                placeholder,
                DataComponentsProxy.INSTANCE.customName(),
                ComponentProxy.INSTANCE.empty()
        );
        ItemStackProxy.INSTANCE.set(
                placeholder,
                DataComponentsProxy.INSTANCE.tooltipDisplay(),
                TooltipDisplayProxy.INSTANCE.newInstance(true, new LinkedHashSet<>())
        );
        ItemStackProxy.INSTANCE.set(
                placeholder,
                DataComponentsProxy.INSTANCE.itemModel(),
                IdentifierProxy.INSTANCE.withDefaultNamespace("air")
        );
        return placeholder;
    }
}
