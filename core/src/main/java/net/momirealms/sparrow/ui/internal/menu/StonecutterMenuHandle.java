package net.momirealms.sparrow.ui.internal.menu;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public interface StonecutterMenuHandle extends MenuHandle {

    /**
     * 替换客户端原生配方按钮快照.
     *
     * <p>空物品表示有效前缀中的中间空洞, 实现需向客户端显示不可见占位符.
     * <strong>数组和物品引用只在调用期间有效, 实现不得修改或保留.</strong>
     *
     * @param buttons 按客户端索引顺序排列的按钮物品
     */
    void setRecipeButtons(ItemStack @NotNull [] buttons);

    /**
     * 设置当前选中的原生配方索引.
     *
     * @param index 配方索引, {@code -1} 表示未选择
     */
    void setSelectedRecipeIndex(int index);

    /**
     * 收到客户端选择操作后, 以服务端选中值强制复核选择 property 和结果槽.
     *
     * @param index 需要恢复给客户端的服务端配方索引
     */
    void reconcileClientSelection(int index);
}
