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

import java.util.BitSet;

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
    private final InventoryType inventoryType;
    private final MenuType menuType;
    private final ItemStack[] items;
    private final BitSet touchedSlots = new BitSet();
    private Component title = Component.empty();
    private ItemStack cursor = ItemStack.empty();
    private boolean cursorTouched;

    /**
     * 创建一个初始为空的协议视图.
     *
     * @param player 视图所属玩家
     * @param topSlots 顶部槽位数量
     * @param inventoryType Bukkit 库存类型
     * @param menuType Bukkit 菜单类型
     */
    ProtocolInventoryView(Player player, int topSlots, InventoryType inventoryType, MenuType menuType) {
        this.player = player;
        this.top = new CraftInventory(new SimpleContainer(topSlots));
        this.inventoryType = inventoryType;
        this.menuType = menuType;
        this.items = new ItemStack[topSlots + PLAYER_INVENTORY_SLOTS];
        for (int index = 0; index < this.items.length; index++) {
            this.items[index] = ItemStack.empty();
        }
    }

    /**
     * 用完整权威状态替换事件可见的槽位、光标和标题.
     *
     * @param slots 完整权威槽位
     * @param cursor 权威光标
     * @param title 当前标题
     */
    void initialize(ItemStack @NotNull [] slots, @NotNull ItemStack cursor, @NotNull Component title) {
        this.title = title;
        for (int index = 0; index < this.items.length; index++) {
            this.items[index] = ItemSnapshots.copyOrEmpty(slots[index]);
        }
        this.cursor = ItemSnapshots.copyOrEmpty(cursor);
        this.touchedSlots.clear();
        this.cursorTouched = false;
        this.refreshTop();
    }

    /**
     * 将权威增量重新投影到事件可见的协议镜像.
     *
     * @param slots 当前权威槽位数组
     * @param changedSlots 已发送变化或被 Bukkit 事件触碰的槽位
     * @param cursor 当前权威光标
     * @param cursorChanged 是否需要恢复权威光标投影
     */
    void apply(
            ItemStack @NotNull [] slots,
            @NotNull BitSet changedSlots,
            @NotNull ItemStack cursor,
            boolean cursorChanged
    ) {
        for (
                int slot = changedSlots.nextSetBit(0);
                slot >= 0;
                slot = changedSlots.nextSetBit(slot + 1)
        ) {
            this.items[slot] = ItemSnapshots.copyOrEmpty(slots[slot]);
            if (slot < this.top.getSize()) {
                this.top.setItem(slot, this.items[slot]);
            }
        }
        if (cursorChanged) {
            this.cursor = ItemSnapshots.copyOrEmpty(cursor);
        }
    }

    /**
     * 返回事件视图当前展示的 Adventure 标题.
     *
     * @return 当前标题
     */
    @Override
    public @NotNull Component title() {
        return this.title;
    }

    /**
     * 取出并清空 Bukkit 事件写入过的槽位.
     *
     * @return 需要恢复权威投影的槽位
     */
    @NotNull BitSet takeTouchedSlots() {
        BitSet touched = (BitSet) this.touchedSlots.clone();
        this.touchedSlots.clear();
        return touched;
    }

    /**
     * 取出并清空 Bukkit 事件是否写入过光标的标记.
     *
     * @return 需要恢复权威光标投影时返回 {@code true}
     */
    boolean takeCursorTouched() {
        boolean touched = this.cursorTouched;
        this.cursorTouched = false;
        return touched;
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
        return this.inventoryType;
    }

    /**
     * 只更新协议镜像; 底部库存的写入绝不落到玩家真实物品栏.
     */
    @Override
    public void setItem(int rawSlot, @Nullable ItemStack item) {
        if (rawSlot >= 0 && rawSlot < this.items.length) {
            this.items[rawSlot] = ItemSnapshots.copyOrEmpty(item);
            this.touchedSlots.set(rawSlot);
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
        this.cursorTouched = true;
    }

    @Override
    public @NotNull ItemStack getCursor() {
        return ItemSnapshots.copyOrEmpty(this.cursor);
    }

    @Override
    public @Nullable Inventory getInventory(int rawSlot) {
        if (rawSlot < 0 || rawSlot >= this.items.length) {
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

    @Nullable
    @Override
    public MenuType getMenuType() {
        return this.menuType;
    }

    private void refreshTop() {
        for (int index = 0; index < this.top.getSize(); index++) {
            this.top.setItem(index, this.items[index]);
        }
    }
}
