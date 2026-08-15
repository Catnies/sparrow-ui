package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class AnvilMenuHandleImpl extends ContainerMenuHandle implements AnvilMenuHandle {
    private static final int ENCHANTMENT_COST_DATA_SLOT = 0;
    private static final Object PLACEHOLDER = ItemUtils.invisibleBarrier(); // NMS ItemStack 不可见占位快照

    private final InputPlaceholder inputPlaceholder = new InputPlaceholder(PLACEHOLDER);
    private int enchantmentCost;
    private boolean textFieldAlwaysEnabled;
    private boolean resultAlwaysValid;
    private boolean dataDirty = true;
    private boolean dataQueued;

    AnvilMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.ANVIL,
                InventoryType.ANVIL,
                org.bukkit.inventory.MenuType.ANVIL,
                3,
                generation
        );
    }

    @Override
    public void handleRename(@NotNull String text) {
        this.inputPlaceholder.renameText(text);
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
        if (this.textFieldAlwaysEnabled != textFieldAlwaysEnabled) {
            this.textFieldAlwaysEnabled = textFieldAlwaysEnabled;
            this.forceRemoteSlot(0);
        }
    }

    @Override
    public void setResultAlwaysValid(boolean resultAlwaysValid) {
        if (this.resultAlwaysValid != resultAlwaysValid) {
            this.resultAlwaysValid = resultAlwaysValid;
            this.forceRemoteSlot(2);
        }
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (forceFull) {
            this.dataDirty = true;
        } else if (dirtySlots.get(0) || dirtySlots.get(1)) {
            // 客户端应用任一输入槽纠正时会重新计算铁砧结果; 结果槽必须随后覆盖, cost data 再在批次末尾覆盖.
            this.forceRemoteSlot(2);
            this.dataDirty = true;
        }
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
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
    protected void commitPackets() {
        if (this.dataQueued) {
            this.dataDirty = false;
            this.dataQueued = false;
        }
    }

    @Override
    protected Object toClientItem(int rawSlot, ItemStack item) {
        if (item.isEmpty() && rawSlot == 0 && this.textFieldAlwaysEnabled) {
            return this.inputPlaceholder.item();
        }
        if (item.isEmpty() && rawSlot == 2 && this.resultAlwaysValid) {
            return PLACEHOLDER;
        }
        return super.toClientItem(rawSlot, item);
    }

    static final class InputPlaceholder {
        private final Object shared;
        private String renameText = "";
        private @Nullable Object projected;

        InputPlaceholder(Object shared) {
            this.shared = shared;
        }

        void renameText(@NotNull String renameText) {
            if (!this.renameText.equals(renameText)) {
                this.renameText = renameText;
                this.projected = null;
            }
        }

        Object item() {
            if (this.renameText.isEmpty()) {
                return this.shared;
            }
            if (this.projected == null) {
                this.projected = ItemStackProxy.INSTANCE.copy(this.shared); // 独立 NMS ItemStack
                ItemStackProxy.INSTANCE.set(this.projected, DataComponentsProxy.CUSTOM_NAME, ComponentProxy.INSTANCE.literal(this.renameText));
            }
            return this.projected;
        }
    }
}
