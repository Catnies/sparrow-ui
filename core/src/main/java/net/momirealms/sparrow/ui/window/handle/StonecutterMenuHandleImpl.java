package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.network.filter.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.network.filter.ClientboundStateProjection;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.PacketFlow;
import net.momirealms.sparrow.ui.network.PacketIdRegistry;
import net.momirealms.sparrow.ui.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.registries.BuiltInRegistriesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundUpdateRecipesPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackTemplateProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.*;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.ItemStackSlotDisplayProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings("UnstableApiUsage")
final class StonecutterMenuHandleImpl extends ContainerMenuHandle implements StonecutterMenuHandle {
    // 协议槽位与占位物
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 1;
    private static final int SELECTED_DATA_SLOT = 0;
    private static final Object PLACEHOLDER = ItemUtils.invisibleBarrier();

    // 原版配方目录
    private static final Object RECIPE_MANAGER = MinecraftServerProxy.INSTANCE.getRecipeManager(MinecraftServerProxy.INSTANCE.getServer());
    private static final Object ALL_ITEMS = IngredientProxy.INSTANCE.of(
            RegistryProxy.INSTANCE.stream(BuiltInRegistriesProxy.ITEM).filter(item -> item != ItemsProxy.AIR)
    );
    private static final ClientboundStateProjection RECIPE_CATALOG_PROJECTION = new ClientboundStateProjection() {
        @Override
        public int[] suppressedPacketIds(@NotNull PacketIdRegistry packetIds) {
            return new int[]{packetIds.byName("minecraft:update_recipes", ConnectionState.PLAY, PacketFlow.CLIENTBOUND)};
        }

        @Override
        public boolean suppresses(@NotNull Object packet) {
            return ClientboundUpdateRecipesPacketProxy.CLASS.isInstance(packet);
        }

        @Override
        @NotNull
        public Object createNativeRestorePacket() {
            return ClientboundUpdateRecipesPacketProxy.INSTANCE.newInstance(
                    RecipeManagerProxy.INSTANCE.getSynchronizedItemProperties(RECIPE_MANAGER),
                    RecipeManagerProxy.INSTANCE.getSynchronizedStonecutterRecipes(RECIPE_MANAGER)
            );
        }
    };

    // 当前按钮与客户端投影
    private List<ItemStack> recipeButtons = List.of();
    private Object clientInput = PLACEHOLDER;
    private Object clientResult = ItemStackProxy.EMPTY;
    private int selectedRecipeIndex = -1;

    // 待发送变更
    private boolean recipeButtonsDirty = true;
    private boolean dataDirty = true;

    // 当前网络批次
    private boolean recipeButtonsQueued;
    private boolean dataQueued;

