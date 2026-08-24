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

import java.util.Arrays;
import java.util.BitSet;

/**
 * Bukkit 事件读取和临时写入的独立 InventoryView.
 * <p>顶部 Inventory 与底部槽位数组保存协议状态副本, 事件写入不会直接修改玩家物品栏.
 */
@SuppressWarnings("UnstableApiUsage")
final class ProtocolInventoryView implements InventoryView {
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    // 事件视图布局
    private final Player player;
    private final Inventory upper;
    private final int lowerStart;
    private final int size;
    private final InventoryType inventoryType;
    private final MenuType menuType;

    // 事件读取的内容副本
    private final ItemStack[] lowerItems;
    private Component title = Component.empty();
    private ItemStack cursor = ItemUtils.EMPTY;

    // 本 tick 累积写入
    private final BitSet touchedSlots = new BitSet();
    private boolean cursorTouched;

    // 单次事件写入
    private final BitSet eventTouchedSlots = new BitSet();
    private boolean eventCursorTouched;

    ProtocolInventoryView(Player player, int upperSize, InventoryType inventoryType, MenuType menuType) {
        this(player, upperSize, upperSize, inventoryType, menuType);
    }

    // Crafter 的结果槽位于玩家 36 格之后, lowerStart 可以小于 upperSize.
    ProtocolInventoryView(Player player, int upperSize, int lowerStart, InventoryType inventoryType, MenuType menuType) {
        this(player, CraftInventoryProxy.INSTANCE.newInstance(SimpleContainerProxy.INSTANCE.newInstance(upperSize)), lowerStart, inventoryType, menuType);
    }

    ProtocolInventoryView(Player player, Inventory upper, int lowerStart, InventoryType inventoryType, MenuType menuType) {
        int upperSize = upper.getSize();
        if (lowerStart < 0 || lowerStart > upperSize) {
            throw new IllegalArgumentException("lower start must be between 0 and " + upperSize + ": " + lowerStart);
        }
        this.player = player;
        this.upper = upper;
        this.lowerStart = lowerStart;
        this.size = upperSize + PLAYER_INVENTORY_SLOTS;
        this.inventoryType = inventoryType;
        this.menuType = menuType;
        this.lowerItems = new ItemStack[PLAYER_INVENTORY_SLOTS];
        Arrays.fill(this.lowerItems, ItemUtils.EMPTY);
    }

    void initialize(ItemStack @NotNull [] slots, @NotNull ItemStack cursor, @NotNull Component title) {
        this.title = title;
        this.replaceContents(slots, cursor);
        this.touchedSlots.clear();
        this.eventTouchedSlots.clear();
        this.cursorTouched = false;
        this.eventCursorTouched = false;
    }

    // 重置下一次事件读取的副本, 本 tick 累积触碰记录继续留给最终同步.
    void resetForEvent(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor) {
        for (int rawSlot = 0; rawSlot < slots.length; rawSlot++) {
            int upperSlot = this.upperSlot(rawSlot);
            if (upperSlot >= 0) {
                // 监听器能直接改 upper backing, 每次事件前都完整恢复.
                this.upper.setItem(upperSlot, slots[rawSlot]);
            } else if (renderedSlots.get(rawSlot) || this.touchedSlots.get(rawSlot)) {
                // lowerItems 不与真实玩家 Inventory 共用 backing, 按变化范围恢复即可.
                this.lowerItems[this.lowerSlot(rawSlot)] = ItemUtils.copyOrEmpty(slots[rawSlot]);
            }
        }
        this.cursor = ItemUtils.copyOrEmpty(cursor);
        this.eventTouchedSlots.clear();
        this.eventCursorTouched = false;
    }

    // 将协议槽位换算为上下容器下标, 再覆盖完整事件副本.
    private void replaceContents(ItemStack[] slots, ItemStack cursor) {
        for (int rawSlot = 0; rawSlot < slots.length; rawSlot++) {
            int upperSlot = this.upperSlot(rawSlot);
            if (upperSlot >= 0) {
                this.upper.setItem(upperSlot, slots[rawSlot]);
            } else {
                this.lowerItems[this.lowerSlot(rawSlot)] = ItemUtils.copyOrEmpty(slots[rawSlot]);
            }
        }
        this.cursor = ItemUtils.copyOrEmpty(cursor);
    }

    // 最终同步后把实际发送或被事件触碰的位置对齐到服务端状态.
    void apply(
            ItemStack @NotNull [] slots,
            @NotNull BitSet changedSlots,
            @NotNull ItemStack cursor,
            boolean cursorChanged
    ) {
        for (
                int slot = changedSlots.nextSetBit(0);
                slot >= 0 && slot < slots.length;
                slot = changedSlots.nextSetBit(slot + 1)
        ) {
            int upperSlot = this.upperSlot(slot);
            if (upperSlot >= 0) {
                this.upper.setItem(upperSlot, slots[slot]);
            } else {
                this.lowerItems[this.lowerSlot(slot)] = ItemUtils.copyOrEmpty(slots[slot]);
            }
        }
        if (cursorChanged) {
            this.cursor = ItemUtils.copyOrEmpty(cursor);
        }
    }

