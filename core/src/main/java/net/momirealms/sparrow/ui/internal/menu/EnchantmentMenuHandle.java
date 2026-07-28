package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.window.EnchantmentWindow;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 附魔台菜单的三个选项和符文种子能力.
 */
@ApiStatus.Internal
public interface EnchantmentMenuHandle extends MenuHandle {

    /**
     * 设置一个客户端附魔选项, null 表示禁用.
     *
     * @param index 选项索引
     * @param option 选项或 null
     */
    void setOption(int index, @Nullable EnchantmentWindow.EnchantOption option);

    /**
     * 设置客户端符文文字使用的随机种子.
     *
     * @param seed 附魔种子
     */
    void setEnchantmentSeed(int seed);
}
