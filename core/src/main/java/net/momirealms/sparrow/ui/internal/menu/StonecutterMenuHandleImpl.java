package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.registries.BuiltInRegistriesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundUpdateRecipesPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.AbstractContainerMenuProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackTemplateProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.TooltipDisplayProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.*;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.ItemStackSlotDisplayProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import net.momirealms.sparrow.ui.window.StonecutterRecipeOption;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings("UnstableApiUsage")
final class StonecutterMenuHandleImpl extends PaperMenuHandle implements StonecutterMenuHandle {
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 1;
    private static final int SELECTED_DATA_SLOT = 0;
    private static final Object PLACEHOLDER = StonecutterMenuHandleImpl.createPlaceholder();
    private static final Object ALL_ITEMS = StonecutterMenuHandleImpl.createAllItemsIngredient();
    private static final Set<Class<?>> DISCARDED_OUTGOING = Set.of(ClientboundUpdateRecipesPacketProxy.CLASS);

    private List<StonecutterRecipeOption> recipeOptions = List.of();
    private Object clientInput = PLACEHOLDER;
    private Object clientResult = ItemStackProxy.EMPTY;
    private int selectedRecipeIndex = -1;
    private boolean recipeOptionsDirty = true;
    private boolean dataDirty = true;
    private boolean recipeOptionsQueued;
    private boolean dataQueued;
    private boolean recipeRestoreHandled;

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
    public void setRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options) {
        this.recipeOptions = List.copyOf(options);
        this.selectedRecipeIndex = -1;
        this.recipeOptionsDirty = true;
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
    public void close(@NotNull InventoryCloseEvent.Reason reason) {
        boolean restoreRecipes = false;
        if (!this.recipeRestoreHandled) {
            this.recipeRestoreHandled = true;
            restoreRecipes = reason != InventoryCloseEvent.Reason.DISCONNECT
                    && this.shouldRestoreOutgoing(ClientboundUpdateRecipesPacketProxy.CLASS);
        }

        Throwable failure = null;
        try {
            super.close(reason);
        } catch (RuntimeException | Error throwable) {
            failure = throwable;
        }
        if (restoreRecipes) {
            try {
                this.packets.send(this.player, List.of(StonecutterMenuHandleImpl.createRealRecipesPacket()));
            } catch (RuntimeException | Error throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        ThrowableUtils.throwIfUnchecked(failure);
    }

    @Override
    @NotNull
    protected Set<Class<?>> discardedOutgoingPacketTypes() {
        return DISCARDED_OUTGOING;
    }

    @Override
    protected void handleAcceptedInteraction() {
        this.dataDirty = true;
        this.forceRemoteSlot(RESULT_SLOT);
    }

    @Override
    protected void appendMenuDataPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.recipeOptionsQueued = forceFull || this.recipeOptionsDirty;
        this.dataQueued = forceFull || this.dataDirty || this.recipeOptionsQueued;
        if (this.recipeOptionsQueued) {
            outgoing.add(StonecutterMenuHandleImpl.createRecipeOptionsPacket(this.recipeOptions));
            outgoing.add(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                        this.containerId, AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                        INPUT_SLOT, ItemStackProxy.EMPTY
            ));
            outgoing.add(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                    this.containerId, AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                    INPUT_SLOT, this.clientInput
            ));
            outgoing.add(ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                    this.containerId, AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                    RESULT_SLOT, this.clientResult
            ));
        }
        if (this.dataQueued) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(),
                    SELECTED_DATA_SLOT,
                    this.selectedRecipeIndex
            ));
        }
    }

    @Override
    protected void commitMenuDataPackets() {
        if (this.recipeOptionsQueued) {
            this.recipeOptionsDirty = false;
            this.recipeOptionsQueued = false;
        }
        if (this.dataQueued) {
            this.dataDirty = false;
            this.dataQueued = false;
        }
    }

    @Override
    protected Object toClientItem(int rawSlot, ItemStack item) {
        Object clientItem;
        if (rawSlot == INPUT_SLOT && item.isEmpty()) {
            clientItem = PLACEHOLDER;
        } else {
            clientItem = super.toClientItem(rawSlot, item);
        }
        if (rawSlot == INPUT_SLOT) {
            this.clientInput = ItemStackProxy.INSTANCE.copy(clientItem);
        } else if (rawSlot == RESULT_SLOT) {
            this.clientResult = ItemStackProxy.INSTANCE.copy(clientItem);
        }
        return clientItem;
    }

    private void checkSelectedRecipeIndex(int index) {
        if (index < -1 || index >= this.recipeOptions.size()) {
            throw new IndexOutOfBoundsException("stonecutter selected recipe index out of bounds: " + index);
        }
    }

    private static Object createRecipeOptionsPacket(List<StonecutterRecipeOption> options) {
        Object recipeManager = StonecutterMenuHandleImpl.recipeManager();
        return ClientboundUpdateRecipesPacketProxy.INSTANCE.newInstance(
                RecipeManagerProxy.INSTANCE.getSynchronizedItemProperties(recipeManager),
                StonecutterMenuHandleImpl.createRecipeEntries(options)
        );
    }

    private static Object createRealRecipesPacket() {
        Object recipeManager = StonecutterMenuHandleImpl.recipeManager();
        return ClientboundUpdateRecipesPacketProxy.INSTANCE.newInstance(
                RecipeManagerProxy.INSTANCE.getSynchronizedItemProperties(recipeManager),
                RecipeManagerProxy.INSTANCE.getSynchronizedStonecutterRecipes(recipeManager)
        );
    }

    private static Object recipeManager() {
        Object server = MinecraftServerProxy.INSTANCE.getServer();
        return MinecraftServerProxy.INSTANCE.getRecipeManager(server);
    }

    private static Object createRecipeEntries(List<StonecutterRecipeOption> options) {
        ArrayList<Object> entries = new ArrayList<>(options.size());
        for (int index = 0; index < options.size(); index++) {
            ItemStack displayItem = options.get(index).display();
            Object stack = ItemStackProxy.INSTANCE.copy(ItemUtils.getItemStackNMSHandle(displayItem));
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

    private static Object createAllItemsIngredient() {
        return IngredientProxy.INSTANCE.of(
                RegistryProxy.INSTANCE.stream(BuiltInRegistriesProxy.ITEM).filter(item -> item != ItemsProxy.AIR)
        );
    }

    private static Object createPlaceholder() {
        Object placeholder = ItemStackProxy.INSTANCE.newInstance(ItemsProxy.BARRIER);
        ItemStackProxy.INSTANCE.set(
                placeholder,
                DataComponentsProxy.CUSTOM_NAME,
                ComponentProxy.INSTANCE.empty()
        );
        ItemStackProxy.INSTANCE.set(
                placeholder,
                DataComponentsProxy.TOOLTIP_DISPLAY,
                TooltipDisplayProxy.INSTANCE.newInstance(true, new LinkedHashSet<>())
        );
        ItemStackProxy.INSTANCE.set(
                placeholder,
                DataComponentsProxy.ITEM_MODEL,
                IdentifierProxy.INSTANCE.withDefaultNamespace("air")
        );
        return placeholder;
    }
}
