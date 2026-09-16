package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.entity.player.Inventory;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import java.util.Arrays;

public class CraftInventoryPlayer extends CraftInventory implements PlayerInventory {

    public CraftInventoryPlayer(Inventory inventory) {
        super(inventory);
    }

    @Override
    public Inventory getInventory() {
        return (Inventory) super.getInventory();
    }

    @Override
    public ItemStack[] getStorageContents() {
        return Arrays.copyOf(this.getContents(), Inventory.ITEMS_SIZE);
    }

    @Override
    public HumanEntity getHolder() {
        return this.getInventory().owner();
    }

    @Override
    public ItemStack[] getArmorContents() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack[] getExtraContents() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getHelmet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getChestplate() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getLeggings() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getBoots() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItem(EquipmentSlot slot, ItemStack item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getItem(EquipmentSlot slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setArmorContents(ItemStack[] items) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setExtraContents(ItemStack[] items) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHelmet(ItemStack helmet) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setChestplate(ItemStack chestplate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setLeggings(ItemStack leggings) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBoots(ItemStack boots) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getItemInMainHand() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItemInMainHand(ItemStack item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getItemInOffHand() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItemInOffHand(ItemStack item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack getItemInHand() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItemInHand(ItemStack item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getHeldItemSlot() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHeldItemSlot(int slot) {
        throw new UnsupportedOperationException();
    }
}
