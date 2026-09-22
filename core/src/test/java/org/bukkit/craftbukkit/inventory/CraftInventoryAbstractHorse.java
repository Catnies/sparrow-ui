package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.inventory.AbstractHorseInventory;
import org.bukkit.inventory.ItemStack;

public class CraftInventoryAbstractHorse extends CraftInventory implements AbstractHorseInventory {
    private final EntityEquipment equipment;

    public CraftInventoryAbstractHorse(Container inventory, EntityEquipment equipment) {
        super(inventory);
        this.equipment = equipment;
    }

    @Override
    public ItemStack getSaddle() {
        return asBukkit(this.equipment.get(EquipmentSlot.SADDLE));
    }

    @Override
    public void setSaddle(ItemStack stack) {
        this.equipment.set(EquipmentSlot.SADDLE, asNms(stack));
    }
}
