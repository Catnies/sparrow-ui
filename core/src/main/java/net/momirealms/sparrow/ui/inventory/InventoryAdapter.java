package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

/**
 * 将 UI 操作适配 Bukkit Inventory 接口.
 * <p>写路径一律转为事务操作 (原因为 {@link UpdateReason.Program}) 线程契约
 * 随被包装库存: 快照型库存可在任意线程写, 引用型库存当前不可访问时 void 写静默
 * no-op、add/remove 返回 leftovers；读路径走快照克隆.
 * 与真实容器无关的能力(观看者, 持有者, 位置, 类型)按"无"回答.
 */
final class InventoryAdapter implements org.bukkit.inventory.Inventory {
    private final Inventory inventory;

    InventoryAdapter(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public int getSize() {
        return this.inventory.size();
    }

    @Override
    public int getMaxStackSize() {
        int max = 0;
        for (int slot = 0; slot < this.inventory.size(); slot++) {
            max = Math.max(max, this.inventory.slotMaxStackSize(slot));
        }
        // 零尺寸库存回退默认值, 避免向 Bukkit 调用方报告 0 上限
        return max > 0 ? max : Inventory.DEFAULT_MAX_STACK_SIZE;
    }

    @Override
    public void setMaxStackSize(int size) {
        if (this.inventory instanceof VirtualInventory virtualInventory) {
            int[] maxes = new int[virtualInventory.size()];
            Arrays.fill(maxes, size);
            virtualInventory.slotMaxStackSizes(maxes);
        }
    }

    @Override
    @Nullable
    public ItemStack getItem(int index) {
        return this.inventory.itemAt(index);
    }

    @Override
    public void setItem(int index, @Nullable ItemStack item) {
        this.inventory.setItem(UpdateReason.Program.INSTANCE, index, item);
    }

    @Override
    @NotNull
    public HashMap<Integer, ItemStack> addItem(@Nullable ItemStack @NotNull ... items) {
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null) {
                throw new IllegalArgumentException("Item at index " + i + " is null");
            }
            AddResult result = this.inventory.add(UpdateReason.Program.INSTANCE, item);
            if (result.remaining() > 0) {
                leftovers.put(i, ItemUtils.copyWithAmount(item, result.remaining()));
            }
        }
        return leftovers;
    }

    @Override
    @NotNull
    public HashMap<Integer, ItemStack> removeItem(@Nullable ItemStack @NotNull ... items) {
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null) {
                throw new IllegalArgumentException("Item at index " + i + " is null");
            }
            RemoveResult result = this.inventory.remove(UpdateReason.Program.INSTANCE, stack -> stack.isSimilar(item), item.getAmount());
            int leftOver = item.getAmount() - result.removed();
            if (leftOver > 0) {
                leftovers.put(i, ItemUtils.copyWithAmount(item, leftOver));
            }
        }
        return leftovers;
    }

    @Override
    @NotNull
    public HashMap<Integer, ItemStack> removeItemAnySlot(@Nullable ItemStack @NotNull ... items) {
        return this.removeItem(items);
    }

    @Override
    public @Nullable ItemStack @NotNull [] getContents() {
        return this.inventory.snapshot();
    }

    @Override
    public void setContents(@Nullable ItemStack @NotNull [] items) {
        if (items.length > this.getSize()) {
            throw new IllegalArgumentException("Array size (" + items.length + ") exceeds inventory size (" + this.getSize() + ")");
        }
        @Nullable ItemStack[] current = this.inventory.snapshot();
        for (int slot = 0; slot < this.getSize(); slot++) {
            @Nullable ItemStack target = slot < items.length ? items[slot] : null;
            // 空槽写空是无变更操作, 跳过以免派发幻影权威写事件
            if (target == null && current[slot] == null) {
                continue;
            }
            this.inventory.setItem(UpdateReason.Program.INSTANCE, slot, target);
        }
    }

    @Override
    public @Nullable ItemStack @NotNull [] getStorageContents() {
        return this.getContents();
    }

    @Override
    public void setStorageContents(@Nullable ItemStack @NotNull [] items) {
        this.setContents(items);
    }

    @Override
    public boolean contains(@NotNull Material material) {
        return this.first(material) != -1;
    }

    @Override
    public boolean contains(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(@NotNull Material material, int amount) {
        int total = 0;
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && stack.getType() == material && (total += stack.getAmount()) >= amount) {
                return true;
            }
        }
        return amount <= 0;
    }

    @Override
    public boolean contains(@Nullable ItemStack item, int amount) {
        if (item == null) {
            return false;
        }
        int found = 0;
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot]) && ++found >= amount) {
                return true;
            }
        }
        return amount <= 0;
    }

    @Override
    public boolean containsAtLeast(@Nullable ItemStack item, int amount) {
        if (item == null) {
            return false;
        }
        int total = 0;
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && stack.isSimilar(item) && (total += stack.getAmount()) >= amount) {
                return true;
            }
        }
        return amount <= 0;
    }

    @Override
    @NotNull
    public HashMap<Integer, ? extends ItemStack> all(@NotNull Material material) {
        HashMap<Integer, ItemStack> found = new HashMap<>();
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && stack.getType() == material) {
                found.put(slot, stack);
            }
        }
        return found;
    }

    @Override
    @NotNull
    public HashMap<Integer, ? extends ItemStack> all(@Nullable ItemStack item) {
        HashMap<Integer, ItemStack> found = new HashMap<>();
        if (item == null) {
            return found;
        }
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot])) {
                found.put(slot, snapshot[slot]);
            }
        }
        return found;
    }

    @Override
    public int first(@NotNull Material material) {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (snapshot[slot] != null && snapshot[slot].getType() == material) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public int first(@NotNull ItemStack item) {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot])) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public int firstEmpty() {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (snapshot[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public boolean isEmpty() {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (snapshot[slot] != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void remove(@NotNull Material material) {
        this.inventory.remove(UpdateReason.Program.INSTANCE, stack -> stack.getType() == material, Integer.MAX_VALUE);
    }

    @Override
    public void remove(@NotNull ItemStack item) {
        this.inventory.remove(UpdateReason.Program.INSTANCE, item::equals, Integer.MAX_VALUE);
    }

    @Override
    public void clear(int index) {
        this.setItem(index, null);
    }

    @Override
    public void clear() {
        // 只清非空槽, 避免为已空槽派发幻影权威写事件
        @Nullable ItemStack[] current = this.inventory.snapshot();
        for (int slot = 0; slot < current.length; slot++) {
            if (current[slot] != null) {
                this.setItem(slot, null);
            }
        }
    }

    @Override
    public int close() {
        return 0;
    }

    @Override
    @NotNull
    public List<HumanEntity> getViewers() {
        return List.of();
    }

    @Override
    @NotNull
    public InventoryType getType() {
        return InventoryType.CHEST;
    }

    @Override
    @Nullable
    public InventoryHolder getHolder() {
        return null;
    }

    @Override
    @Nullable
    public InventoryHolder getHolder(boolean useSnapshot) {
        return null;
    }

    @Override
    @NotNull
    public ListIterator<ItemStack> iterator() {
        return this.iterator(0);
    }

    @Override
    @NotNull
    public ListIterator<ItemStack> iterator(int index) {
        // CraftBukkit 兼容: 负下标从尾部回绕
        if (index < 0) {
            index += this.getSize();
        }
        // 快照的只读迭代: set 等修改操作抛出异常, 而不是静默写进脱离库存的副本
        return Collections.unmodifiableList(Arrays.asList(this.inventory.snapshot())).listIterator(index);
    }

    @Override
    @Nullable
    public Location getLocation() {
        return null;
    }
}
