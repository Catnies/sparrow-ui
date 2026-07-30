package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.inventory.ItemStack;

@ReflectionProxy(name = "net.minecraft.world.item.ItemStack")
public interface ItemStackProxy {
    ItemStackProxy INSTANCE = ASMProxyFactory.create(ItemStackProxy.class);
    Object EMPTY = INSTANCE.getEMPTY();
    Codec<Object> CODEC = INSTANCE.getCODEC();

    /**
     * 从原版 ItemLike 创建一份新的 NMS 物品堆.
     *
     * @param item NMS {@code ItemLike}
     * @return 新建的 NMS {@code ItemStack}
     */
    @ConstructorInvoker
    Object newInstance(@Type(name = "net.minecraft.world.level.ItemLike") Object item);

    @FieldGetter(name = "EMPTY", isStatic = true)
    Object getEMPTY();

    @FieldGetter(name = "CODEC", isStatic = true)
    Codec<Object> getCODEC();

    @MethodInvoker(name = "getBukkitStack", activeIf = "has_patch=paper")
    ItemStack getBukkitStack(Object target);

    @MethodInvoker(name = "copy")
    Object copy(Object target);

    @MethodInvoker(name = "getComponents")
    Object getComponents(Object target);

    @MethodInvoker(name = "getItem")
    Object getItem(Object target);

    @MethodInvoker(name = "getItemHolder", activeIf = "!min_version=26.1")
    Object getItemHolder(Object target);

    @MethodInvoker(name = "typeHolder", activeIf = "min_version=26.1")
    Object typeHolder(Object target);

    @MethodInvoker(name = "transmuteCopy")
    Object transmuteCopy(Object target, @Type(name = "net.minecraft.world.level.ItemLike") Object item);

    /**
     * 直接写入 NMS Data Component.
     *
     * @param target NMS {@code ItemStack}
     * @param component NMS {@code DataComponentType<?>}
     * @param value 对应组件值
     * @return 被替换的旧组件值, 没有旧值时为 {@code null}
     */
    @MethodInvoker(name = "set")
    Object set(Object target, @Type(name = "net.minecraft.core.component.DataComponentType") Object component, Object value);
}
