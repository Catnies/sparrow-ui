package net.minecraft.world.entity;

import net.minecraft.world.item.ItemStack;
import java.util.EnumMap;

public final class EntityEquipment {
    private final EnumMap<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);

    public ItemStack get(EquipmentSlot slot) {
        return this.items.getOrDefault(slot, ItemStack.EMPTY);
    }

    public ItemStack set(EquipmentSlot slot, ItemStack item) {
        return this.items.put(slot, item);
    }
}