    @Override
    public @NotNull Component title() {
        return this.title;
    }

    // 转移本 tick 累积写入, 供最终客户端纠正.
    void drainTouchedSlots(@NotNull BitSet destination) {
        destination.clear();
        destination.or(this.touchedSlots);
        this.touchedSlots.clear();
    }

    // 转移单次事件写入, 供当前交互草稿吸收.
    void drainEventTouchedSlots(@NotNull BitSet destination) {
        destination.clear();
        destination.or(this.eventTouchedSlots);
        this.eventTouchedSlots.clear();
    }

    // 取出本 tick 的累积光标写入标记.
    boolean takeCursorTouched() {
        boolean touched = this.cursorTouched;
        this.cursorTouched = false;
        return touched;
    }

    // 取出单次事件写入的光标副本, 供当前交互草稿吸收.
    @Nullable
    ItemStack takeEventCursor() {
        if (!this.eventCursorTouched) {
            return null;
        }
        this.eventCursorTouched = false;
        return ItemUtils.copyOrEmpty(this.cursor);
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

    // 写入停留在事件副本, 底部槽位不会落到真实玩家 Inventory.
    @Override
    public void setItem(int rawSlot, @Nullable ItemStack item) {
        int upperSlot = this.upperSlot(rawSlot);
        int lowerSlot = this.lowerSlot(rawSlot);
        if (upperSlot < 0 && lowerSlot < 0) {
            return;
        }
        if (upperSlot >= 0) {
            this.upper.setItem(upperSlot, item);
        } else {
            this.lowerItems[lowerSlot] = ItemUtils.copyOrEmpty(item);
        }
        this.touchedSlots.set(rawSlot);
        this.eventTouchedSlots.set(rawSlot);
    }

    @Nullable
    @Override
    public ItemStack getItem(int rawSlot) {
        int upperSlot = this.upperSlot(rawSlot);
        int lowerSlot = this.lowerSlot(rawSlot);
        if (upperSlot < 0 && lowerSlot < 0) {
            return null;
        }
        ItemStack item = upperSlot >= 0
                ? this.upper.getItem(upperSlot)
                : this.lowerItems[lowerSlot];
        return ItemUtils.copyOrEmpty(item);
    }

    // 光标写入同时记入本 tick 和本次事件两级触碰状态.
    @Override
    public void setCursor(@Nullable ItemStack item) {
        this.cursor = ItemUtils.copyOrEmpty(item);
        this.cursorTouched = true;
        this.eventCursorTouched = true;
    }

    @Override
    public @NotNull ItemStack getCursor() {
        return ItemUtils.copyOrEmpty(this.cursor);
    }

    @Nullable
    @Override
    public Inventory getInventory(int rawSlot) {
        int upperSlot = this.upperSlot(rawSlot);
        int lowerSlot = this.lowerSlot(rawSlot);
        if (upperSlot < 0 && lowerSlot < 0) {
            return null;
        }
        return upperSlot >= 0 ? this.upper : this.player.getInventory();
    }

    @Override
    public int convertSlot(int rawSlot) {
        int upperSlot = this.upperSlot(rawSlot);
        if (upperSlot >= 0) {
            return upperSlot;
        }
        int lowerSlot = this.lowerSlot(rawSlot);
        if (lowerSlot < 0) {
            return rawSlot;
        }
        return lowerSlot >= 27 ? lowerSlot - 27 : lowerSlot + 9;
    }

    @Override
    public @NotNull InventoryType.SlotType getSlotType(int rawSlot) {
        if (rawSlot == InventoryView.OUTSIDE) {
            return InventoryType.SlotType.OUTSIDE;
        }
        return this.lowerSlot(rawSlot) >= 27 ? InventoryType.SlotType.QUICKBAR : InventoryType.SlotType.CONTAINER;
    }

    @Override
    public void open() {
    }

    @Override
    public void close() {
    }

    @Override
    public int countSlots() {
        return this.size;
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

    private int upperSlot(int rawSlot) {
        if (!this.contains(rawSlot)) {
            return -1;
        }
        if (rawSlot < this.lowerStart) {
            return rawSlot;
        }
        return rawSlot >= this.lowerStart + PLAYER_INVENTORY_SLOTS
                ? rawSlot - PLAYER_INVENTORY_SLOTS
                : -1;
    }

    private int lowerSlot(int rawSlot) {
        return rawSlot >= this.lowerStart && rawSlot < this.lowerStart + PLAYER_INVENTORY_SLOTS
                ? rawSlot - this.lowerStart
                : -1;
    }

    private boolean contains(int rawSlot) {
        return rawSlot >= 0 && rawSlot < this.size;
    }
}
