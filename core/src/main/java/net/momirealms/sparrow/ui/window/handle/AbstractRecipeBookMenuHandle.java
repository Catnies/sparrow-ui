package net.momirealms.sparrow.ui.window.handle;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.proxy.minecraft.core.registries.RegistriesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.ResourceKeyProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.RecipeHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.RecipeManagerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.RecipeManagerServerDisplayInfoProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.RecipeDisplayEntryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display.RecipeDisplayIdProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 工作台与炉类菜单共用的原版配方书协议实现.
 */
@SuppressWarnings("UnstableApiUsage")
abstract class AbstractRecipeBookMenuHandle extends ContainerMenuHandle implements RecipeBookMenuHandle {
    private static final Object RECIPE_MANAGER = MinecraftServerProxy.INSTANCE.getRecipeManager(MinecraftServerProxy.INSTANCE.getServer());

    AbstractRecipeBookMenuHandle(
            @NotNull MenuPacketGateway packets,
            @NotNull Player player,
            @NotNull Object menuType,
            @NotNull InventoryType inventoryType,
            @NotNull MenuType bukkitMenuType,
            int upperSize,
            long generation
    ) {
        super(packets, player, menuType, inventoryType, bukkitMenuType, upperSize, generation);
    }

    @Override
    @Nullable
    public final Key recipeKey(int displayId) {
        Object displayInfo = RecipeManagerProxy.INSTANCE.getRecipeFromDisplay(RECIPE_MANAGER, RecipeDisplayIdProxy.INSTANCE.newInstance(displayId));
        if (displayInfo == null) return null;

        Object holder = RecipeManagerServerDisplayInfoProxy.INSTANCE.parent(displayInfo);
        Object recipeKey = RecipeHolderProxy.INSTANCE.id(holder);
        Object identifier = VersionHelper.isOrAbove1_21_11() ? ResourceKeyProxy.INSTANCE.identifier(recipeKey) : ResourceKeyProxy.INSTANCE.location(recipeKey);
        return Key.key(IdentifierProxy.INSTANCE.getNamespace(identifier), IdentifierProxy.INSTANCE.getPath(identifier));
    }

    @Override
    public final boolean sendGhostRecipe(@NotNull Key recipeId) {
        Object identifier = IdentifierProxy.INSTANCE.fromNamespaceAndPath(recipeId.namespace(), recipeId.value());
        Object recipeKey = ResourceKeyProxy.INSTANCE.create(RegistriesProxy.RECIPE, identifier);
        Object[] firstDisplay = new Object[1];
        RecipeManagerProxy.INSTANCE.listDisplaysForRecipe(
                RECIPE_MANAGER, recipeKey,
                entry -> {
                    if (firstDisplay[0] == null) {
                        firstDisplay[0] = RecipeDisplayEntryProxy.INSTANCE.display(entry);
                    }
                }
        );
        if (firstDisplay[0] == null) {
            return false;
        }
        this.sendClientboundPacket(ClientboundPlaceGhostRecipePacketProxy.INSTANCE.newInstance(
                this.containerId(),
                firstDisplay[0]
        ));
        return true;
    }
}
