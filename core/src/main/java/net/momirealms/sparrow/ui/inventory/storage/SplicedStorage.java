package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 将多个存储按声明顺序拼成一段连续槽位.
final class SplicedStorage implements ExternalStorage {
    private final ExternalStorage[] parts;
    private final int[] offsets;
    private final int size;

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
        @Nullable ItemStack[] contents = new ItemStack[this.size];
        for (int index = 0; index < this.parts.length; index++) {
            @Nullable ItemStack[] part = this.parts[index].readAll();
            System.arraycopy(part, 0, contents, this.offsets[index], part.length);
        }
        return contents;
    }

    @Override
    public boolean contentEquals(int slot, @Nullable ItemStack expected) {
        int part = this.partOf(slot);
        return this.parts[part].contentEquals(slot - this.offsets[part], expected);
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
        // 物理身份使用最终分段的局部坐标.
        int part = this.partOf(slot);
        return this.parts[part].keyOf(slot - this.offsets[part]);
    }

    @Override
    public boolean alive() {
        for (int index = 0; index < this.parts.length; index++) {
            if (!this.parts[index].alive()) {
                return false;
            }
        }
        return true;
    }

    private int partOf(int slot) {
        for (int index = 1; index < this.parts.length; index++) {
            if (slot < this.offsets[index]) {
                return index - 1;
            }
        }
        return this.parts.length - 1;
    }
}
