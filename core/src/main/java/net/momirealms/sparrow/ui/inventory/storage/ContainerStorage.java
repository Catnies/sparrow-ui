package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.CompoundContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// 直接读写 NMS 容器的存储, 槽位数量, 堆叠上限与读写全部取自容器自己.
abstract class ContainerStorage implements ExternalStorage {
    private final int size;         // 被引用区段的槽位数量, 构造时取样
    private final int maxStackSize; // 容器的堆叠上限, 构造时缓存

    ContainerStorage(int size, int maxStackSize) {
        this.size = size;
        this.maxStackSize = maxStackSize;
    }

    // 把一个 NMS 容器包成存储.
    @NotNull
    static ExternalStorage of(@NotNull Object container) {
        // 大箱子那种把几个容器接起来的容器在这里就拆开, 每个容器各占一段.
        if (!CompoundContainerProxy.CLASS.isInstance(container)) {
            return new FixedContainerStorage(container);
        }
        List<ExternalStorage> parts = new ArrayList<>(2);
        collectParts(container, parts);
        return new SplicedStorage(parts.toArray(new ExternalStorage[0]));
    }

    // 顺着接起来的结构往下走, 把每个真正存放内容的容器收成独立一段.
    private static void collectParts(Object container, List<ExternalStorage> parts) {
        if (!CompoundContainerProxy.CLASS.isInstance(container)) {
            parts.add(new FixedContainerStorage(container));
            return;
        }
        collectParts(CompoundContainerProxy.INSTANCE.getContainer1(container), parts);
        collectParts(CompoundContainerProxy.INSTANCE.getContainer2(container), parts);
    }

    // 这一刻该读写的 NMS 容器, 每次访问都问一遍, 因为玩家背包那种会被换掉.
    // 容器住在世界里, 世界放手之后本存储也跟着放手, 那之后问出来的是 null, 读到空, 写入丢弃.
    @Nullable
    abstract Object container();

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        Object container = this.container();
        if (container == null) return null;
        Object handle = ContainerProxy.INSTANCE.getItem(container, slot);
        // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
        if (ItemStackProxy.INSTANCE.isEmpty(handle)) return null;
        return CraftItemStackProxy.INSTANCE.asCraftMirror(handle);
    }

    @Override
    @Nullable
    public ItemStack @NotNull [] readAll() {
        Object container = this.container();
        @Nullable ItemStack[] contents = new ItemStack[this.size];
        if (container == null) return contents;
        for (int slot = 0; slot < contents.length; slot++) {
            Object handle = ContainerProxy.INSTANCE.getItem(container, slot);
            // NMS 容器用空物品表示空槽, 这里换成外部存储约定的 null
            contents[slot] = ItemStackProxy.INSTANCE.isEmpty(handle) ? null : CraftItemStackProxy.INSTANCE.asCraftMirror(handle);
        }
        return contents;
    }

    @Override
    public boolean contentEquals(int slot, @Nullable ItemStack expected) {
        Object container = this.container();
        if (container == null) return expected == null;
        return ItemUtils.isHandleContentEqual(ContainerProxy.INSTANCE.getItem(container, slot), expected);
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        Object container = this.container();
        if (container == null) return;
        // 传入实例的所有权归存储, 取出它的句柄直接放进容器, 不必再复制一份
        Object handle = item == null ? ItemStackProxy.EMPTY : ItemUtils.getItemStackHandle(item);
        ContainerProxy.INSTANCE.setItem(container, slot, handle);
    }

    @Override
    public int maxStackSize(int slot) {
        return this.maxStackSize;
    }
}
