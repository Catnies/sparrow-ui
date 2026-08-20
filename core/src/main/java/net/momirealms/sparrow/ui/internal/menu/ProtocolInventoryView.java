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
 * 为 Bukkit 事件提供专用的 InventoryView, 但不把事件对该 InventoryView 的写入直接应用到玩家物品栏.
 * <p>顶部 Inventory 和底部槽位数组共同组成 Bukkit 事件状态副本. 事件可以观察与协议包一致的内容,
 * 但任何写入都只修改这份副本; 最终服务端渲染结果仍由 Window 决定.</p>
 */
@SuppressWarnings("UnstableApiUsage")
final class ProtocolInventoryView implements InventoryView {
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private final Player player;
    private final Inventory upper;
    private final int lowerStart;
    private final int size;
    private final InventoryType inventoryType;
    private final MenuType menuType;
    private final ItemStack[] lowerItems;
    private final BitSet touchedSlots = new BitSet();      // 本 tick 写过的槽位, 留给最终同步纠正客户端
    private final BitSet eventTouchedSlots = new BitSet(); // 最近一次事件写过的槽位, 由 resetForEvent 清空
    private Component title = Component.empty();
    private ItemStack cursor = ItemUtils.EMPTY;
    private boolean cursorTouched;      // 本 tick 是否写过光标, 留给最终同步纠正客户端
    private boolean eventCursorTouched; // 最近一次事件是否写过光标, 由 resetForEvent 清空

    /**
     * 创建一个初始为空的 Bukkit 事件用的 InventoryView.
     *
     * @param player InventoryView 所属玩家
     * @param upperSize 顶部槽位数量
     * @param inventoryType Bukkit Inventory 类型
     * @param menuType Bukkit 菜单类型
     */
    ProtocolInventoryView(Player player, int upperSize, InventoryType inventoryType, MenuType menuType) {
        this(player, upperSize, upperSize, inventoryType, menuType);
    }

    /**
     * 创建下部玩家物品栏位于指定协议槽位(raw slot)的 Bukkit 事件用的 InventoryView.
     *
     * <p>顶部 Inventory 中位于 {@code lowerStart} 之前的槽位先出现, 随后是固定的 36 个玩家槽位,
     * 顶部 Inventory 的剩余槽位位于协议末尾. 普通菜单的 {@code upperSize == lowerStart},
     * Crafter 则用末尾的顶部槽位表示结果槽.</p>
     *
     * @param player InventoryView 所属玩家
     * @param upperSize Bukkit 顶部 Inventory 槽位数量
     * @param lowerStart 玩家物品栏的起始协议槽位(raw slot)
     * @param inventoryType Bukkit Inventory 类型
     * @param menuType Bukkit 菜单类型
     */
    ProtocolInventoryView(Player player, int upperSize, int lowerStart, InventoryType inventoryType, MenuType menuType) {
        this(player, CraftInventoryProxy.INSTANCE.newInstance(SimpleContainerProxy.INSTANCE.newInstance(upperSize)), lowerStart, inventoryType, menuType);
    }

    /**
     * 创建使用给定 Bukkit 顶部 Inventory 保存事件状态的 InventoryView.
     *
     * @param player InventoryView 所属玩家
     * @param upper 保存 Bukkit 事件状态的顶部 Inventory
     * @param lowerStart 玩家物品栏的起始协议槽位(raw slot)
     * @param inventoryType Bukkit Inventory 类型
     * @param menuType Bukkit 菜单类型
     */
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

    /**
     * 用完整服务端渲染结果替换 Bukkit 事件状态副本中的槽位, 光标和标题.
     *
     * @param slots 完整的服务端槽位渲染结果
     * @param cursor 菜单实际光标
     * @param title 当前标题
     */
    void initialize(ItemStack @NotNull [] slots, @NotNull ItemStack cursor, @NotNull Component title) {
        this.title = title;
        this.replaceContents(slots, cursor);
        this.touchedSlots.clear();
        this.eventTouchedSlots.clear();
        this.cursorTouched = false;
        this.eventCursorTouched = false;
    }

