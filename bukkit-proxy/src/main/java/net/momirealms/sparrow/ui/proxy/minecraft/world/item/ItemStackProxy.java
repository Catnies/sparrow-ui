package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.ItemStack")
public interface ItemStackProxy {
    ItemStackProxy INSTANCE = ASMProxyFactory.create(ItemStackProxy.class);
    Object EMPTY = INSTANCE.EMPTY();

    /**
     * 从原版 ItemLike 创建一份新的 NMS 物品堆.
     *
     * @param item NMS {@code ItemLike}
     * @return 新建的 NMS {@code ItemStack}
     */
    @ConstructorInvoker
    Object newInstance(@Type(name = "net.minecraft.world.level.ItemLike") Object item);

    @FieldGetter(name = "EMPTY", isStatic = true)
    Object EMPTY();

    @MethodInvoker(name = "copy")
    Object copy(Object target);

    @MethodInvoker(name = "transmuteCopy")
    Object transmuteCopy(
            Object target,
            @Type(name = "net.minecraft.world.level.ItemLike") Object item
    );

    /**
     * 直接写入 NMS Data Component.
     *
     * @param target NMS {@code ItemStack}
     * @param component NMS {@code DataComponentType<?>}
     * @param value 对应组件值
     * @return 被替换的旧组件值, 没有旧值时为 {@code null}
     */
    @MethodInvoker(name = "set")
    Object set(
            Object target,
            @Type(name = "net.minecraft.core.component.DataComponentType") Object component,
            Object value
    );
}
