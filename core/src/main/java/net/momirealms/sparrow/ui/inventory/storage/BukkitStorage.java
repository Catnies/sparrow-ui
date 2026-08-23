package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

@ApiStatus.Internal
public final class BukkitStorage implements ExternalStorage {
    private final Inventory bukkitInventory;
    private final Function<Inventory, @Nullable ItemStack[]> contentsGetter;
    private final int size;
    private final int bukkitMaxStackSize;
    private final Object identity;

    private final @Nullable HumanEntity owner;

    BukkitStorage(@NotNull Inventory bukkitInventory, @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter) {
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.owner = bukkitInventory instanceof PlayerInventory playerInventory ? playerInventory.getHolder() : null;
        this.size = contentsGetter.apply(bukkitInventory).length;
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
        // 玩家重生后背包对象会变化, UUID 让槽位身份保持稳定.
        this.identity = this.owner == null ? bukkitInventory : this.owner.getUniqueId();
    }

    // 未知排布回退到 Bukkit 通道, 它会强持有容器直到 Inventory 退役.
    @NotNull
    public static ExternalStorage of(@NotNull Inventory inventory, @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter) {
        int size = contentsGetter.apply(inventory).length;
        ExternalStorage storage = BukkitInventoryLayout.storageOf(inventory, size);
        return storage != null ? storage : new BukkitStorage(inventory, contentsGetter);
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        return this.bukkitInventory.getItem(slot);
    }

    @Override
    public @Nullable ItemStack @NotNull [] readAll() {
        return this.contentsGetter.apply(this.bukkitInventory);
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        this.bukkitInventory.setItem(slot, item);
    }

    @Override
    public int maxStackSize(int slot) {
        return this.bukkitMaxStackSize;
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.identity, slot);
    }

    @Override
    public boolean alive() {
        return !(this.owner instanceof Player player) || player.isConnected();
    }
}
