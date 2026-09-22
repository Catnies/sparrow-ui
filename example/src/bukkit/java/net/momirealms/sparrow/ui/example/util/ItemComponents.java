package net.momirealms.sparrow.ui.example.util;

import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.Equippable;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.util.AdventureUtils;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@ApiStatus.Internal
public final class ItemComponents {
    private ItemComponents() {
    }

    @NotNull
    public static ItemStack create(@NotNull Material material) {
        return CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(CraftMagicNumbers.getItem(material)));
    }

    // 名称、Lore 和发光只写入 create 创建的独立 CraftItemStack.
    public static void name(@NotNull ItemStack item, @NotNull Component name) {
        handle(item).set(DataComponents.CUSTOM_NAME, (net.minecraft.network.chat.Component) AdventureUtils.asVanilla(name));
    }

    public static void lore(@NotNull ItemStack item, @NotNull List<Component> lore) {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>(lore.size());
        for (int index = 0; index < lore.size(); index++) {
            lines.add((net.minecraft.network.chat.Component) AdventureUtils.asVanilla(lore.get(index)));
        }
        handle(item).set(DataComponents.LORE, new ItemLore(lines));
    }

    public static void glint(@NotNull ItemStack item, boolean glint) {
        handle(item).set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
    }

    public static boolean isBodyArmor(@NotNull ItemStack item) {
        Equippable equippable = ((net.minecraft.world.item.ItemStack) ItemUtils.getItemStackHandle(item)).get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.BODY;
    }

    @NotNull
    private static net.minecraft.world.item.ItemStack handle(@NotNull ItemStack item) {
        return (net.minecraft.world.item.ItemStack) CraftItemStackProxy.INSTANCE.handle((CraftItemStack) item);
    }
}
