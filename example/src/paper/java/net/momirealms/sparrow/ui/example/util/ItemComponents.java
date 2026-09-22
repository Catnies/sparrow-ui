package net.momirealms.sparrow.ui.example.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@ApiStatus.Internal
public final class ItemComponents {
    private ItemComponents() {
    }

    @NotNull
    public static ItemStack create(@NotNull Material material) {
        return new ItemStack(material);
    }

    public static void name(@NotNull ItemStack item, @NotNull Component name) {
        item.setData(DataComponentTypes.CUSTOM_NAME, name);
    }

    public static void lore(@NotNull ItemStack item, @NotNull List<Component> lore) {
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }

    public static void glint(@NotNull ItemStack item, boolean glint) {
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, glint);
    }

    public static boolean isBodyArmor(@NotNull ItemStack item) {
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.BODY;
    }
}
