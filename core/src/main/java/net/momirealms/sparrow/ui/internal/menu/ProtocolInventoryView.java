package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.util.ItemSnapshots;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.SimpleContainer;

/**
 * 为 Bukkit 事件提供当前协议快照, 但不把事件对视图的写入直接应用到玩家物品栏.
 *
 * <p>顶部库存是本地镜像, 底部库存只读自玩家真实物品栏. 这使事件能观察与协议包一致的状态,
 * 同时 Window 仍是唯一的物品变更权威.</p>
 */
@SuppressWarnings("UnstableApiUsage")
final class ProtocolInventoryView implements InventoryView {
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private final Player player;
    private final Inventory top;
    private final ItemStack[] items;
    private Component title = Component.empty();
    private ItemStack cursor = ItemStack.empty();

    /**
     * 创建一个初始为空的协议视图.
     *
     * @param player 视图所属玩家
     * @param topSlots 顶部箱子槽位数量
     */
    ProtocolInventoryView(Player player, int topSlots) {
        this.player = player;
        this.top = new CraftInventory(new SimpleContainer(topSlots));
        this.items = new ItemStack[topSlots + PLAYER_INVENTORY_SLOTS];
        for (int index = 0; index < this.items.length; index++) {
            this.items[index] = ItemStack.empty();
        }
    }

    /**
     * 用完整同步计划替换事件可见的槽位、光标和标题.
     *
     * @param full 完整权威状态
     * @param title 当前标题
     */
    void apply(@NotNull SyncPlan.Full full, @NotNull Component title) {
        this.title = title;
        for (int index = 0; index < this.items.length; index++) {
            this.items[index] = ItemSnapshots.copyOrEmpty(full.slots().get(index));
        }
        this.cursor = ItemSnapshots.copyOrEmpty(full.carried());
        this.refreshTop();
    }

    /**
     * 将增量或完整计划映射到事件可见的协议镜像.
     *
     * @param plan 已发送给客户端的同步计划
     */
    void apply(@NotNull SyncPlan plan) {
        switch (plan) {
            case SyncPlan.None _ -> {
            }
            case SyncPlan.Delta delta -> {
                for (var entry : delta.slots().entrySet()) {
                    this.items[entry.getKey()] = ItemSnapshots.copyOrEmpty(entry.getValue());
                }
                delta.carried().ifPresent(item -> this.cursor = ItemSnapshots.copyOrEmpty(item));
                this.refreshTop();
            }
            case SyncPlan.Full full -> this.apply(full, this.title);
        }
    }

    @Override
    public @NotNull Inventory getTopInventory() {
        return this.top;
    }

    @Override
    public @NotNull Inventory getBottomInventory() {
        return this.player.getInventory();
    }

    @Override
    public @NotNull HumanEntity getPlayer() {
        return this.player;
    }

    @Override
    public @NotNull InventoryType getType() {
        return InventoryType.CHEST;
    }

    /**
     * 只更新协议镜像; 底部库存的写入绝不落到玩家真实物品栏.
     */
    @Override
    public void setItem(int rawSlot, @Nullable ItemStack item) {
        if (rawSlot >= 0 && rawSlot < this.items.length) {
            this.items[rawSlot] = ItemSnapshots.copyOrEmpty(item);
            if (rawSlot < this.top.getSize()) {
                this.top.setItem(rawSlot, this.items[rawSlot]);
            }
        }
    }

    @Override
    public @Nullable ItemStack getItem(int rawSlot) {
        if (rawSlot < 0 || rawSlot >= this.items.length) {
            return null;
        }
        return ItemSnapshots.copyOrEmpty(this.items[rawSlot]);
    }

    /**
     * 只更新事件可见的光标镜像.
     */
    @Override
    public void setCursor(@Nullable ItemStack item) {
        this.cursor = ItemSnapshots.copyOrEmpty(item);
    }

    @Override
    public @NotNull ItemStack getCursor() {
        return ItemSnapshots.copyOrEmpty(this.cursor);
    }

    @Override
    public @Nullable Inventory getInventory(int rawSlot) {
        if (rawSlot == InventoryView.OUTSIDE || rawSlot < 0 || rawSlot >= this.items.length) {
            return null;
        }
        return rawSlot < this.top.getSize() ? this.top : this.player.getInventory();
    }

    @Override
    public int convertSlot(int rawSlot) {
        if (rawSlot < this.top.getSize()) {
            return rawSlot;
        }
        int lowerSlot = rawSlot - this.top.getSize();
        return lowerSlot >= 27 ? lowerSlot - 27 : lowerSlot + 9;
    }

    @Override
    public @NotNull InventoryType.SlotType getSlotType(int rawSlot) {
        if (rawSlot == InventoryView.OUTSIDE) {
            return InventoryType.SlotType.OUTSIDE;
        }
        int lowerSlot = rawSlot - this.top.getSize();
        return lowerSlot >= 27 ? InventoryType.SlotType.QUICKBAR : InventoryType.SlotType.CONTAINER;
    }

    @Override
    public void open() {
    }

    @Override
    public void close() {
    }

    @Override
    public int countSlots() {
        return this.items.length;
    }

    @SuppressWarnings("removal")
    @Override
    public boolean setProperty(Property property, int value) {
        return false;
    }

    @Override
    public @NotNull String getTitle() {
        return this.title.toString();
    }

    @Override
    public @NotNull String getOriginalTitle() {
        return this.getTitle();
    }

    @Override
    public void setTitle(@NotNull String title) {
    }

    @Override
    public @Nullable MenuType getMenuType() {
        return switch (this.top.getSize()) {
            case 9 -> MenuType.GENERIC_9X1;
            case 18 -> MenuType.GENERIC_9X2;
            case 27 -> MenuType.GENERIC_9X3;
            case 36 -> MenuType.GENERIC_9X4;
            case 45 -> MenuType.GENERIC_9X5;
            case 54 -> MenuType.GENERIC_9X6;
            default -> null;
        };
    }

    private void refreshTop() {
        for (int index = 0; index < this.top.getSize(); index++) {
            this.top.setItem(index, this.items[index]);
        }
    }
}
