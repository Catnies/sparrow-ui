package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * 为单个渲染上下文生成归调用方所有的 {@link ItemStack} 快照.
 *
 * <p>返回的物品堆仅归调用方所有. 实现不得返回可变模板, 也不得返回与其他渲染操作共享的物品堆.</p>
 */
@FunctionalInterface
public interface ItemProvider {
    ItemProvider EMPTY = ignoredContext -> ItemUtils.copyOrEmpty(null);

    /**
     * 基于模板的防御性副本创建固定提供器.
     *
     * @param template 模板物品堆
     * @return 提供器
     */
    static ItemProvider constant(ItemStack template) {
        return new ItemWrapper(template);
    }

    /**
     * 包装依赖上下文的渲染器, 并对每个结果创建快照.
     *
     * @param renderer 渲染器, 可返回 {@code null}
     * @return 持有其返回快照的提供器
     */
    static ItemProvider contextual(@NotNull Function<? super RenderContext, ? extends ItemStack> renderer) {
        return context -> ItemUtils.copyOrEmpty(renderer.apply(context));
    }

    /**
     * 为本次渲染生成可独立修改的快照.
     *
     * @param context 当前渲染上下文
     * @return 归调用方所有的物品堆
     */
    ItemStack provide(RenderContext context);
}
