package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftContainerAccess;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Bukkit 容器的外部存储适配: 读写直达被引用容器, 自身不持有任何内容状态.
 * <p>身份能力的成色取决于平台: CraftBukkit 的 {@code getItem} 返回包着真实 NMS 句柄的活视图,
 * 且 {@link CraftContainerAccess} 可直达 NMS Container, 两条保身份快路径全开;
 * 其他平台(测试环境等)自动降级 —— {@code liveView} 只能给副本时由回写复核兜住,
 * 句柄注入经 {@link #supportsHandleTransfer()} 关闭, 内容仍然正确.
 * <p>存储槽位就是 Bukkit 容器槽位: {@code getContents} 与 {@code getStorageContents}
 * 都是容器槽位的前缀区段, 区段下标与 {@code getItem}/{@code setItem} 的槽位一致.
 */
final class BukkitStorage implements LiveCapableStorage {
    private final Inventory bukkitInventory; // 被引用的 Bukkit 容器
    private final Function<Inventory, @Nullable ItemStack[]> contentsGetter; // 读取被引用区段(getContents / getStorageContents)
    private final int size;                  // 被引用区段的槽位数量, 构造时取样
    private final int bukkitMaxStackSize;    // 容器的堆叠上限, 构造时缓存
    private final boolean craftBacked;       // 容器背后是否有可直达的 NMS Container

    BukkitStorage(@NotNull Inventory bukkitInventory, @NotNull Function<Inventory, @Nullable ItemStack[]> contentsGetter) {
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.size = contentsGetter.apply(bukkitInventory).length;
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
        this.craftBacked = CraftContainerAccess.isCraftBacked(bukkitInventory);
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

    /**
     * {@inheritDoc}
     *
     * <p>原地改数绕过了容器自己的写入口, 这里补一次 NMS {@code setChanged}, 方块实体照常标脏保存,
     * 与原版 grow/shrink 之后 {@code slot.setChanged()} 的语义一致; 不可直达时无事发生.
     */
    @Override
    public void markChanged() {
        if (this.craftBacked) {
            CraftContainerAccess.markChanged(this.bukkitInventory);
        }
    }

    @Override
    @NotNull
    public Object identity() {
        return this.bukkitInventory;
    }

    @Override
    @Nullable
    public ItemStack liveView(int slot) {
        return this.bukkitInventory.getItem(slot);
    }

    @Override
    public boolean supportsHandleTransfer() {
        return this.craftBacked;
    }

    @Override
    public void adoptHandle(int slot, @NotNull Object itemHandle) {
        CraftContainerAccess.setItem(this.bukkitInventory, slot, itemHandle);
    }
}