    StonecutterMenuHandleImpl(MenuPacketGateway packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.STONECUTTER,
                InventoryType.STONECUTTER,
                org.bukkit.inventory.MenuType.STONECUTTER,
                2,
                generation
        );
    }

    @Override
    public void setRecipeButtons(ItemStack @NotNull [] buttons) {
        Objects.requireNonNull(buttons, "buttons");
        for (int index = 0; index < buttons.length; index++) {
            Objects.requireNonNull(buttons[index], "buttons contains null");
        }
        // 内容没变时沿用已有按钮快照和客户端目录.
        if (this.sameRecipeButtons(buttons)) return;
        // 存下独立副本, 调用方随后可以复用或修改原数组.
        ItemStack[] copy = new ItemStack[buttons.length];
        for (int index = 0; index < buttons.length; index++) {
            copy[index] = ItemUtils.copyOrEmpty(buttons[index]);
        }
        List<ItemStack> snapshot = List.of(copy);

        this.recipeButtons = snapshot;
        if (this.selectedRecipeIndex >= snapshot.size()) {
            this.selectedRecipeIndex = -1;
        }
        this.recipeButtonsDirty = true;
        this.dataDirty = true;
        this.forceRemoteSlot(RESULT_SLOT);
    }

    // 当前快照已归一化空物品, 比较输入时先对齐空值语义.
    private boolean sameRecipeButtons(ItemStack @NotNull [] buttons) {
        if (this.recipeButtons.size() != buttons.length) {
            return false;
        }
        for (int index = 0; index < buttons.length; index++) {
            ItemStack current = this.recipeButtons.get(index);
            ItemStack incoming = buttons[index];
            if (current.isEmpty() != incoming.isEmpty()) {
                return false;
            }
            if (!current.isEmpty() && !current.equals(incoming)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setSelectedRecipeIndex(int index) {
        this.checkSelectedRecipeIndex(index);
        if (this.selectedRecipeIndex == index) {
            return;
        }
        this.selectedRecipeIndex = index;
        this.dataDirty = true;
        this.forceRemoteSlot(RESULT_SLOT);
    }

    @Override
    public void reconcileClientSelection(int index) {
        this.checkSelectedRecipeIndex(index);
        this.selectedRecipeIndex = index;
        this.dataDirty = true;
        this.forceRemoteSlot(RESULT_SLOT);
    }

    @Override
    @NotNull
    protected ClientboundPacketFilter clientboundPacketFilter() {
        return RECIPE_CATALOG_PROJECTION;
    }

    @Override
    protected void handleAcceptedInteraction() {
        this.dataDirty = true;
        this.forceRemoteSlot(RESULT_SLOT);
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.recipeButtonsQueued = forceFull || this.recipeButtonsDirty;
        this.dataQueued = forceFull || this.dataDirty || this.recipeButtonsQueued;
        if (this.recipeButtonsQueued) {
            outgoing.add(ClientboundUpdateRecipesPacketProxy.INSTANCE.newInstance(
                    RecipeManagerProxy.INSTANCE.getSynchronizedItemProperties(RECIPE_MANAGER),
                    StonecutterMenuHandleImpl.createRecipeEntries(this.recipeButtons)
            ));
            outgoing.add(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                    this.containerId(), this.incrementStateId(), INPUT_SLOT, ItemStackProxy.EMPTY
            ));
            outgoing.add(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                    this.containerId(), this.incrementStateId(), INPUT_SLOT, this.clientInput
            ));
            outgoing.add(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                    this.containerId(), this.incrementStateId(), RESULT_SLOT, this.clientResult
            ));
        }
        if (this.dataQueued) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(), SELECTED_DATA_SLOT, this.selectedRecipeIndex
            ));
        }
    }

    @Override
    protected void commitPackets() {
        if (this.recipeButtonsQueued) {
            this.recipeButtonsDirty = false;
            this.recipeButtonsQueued = false;
        }
        if (this.dataQueued) {
            this.dataDirty = false;
            this.dataQueued = false;
        }
    }

    @Override
    protected Object toClientItem(int rawSlot, ItemStack item) {
        // 原版配方界面要求输入槽非空, 空输入使用不可见占位物.
        Object clientItem = rawSlot == INPUT_SLOT && item.isEmpty()
                ? PLACEHOLDER
                : super.toClientItem(rawSlot, item);
        if (rawSlot == INPUT_SLOT) {
            this.clientInput = ItemStackProxy.INSTANCE.copy(clientItem);
        } else if (rawSlot == RESULT_SLOT) {
            this.clientResult = ItemStackProxy.INSTANCE.copy(clientItem);
        }
        return clientItem;
    }

    private void checkSelectedRecipeIndex(int index) {
        if (index < -1 || index >= this.recipeButtons.size()) {
            throw new IndexOutOfBoundsException("stonecutter selected recipe index out of bounds: " + index);
        }
    }

    private static Object createRecipeEntries(List<ItemStack> buttons) {
        ArrayList<Object> entries = new ArrayList<>(buttons.size());
        for (int index = 0; index < buttons.size(); index++) {
            ItemStack button = buttons.get(index);
            Object stack = button.isEmpty() ? PLACEHOLDER : ItemUtils.getItemStackHandle(button);
            Object display;
            if (VersionHelper.isOrAbove26_1()) {
                Object template = ItemStackTemplateProxy.INSTANCE.fromNonEmptyStack(stack);
                display = ItemStackSlotDisplayProxy.INSTANCE.newInstance$0(template);
            } else {
                display = ItemStackSlotDisplayProxy.INSTANCE.newInstance(stack);
            }
            Object selectable = SelectableRecipeProxy.INSTANCE.newInstance(display, Optional.empty());
            entries.add(SelectableRecipeSingleInputEntryProxy.INSTANCE.newInstance(ALL_ITEMS, selectable));
        }
        return SelectableRecipeSingleInputSetProxy.INSTANCE.newInstance(entries);
    }

}
