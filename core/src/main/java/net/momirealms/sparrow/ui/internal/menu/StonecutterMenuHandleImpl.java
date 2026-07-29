package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.internal.network.ClientboundStateProjection;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
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
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 1;
    private static final int SELECTED_DATA_SLOT = 0;
    private static final Object PLACEHOLDER = ItemUtils.invisibleBarrier();
    private static final Object RECIPE_MANAGER = MinecraftServerProxy.INSTANCE.getRecipeManager(MinecraftServerProxy.INSTANCE.getServer());
    private static final Object ALL_ITEMS = IngredientProxy.INSTANCE.of(
            RegistryProxy.INSTANCE.stream(BuiltInRegistriesProxy.ITEM).filter(item -> item != ItemsProxy.AIR)
    );
    private static final ClientboundStateProjection RECIPE_CATALOG_PROJECTION = new ClientboundStateProjection() {
        @Override
        public boolean suppresses(@NotNull Object packet) {
            return ClientboundUpdateRecipesPacketProxy.CLASS.isInstance(packet);
        }

        @NotNull
        @Override
        public Object stateKey() {
            return ClientState.SYNCHRONIZED_RECIPES;
        }

        @Override
        public void appendNativeRestore(@NotNull Player player, @NotNull List<Object> packets) {
            packets.add(ClientboundUpdateRecipesPacketProxy.INSTANCE.newInstance(
                    RecipeManagerProxy.INSTANCE.getSynchronizedItemProperties(RECIPE_MANAGER),
                    RecipeManagerProxy.INSTANCE.getSynchronizedStonecutterRecipes(RECIPE_MANAGER)
            ));
        }
    };

    private List<ItemStack> recipeButtons = List.of();
    private Object clientInput = PLACEHOLDER;
    private Object clientResult = ItemStackProxy.EMPTY;
    private int selectedRecipeIndex = -1;
    private boolean recipeButtonsDirty = true;
    private boolean dataDirty = true;
    private boolean recipeButtonsQueued;
    private boolean dataQueued;

    StonecutterMenuHandleImpl(PacketListener packets, Player player, long generation) {
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
        ArrayList<ItemStack> copy = new ArrayList<>(buttons.length);
        for (int index = 0; index < buttons.length; index++) {
            copy.add(ItemUtils.copyOrEmpty(Objects.requireNonNull(buttons[index], "buttons contains null")));
        }
        List<ItemStack> snapshot = List.copyOf(copy);
        if (this.recipeButtons.equals(snapshot)) {
            return;
        }

        this.recipeButtons = snapshot;
        if (this.selectedRecipeIndex >= snapshot.size()) {
            this.selectedRecipeIndex = -1;
        }
        this.recipeButtonsDirty = true;
        this.dataDirty = true;
        this.forceRemoteSlot(RESULT_SLOT);
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
    protected ClientboundPacketFilter clientboundPacketFilters() {
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
        // 强制输入槽物品不为空, 至少需要一个占位物品以支持配方界面.
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

    private enum ClientState {
        SYNCHRONIZED_RECIPES
    }
}