    /**
     * 用服务端渲染结果和菜单实际光标重置 Bukkit 事件状态副本, 但保留此前事件的触碰记录供最终同步纠正客户端.
     * 顶部 Inventory 本身是事件副本的 backing, 监听器可以直接写入并绕过触碰记录, 所以始终完整恢复.
     * 底部暴露的是真实玩家 Inventory, 不与 lowerItems 事件副本共用 backing; lowerItems 只恢复刚渲染或此前触碰过的槽位.
     * <p>只有事件粒度的写入记录会被清掉: 它描述的是"上一个事件写了什么", 内容一旦被服务端渲染结果覆盖就失效了.
     *
     * @param slots 按协议槽位排列的当前服务端渲染结果
     * @param renderedSlots 本次事件前刚重新渲染的槽位
     * @param cursor 当前菜单实际光标
     */
    void resetForEvent(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor) {
        for (int rawSlot = 0; rawSlot < slots.length; rawSlot++) {
            int upperSlot = this.upperSlot(rawSlot);
            if (upperSlot >= 0) {
                this.upper.setItem(upperSlot, slots[rawSlot]);
            } else if (renderedSlots.get(rawSlot) || this.touchedSlots.get(rawSlot)) {
                this.lowerItems[this.lowerSlot(rawSlot)] = ItemUtils.copyOrEmpty(slots[rawSlot]);
            }
        }
        this.cursor = ItemUtils.copyOrEmpty(cursor);
        this.eventTouchedSlots.clear();
        this.eventCursorTouched = false;
    }

    // 用服务端渲染结果和菜单实际光标覆盖上下两半的槽位与光标; 协议槽位要先换算成上下容器各自的下标.
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

    /**
     * 把发生变化的服务端渲染结果写回 Bukkit 事件状态副本.
     *
     * @param slots 当前服务端槽位渲染结果
     * @param changedSlots 已发送变化或被 Bukkit 事件触碰的槽位
     * @param cursor 当前菜单实际光标
     * @param cursorChanged 是否需要恢复菜单实际光标
     */
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

    /**
     * 返回 Bukkit 事件用 InventoryView 当前展示的 Adventure 标题.
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
     * 把最近一次 Bukkit 事件写过的槽位转移到调用方复用的位图, 并清空该事件的写入记录.
     * <p>与 {@link #drainTouchedSlots} 的累积位图相互独立: 那份覆盖整个 tick, 只用来纠正客户端;
     * 这份只覆盖最近一次事件, 供调用方把写入合并进当前交互的草稿.
     *
     * @param destination 接收本次事件写入槽位的可变位图
     */
    void drainEventTouchedSlots(@NotNull BitSet destination) {
        destination.clear();
        destination.or(this.eventTouchedSlots);
        this.eventTouchedSlots.clear();
    }

    /**
     * 取出并清空 Bukkit 事件是否写入过光标的标记.
     *
     * @return 需要恢复菜单实际光标时返回 {@code true}
     */
    boolean takeCursorTouched() {
        boolean touched = this.cursorTouched;
        this.cursorTouched = false;
        return touched;
    }

    /**
     * 取出最近一次 Bukkit 事件写入的光标, 并清空该事件的写入记录.
     * <p>与 {@link #takeCursorTouched()} 的累积标记相互独立: 那份标记覆盖整个 tick, 只用来纠正客户端;
     * 这份只覆盖最近一次事件, 供调用方把写入合并进当前交互的草稿.
     *
     * @return 最近一次事件写过光标时返回写入值, 没写过时返回 {@code null}
     */
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

    /**
     * 只更新 Bukkit 事件状态副本; 底部 Inventory 的写入绝不落到玩家 Bukkit Inventory.
     */
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

    /**
     * 只更新 Bukkit 事件状态副本中的光标.
     */
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
