package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.core.registries.RegistriesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.ResourceKeyProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.RecipeHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.RecipeManagerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.RecipeManagerServerDisplayInfoProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.RecipeDisplayEntryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.RecipeDisplayIdProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

@SuppressWarnings("UnstableApiUsage")
final class CraftingMenuHandleImpl extends PaperMenuHandle implements CraftingMenuHandle {
    private static final int RESULT_SLOT = 0;
    private static final int CRAFTING_SLOT_START = 1;
    private static final int CRAFTING_SLOT_END = 10;
    private static Object recipeManager;

    CraftingMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.CRAFTING,
                InventoryType.WORKBENCH,
                org.bukkit.inventory.MenuType.CRAFTING,
                10,
                generation
        );
        // 初始化 RecipeManager
        if (recipeManager == null) {
            Object server = MinecraftServerProxy.INSTANCE.getServer();
            recipeManager = MinecraftServerProxy.INSTANCE.getRecipeManager(server);
        }
    }

    @Override
    @Nullable
    public Key recipeKey(int displayId) {
        Object displayInfo = RecipeManagerProxy.INSTANCE.getRecipeFromDisplay(recipeManager, RecipeDisplayIdProxy.INSTANCE.newInstance(displayId));
        if (displayInfo == null) return null;

        Object holder = RecipeManagerServerDisplayInfoProxy.INSTANCE.parent(displayInfo);
        Object recipeKey = RecipeHolderProxy.INSTANCE.id(holder);
        Object identifier = VersionHelper.isOrAbove26_1() ? ResourceKeyProxy.INSTANCE.identifier(recipeKey) : ResourceKeyProxy.INSTANCE.location(recipeKey);
        return Key.key(IdentifierProxy.INSTANCE.getNamespace(identifier), IdentifierProxy.INSTANCE.getPath(identifier));
    }

    @Override
    public boolean sendGhostRecipe(@NotNull Key recipeId) {
        Object identifier = IdentifierProxy.INSTANCE.fromNamespaceAndPath(recipeId.namespace(), recipeId.value());
        Object recipeKey = ResourceKeyProxy.INSTANCE.create(RegistriesProxy.RECIPE, identifier);
        Object[] firstDisplay = new Object[1];
        RecipeManagerProxy.INSTANCE.listDisplaysForRecipe(
                recipeManager,
                recipeKey,
                entry -> {
                    if (firstDisplay[0] == null) {
                        firstDisplay[0] = RecipeDisplayEntryProxy.INSTANCE.display(entry);
                    }
                }
        );
        if (firstDisplay[0] == null) {
            return false;
        }
        this.sendClientboundPacket(ClientboundPlaceGhostRecipePacketProxy.INSTANCE.newInstance(this.containerId(), firstDisplay[0]));
        return true;
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (CraftingMenuHandleImpl.hasDirtyCraftingSlot(dirtySlots)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }

    static boolean hasDirtyCraftingSlot(@NotNull BitSet dirtySlots) {
        int firstDirty = dirtySlots.nextSetBit(CRAFTING_SLOT_START);
        return firstDirty >= CRAFTING_SLOT_START && firstDirty < CRAFTING_SLOT_END;
    }
}
