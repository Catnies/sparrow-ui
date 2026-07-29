package net.momirealms.sparrow.ui.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.component.TooltipDisplay")
public interface TooltipDisplayProxy {
    TooltipDisplayProxy INSTANCE = ASMProxyFactory.create(TooltipDisplayProxy.class);

    /**
     * 创建 Tooltip 显示策略.
     *
     * @param hideTooltip 是否隐藏完整 Tooltip
     * @param hiddenComponents NMS {@code DataComponentType<?>} 的有序集合
     * @return NMS {@code TooltipDisplay}
     */
    @ConstructorInvoker
    Object newInstance(
            boolean hideTooltip,
            @Type(name = "java.util.SequencedSet") Object hiddenComponents
    );
}
