package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

// 1.21.4 的主仓首格存鞍, Bukkit 在它后面插入护甲槽.
final class HorseContainerStorage implements ExternalStorage {
    private final FixedContainerStorage main;
    private final FixedContainerStorage armor;
    private final UUID mount;

    HorseContainerStorage(@NotNull Object main, @NotNull Object armor, @NotNull UUID mount) {
        this.main = new FixedContainerStorage(main);
        this.armor = new FixedContainerStorage(armor);
        this.mount = mount;
    }

    @Override
    public int size() {
        return this.main.size() + this.armor.size();
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        return slot == 1 ? this.armor.read(0) : this.main.read(slot == 0 ? 0 : slot - 1);
    }

    @Override
    public boolean contentEquals(int slot, @Nullable ItemStack expected) {
        return slot == 1 ? this.armor.contentEquals(0, expected) : this.main.contentEquals(slot == 0 ? 0 : slot - 1, expected);
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        if (slot == 1) {
            this.armor.write(0, item);
        } else {
            this.main.write(slot == 0 ? 0 : slot - 1, item);
        }
    }

    @Override
    public int maxStackSize(int slot) {
        return slot == 1 ? this.armor.maxStackSize(0) : this.main.maxStackSize(slot == 0 ? 0 : slot - 1);
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.mount, slot);
    }

    @Override
    public boolean alive() {
        return this.main.alive() && this.armor.alive();
    }
}
