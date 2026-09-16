package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 请求访问一个槽位时的候选内容与实际流入流出, 收纳袋操作使用袋内物品计算流动.
 * <p><strong>所有物品均为内部只读引用, 不得修改或持有</strong>.
 */
public final class AccessContext {
    private final SparrowInventory inventory;
    private final UpdateReason reason;
    @Nullable private final Window window;
    private final SlotChange change;
    @Nullable private final ItemStack addedItem;
    @Nullable private final ItemStack removedItem;

    @ApiStatus.Internal
    public AccessContext(@NotNull SparrowInventory inventory, @NotNull UpdateReason reason, @Nullable Window window, @NotNull SlotChange change, @Nullable ItemStack addedItem, @Nullable ItemStack removedItem) {
        this.inventory = inventory;
        this.reason = reason;
        this.window = window;
        this.change = change;
        this.addedItem = addedItem;
        this.removedItem = removedItem;
    }

    @NotNull
    public SparrowInventory inventory() {
        return this.inventory;
    }

    public int slot() {
        return this.change.slot();
    }

    @NotNull
    public UpdateReason reason() {
        return this.reason;
    }

    /**
     * 从玩家请求原因中取得发起者.
     *
     * @return 发起玩家, 非玩家来源为 null
     */
    @Nullable
    public Player player() {
        return this.reason instanceof PlayerUpdateReason playerReason ? playerReason.player() : null;
    }

    @Nullable
    public Window window() {
        return this.window;
    }

    /**
     * 返回规则所审核的变更前内容, 可包含 Bukkit 事件的现场覆盖.
     *
     * @return 只读物品, 空槽为 null
     */
    @Nullable
    public ItemStack before() {
        return this.change.unsafeBefore();
    }

    /**
     * 返回当前候选的变更后内容.
     *
     * @return 只读物品, 清空为 null
     */
    @Nullable
    public ItemStack after() {
        return this.change.unsafeAfter();
    }

    /**
     * 返回实际流入的物品及数量, 收纳袋插入时为袋内新增物.
     *
     * @return 只读物品, 没有流入为 null
     */
    @Nullable
    public ItemStack addedItem() {
        return this.addedItem;
    }

    /**
     * 返回实际流出的物品及数量, 收纳袋取出时为取出的袋内物.
     *
     * @return 只读物品, 没有流出为 null
     */
    @Nullable
    public ItemStack removedItem() {
        return this.removedItem;
    }

    /**
     * 返回本次实际流入数量.
     *
     * @return 没有流入时为 0
     */
    public int addedAmount() {
        return ItemUtils.amountOf(this.addedItem);
    }

    /**
     * 返回本次实际流出数量.
     *
     * @return 没有流出时为 0
     */
    public int removedAmount() {
        return ItemUtils.amountOf(this.removedItem);
    }

    /**
     * 判断本次访问是否有物品流入, 交换时可同时有流出.
     *
     * @return 是否有流入
     */
    public boolean isAdd() {
        return this.addedItem != null;
    }

    /**
     * 判断本次访问是否有物品流出, 交换时可同时有流入.
     *
     * @return 是否有流出
     */
    public boolean isRemove() {
        return this.removedItem != null;
    }
}
