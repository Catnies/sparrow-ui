package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.CompoundContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 直接读写 NMS 容器的存储, 槽位数量, 堆叠上限与读写全部取自容器自己.
abstract class ContainerStorage implements ExternalStorage {
    private final int size;         // 被引用区段的槽位数量, 构造时取样
    private final int maxStackSize; // 容器的堆叠上限, 构造时缓存

    ContainerStorage(int size, int maxStackSize) {
        this.size = size;
        this.maxStackSize = maxStackSize;
    }

    // 这一刻该读写的 NMS 容器, 每次访问都问一遍, 因为玩家背包那种会被换掉.
    @NotNull
    abstract Object container();

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        Object handle = ContainerProxy.INSTANCE.getItem(this.container(), slot);
        // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
        if (handle == ItemStackProxy.EMPTY) return null;
        return ItemUtils.nullIfEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(handle));
    }

    @Override
    @Nullable
    public ItemStack @NotNull [] readAll() {
        Object container = this.container();
        @Nullable ItemStack[] contents = new ItemStack[this.size];
        for (int slot = 0; slot < contents.length; slot++) {
            Object handle = ContainerProxy.INSTANCE.getItem(container, slot);
            // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
            contents[slot] = handle != ItemStackProxy.EMPTY
                    ? ItemUtils.nullIfEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(handle))
                    : null;
        }
        return contents;
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        // 传入实例的所有权归存储, 取出它的句柄直接放进容器, 不必再复制一份
        Object handle = item == null ? ItemStackProxy.EMPTY : ItemUtils.getItemStackHandle(item);
        ContainerProxy.INSTANCE.setItem(this.container(), slot, handle);
    }

    @Override
    public int maxStackSize(int slot) {
        return this.maxStackSize;
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        return keyOf(this.container(), slot);
    }

    // 找到真正存放这一格的那个容器, 顺手把槽号换算到它自己的坐标里.
    @NotNull
    private static SlotKey keyOf(Object container, int slot) {
        // CompoundContainer (比如大箱子) 是两个 Container 接起来的, 归属需要具体到被包装的 Container
        if (CompoundContainerProxy.CLASS.isInstance(container)) {
            Object first = CompoundContainerProxy.INSTANCE.getContainer1(container);
            int firstSize = ContainerProxy.INSTANCE.getContainerSize(first);
            if (slot < firstSize) {
                return keyOf(first, slot);
            }
            return keyOf(CompoundContainerProxy.INSTANCE.getContainer2(container), slot - firstSize);
        }
        // 其余的正常引用 container 本身
        return new SlotKey(container, slot);
    }
}
