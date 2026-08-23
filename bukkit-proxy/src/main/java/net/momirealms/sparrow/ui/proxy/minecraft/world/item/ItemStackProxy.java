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
    @ConstructorInvoker(activeIf = "min_version=1.20.1")
    Object newInstance(@Type(name = "net.minecraft.world.level.ItemLike") Object item);

    @FieldGetter(name = "EMPTY", isStatic = true, activeIf = "min_version=1.20.1")
    Object getEMPTY();

    @FieldGetter(name = "CODEC", isStatic = true, activeIf = "min_version=1.20.5")
    Codec<Object> getCODEC();

    @MethodInvoker(name = "getBukkitStack", activeIf = "min_version=1.20.1 && has_patch=paper")
    ItemStack getBukkitStack(Object target);

    @MethodInvoker(name = "copy", activeIf = "min_version=1.20.1")
    Object copy(Object target);

    @MethodInvoker(name = "getComponents", activeIf = "min_version=1.20.5")
    Object getComponents(Object target);

    @MethodInvoker(name = "getItem", activeIf = "min_version=1.20.1")
    Object getItem(Object target);

    @MethodInvoker(name = "getItemHolder", activeIf = "min_version=1.20.1 && max_version=1.21.11")
    Object getItemHolder(Object target);

    @MethodInvoker(name = "typeHolder", activeIf = "min_version=26.1")
    Object typeHolder(Object target);

    @MethodInvoker(name = "is", activeIf = "min_version=1.20.1 && max_version=1.21.11")
    boolean is(Object target, @Type(name = "net.minecraft.tags.TagKey") Object tag);

    @MethodInvoker(name = "isEmpty", activeIf = "min_version=1.20.1")
    boolean isEmpty(Object target);

    @MethodInvoker(name = "matches", isStatic = true, activeIf = "min_version=1.20.1")
    boolean matches(@Type(name = "net.minecraft.world.item.ItemStack") Object a, @Type(name = "net.minecraft.world.item.ItemStack") Object b);

    @MethodInvoker(name = "isSameItemSameComponents", isStatic = true, activeIf = "min_version=1.20.5")
    boolean isSameItemSameComponents(@Type(name = "net.minecraft.world.item.ItemStack") Object a, @Type(name = "net.minecraft.world.item.ItemStack") Object b);

    @MethodInvoker(name = "transmuteCopy", activeIf = "min_version=1.21")
    Object transmuteCopy(Object target, @Type(name = "net.minecraft.world.level.ItemLike") Object item);

    /**
     * 直接写入 NMS Data Component.
     *
     * @param target NMS {@code ItemStack}
     * @param component NMS {@code DataComponentType<?>}
     * @param value 对应组件值
     * @return 被替换的旧组件值, 没有旧值时为 {@code null}
     */
    @MethodInvoker(name = "set", activeIf = "min_version=1.20.5")
    Object set(Object target, @Type(name = "net.minecraft.core.component.DataComponentType") Object component, Object value);
}
