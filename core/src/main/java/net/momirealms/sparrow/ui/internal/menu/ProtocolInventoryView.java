package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.SimpleContainerProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

/**
 * 为 Bukkit 事件提供当前协议快照, 但不把事件对视图的写入直接应用到玩家物品栏.
 *
 * <p>顶部库存本身就是事件状态的唯一镜像, 底部槽位使用独立快照数组. 事件可以观察与协议包
 * 一致的状态, 但任何写入都只触碰本地投影, Window 仍是唯一的物品变更权威.</p>
 */
@SuppressWarnings("UnstableApiUsage")
final class ProtocolInventoryView implements InventoryView {
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private final Player player;
    private final Inventory upper;
    private final InventoryType inventoryType;
    private final MenuType menuType;
    private final ItemStack[] lowerItems;
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
        Object upperContainer = SimpleContainerProxy.INSTANCE.newInstance(topSlots); // NMS SimpleContainer
        this.upper = CraftInventoryProxy.INSTANCE.newInstance(upperContainer);
        this.inventoryType = inventoryType;
        this.menuType = menuType;
        this.lowerItems = new ItemStack[PLAYER_INVENTORY_SLOTS];
        for (int index = 0; index < this.lowerItems.length; index++) {
            this.lowerItems[index] = ItemStack.empty();
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
        int upperSlots = this.upper.getSize();
        for (int index = 0; index < upperSlots; index++) {
            this.upper.setItem(index, slots[index]);
        }
        for (int index = 0; index < this.lowerItems.length; index++) {
            this.lowerItems[index] = ItemUtils.copyOrEmpty(slots[upperSlots + index]);
        }
        this.cursor = ItemUtils.copyOrEmpty(cursor);
        this.touchedSlots.clear();
        this.cursorTouched = false;
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
        int upperSlots = this.upper.getSize();
        for (
                int slot = changedSlots.nextSetBit(0);
                slot >= 0;
                slot = changedSlots.nextSetBit(slot + 1)
        ) {
            if (slot < upperSlots) {
                this.upper.setItem(slot, slots[slot]);
            } else {
                this.lowerItems[slot - upperSlots] = ItemUtils.copyOrEmpty(slots[slot]);
            }
        }
        if (cursorChanged) {
            this.cursor = ItemUtils.copyOrEmpty(cursor);
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
     * 把 Bukkit 事件写入过的槽位转移到调用方复用的位图, 并清空本地记录.
     *
     * @param destination 接收待恢复槽位的可变位图
     */
    void drainTouchedSlots(@NotNull BitSet destination) {
        destination.clear();
        destination.or(this.touchedSlots);
        this.touchedSlots.clear();
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
        return this.upper;
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
        int topSlots = this.upper.getSize();
        if (rawSlot < 0 || rawSlot >= topSlots + this.lowerItems.length) {
            return;
        }
        if (rawSlot < topSlots) {
            this.upper.setItem(rawSlot, item);
        } else {
            this.lowerItems[rawSlot - topSlots] = ItemUtils.copyOrEmpty(item);
        }
        this.touchedSlots.set(rawSlot);
    }

    @Nullable
    @Override
    public ItemStack getItem(int rawSlot) {
        int topSlots = this.upper.getSize();
        if (rawSlot < 0 || rawSlot >= topSlots + this.lowerItems.length) {
            return null;
        }
        ItemStack item = rawSlot < topSlots
                ? this.upper.getItem(rawSlot)
                : this.lowerItems[rawSlot - topSlots];
        return ItemUtils.copyOrEmpty(item);
    }

    /**
     * 只更新事件可见的光标镜像.
     */
    @Override
    public void setCursor(@Nullable ItemStack item) {
        this.cursor = ItemUtils.copyOrEmpty(item);
        this.cursorTouched = true;
    }

    @Override
    public @NotNull ItemStack getCursor() {
        return ItemUtils.copyOrEmpty(this.cursor);
    }

    @Nullable
    @Override
    public Inventory getInventory(int rawSlot) {
        if (rawSlot < 0 || rawSlot >= this.countSlots()) {
            return null;
        }
        return rawSlot < this.upper.getSize() ? this.upper : this.player.getInventory();
    }

    @Override
    public int convertSlot(int rawSlot) {
        if (rawSlot < this.upper.getSize()) {
            return rawSlot;
        }
        int lowerSlot = rawSlot - this.upper.getSize();
        return lowerSlot >= 27 ? lowerSlot - 27 : lowerSlot + 9;
    }

    @Override
    public @NotNull InventoryType.SlotType getSlotType(int rawSlot) {
        if (rawSlot == InventoryView.OUTSIDE) {
            return InventoryType.SlotType.OUTSIDE;
        }
        int lowerSlot = rawSlot - this.upper.getSize();
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
        return this.upper.getSize() + this.lowerItems.length;
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

}
