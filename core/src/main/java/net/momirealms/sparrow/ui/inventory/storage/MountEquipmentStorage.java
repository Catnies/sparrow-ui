package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityEquipmentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EquipmentSlotProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

// Spigot 的坐骑装备独立于储物容器, 这里按鞍和身体装备的顺序暴露两个槽位.
final class MountEquipmentStorage implements ExternalStorage {
    private static final Object SADDLE = EquipmentSlotProxy.INSTANCE.saddle();
    private static final Object BODY = EquipmentSlotProxy.INSTANCE.body();

    private final WeakReference<Object> equipment;
    private final WeakReference<AbstractHorse> owner;
    private final UUID identity;

    MountEquipmentStorage(Object equipment, AbstractHorse owner) {
        this.equipment = new WeakReference<>(equipment);
        this.owner = new WeakReference<>(owner);
        this.identity = owner.getUniqueId();
    }

    @Override
    public int size() {
        return 2;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        Object handle = this.handle(slot);
        return ItemStackProxy.INSTANCE.isEmpty(handle) ? null : CraftItemStackProxy.INSTANCE.asCraftMirror(handle);
    }

    @Override
    public boolean contentEquals(int slot, @Nullable ItemStack expected) {
        return ItemUtils.isHandleContentEqual(this.handle(slot), expected);
    }

    private Object handle(int slot) {
        Object equipment = this.equipment.get();
        return equipment == null ? ItemStackProxy.EMPTY : EntityEquipmentProxy.INSTANCE.get(equipment, equipmentSlot(slot));
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        Object equipment = this.equipment.get();
        if (equipment == null) return;
        EntityEquipmentProxy.INSTANCE.set(equipment, equipmentSlot(slot), item == null ? ItemStackProxy.EMPTY : ItemUtils.getItemStackHandle(item));
    }

    private static Object equipmentSlot(int slot) {
        return slot == 0 ? SADDLE : BODY;
    }

    @Override
    public int maxStackSize(int slot) {
        return 1;
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return new SlotKey(this.identity, slot);
    }

    @Override
    public boolean alive() {
        AbstractHorse owner = this.owner.get();
        return owner != null && owner.isValid() && this.equipment.get() != null;
    }
}
