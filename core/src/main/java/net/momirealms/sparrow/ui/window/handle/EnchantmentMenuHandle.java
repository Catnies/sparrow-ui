package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.EnchantmentWindow;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public interface EnchantmentMenuHandle extends MenuHandle {

    /**
     * 设置一个客户端附魔选项, {@code null} 表示禁用.
     *
     * @param index 选项索引
     * @param option 选项或 {@code null}
     */
    void setOption(int index, @Nullable EnchantmentWindow.EnchantOption option);

    /**
     * 设置客户端符文文字使用的随机种子.
     *
     * @param seed 附魔种子
     */
    void setEnchantmentSeed(int seed);
}
