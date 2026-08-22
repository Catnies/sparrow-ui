package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 把若干存储首尾接成一个用, 槽位落在哪一段就交给那一段自己处理. 段的边界由每一段自己的 size() 算出来.
final class SplicedStorage implements ExternalStorage {
    private final ExternalStorage[] parts; // 各段存储, 按接起来的顺序排列
    private final int[] offsets;           // 每段的起始槽位, 与 parts 一一对应
    private final int size;                // 各段加起来的槽位数量

    SplicedStorage(@NotNull ExternalStorage @NotNull ... parts) {
        this.parts = parts;
        this.offsets = new int[parts.length];
        int offset = 0;
        for (int index = 0; index < parts.length; index++) {
            this.offsets[index] = offset;
            offset += parts[index].size();
        }
        this.size = offset;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @Nullable
    public ItemStack read(int slot) {
        int part = this.partOf(slot);
        return this.parts[part].read(slot - this.offsets[part]);
    }

    @Override
    public @Nullable ItemStack @NotNull [] readAll() {
        // 每一段各读各的, 再按接起来的顺序摆好
        @Nullable ItemStack[] contents = new ItemStack[this.size];
        for (int index = 0; index < this.parts.length; index++) {
            @Nullable ItemStack[] part = this.parts[index].readAll();
            System.arraycopy(part, 0, contents, this.offsets[index], part.length);
        }
        return contents;
    }

    @Override
    public void write(int slot, @Nullable ItemStack item) {
        int part = this.partOf(slot);
        this.parts[part].write(slot - this.offsets[part], item);
    }

    @Override
    public int maxStackSize(int slot) {
        int part = this.partOf(slot);
        return this.parts[part].maxStackSize(slot - this.offsets[part]);
    }

    @Override
    @NotNull
    public SlotKey keyOf(int slot) {
        // 归属交给真正存放这一格的那一段, 槽号也换算成它自己的
        int part = this.partOf(slot);
        return this.parts[part].keyOf(slot - this.offsets[part]);
    }

    @Override
    public boolean alive() {
        // 任何一段没了都算没了
        for (int index = 0; index < this.parts.length; index++) {
            if (!this.parts[index].alive()) {
                return false;
            }
        }
        return true;
    }

    // 这一格落在第几段.
    private int partOf(int slot) {
        for (int index = 1; index < this.parts.length; index++) {
            if (slot < this.offsets[index]) {
                return index - 1;
            }
        }
        return this.parts.length - 1;
    }
}
