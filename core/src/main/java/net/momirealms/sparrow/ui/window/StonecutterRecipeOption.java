package net.momirealms.sparrow.ui.window;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 客户端切石机配方列表中的一个显示快照.
 *
 * <p>此物品只用于原生配方列表的图标, 不表示真实配方、输入消耗或结果生成规则.
 * 构造与读取都会复制物品, 因而外部修改不会改变已经提交给 Window 的选项.</p>
 *
 * @param display 非空的配方图标
 */
public record StonecutterRecipeOption(@NotNull ItemStack display) {

    public StonecutterRecipeOption {
        Objects.requireNonNull(display, "display");
        if (display.isEmpty()) {
            throw new IllegalArgumentException("stonecutter recipe display must not be empty");
        }
        display = display.clone();
    }

    @Override
    @NotNull
    public ItemStack display() {
        return this.display.clone();
    }
}
