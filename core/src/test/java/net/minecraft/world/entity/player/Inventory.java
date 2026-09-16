package net.minecraft.world.entity.player;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.bukkit.entity.HumanEntity;
import java.util.Arrays;

public class Inventory implements Container {

    public static final int ITEMS_SIZE = 36;
    public static final int EQUIPMENT_SIZE = 7;
    private final ItemStack[] items = new ItemStack[ITEMS_SIZE];
    private final ItemStack[] equipment = new ItemStack[EQUIPMENT_SIZE];
    private HumanEntity owner;
    public Inventory() {
        Arrays.fill(this.items, ItemStack.EMPTY);
        Arrays.fill(this.equipment, ItemStack.EMPTY);
    }

    public HumanEntity owner() {
        return this.owner;
    }

    public void owner(HumanEntity owner) {
        this.owner = owner;
    }

    public ItemStack equipmentAt(int index) {
        return this.equipment[index];
    }

    @Override
    public int getContainerSize() {
        return ITEMS_SIZE + EQUIPMENT_SIZE;
    }

    @Override
    public int getMaxStackSize() {
        return 99;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < ITEMS_SIZE ? this.items[slot] : this.equipment[slot - ITEMS_SIZE];
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        ItemStack stored = item == null ? ItemStack.EMPTY : item;
        if (slot < ITEMS_SIZE) {
            this.items[slot] = stored;
            return;
        }
        this.equipment[slot - ITEMS_SIZE] = stored;
    }
}
