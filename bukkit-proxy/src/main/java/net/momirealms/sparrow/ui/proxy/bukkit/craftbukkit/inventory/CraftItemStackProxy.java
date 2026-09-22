package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.inventory.ItemStack;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftItemStack")
public interface CraftItemStackProxy {
    CraftItemStackProxy INSTANCE = ASMProxyFactory.create(CraftItemStackProxy.class);
    Class<?> CLASS = SparrowClass.find("org.bukkit.craftbukkit.inventory.CraftItemStack");

    // Paper 生成原生 unwrap 调用; Spigot 使用此默认实现, 普通 Bukkit 物品才需要转换.
    @MethodInvoker(name = "unwrap", isStatic = true, activeIf = "min_version=1.20.1 && has_patch=paper")
    default Object unwrap(ItemStack item) {
        if (CLASS.isInstance(item)) {
            Object handle = this.handle(item);
            return handle == null ? ItemStackProxy.EMPTY : handle;
        }
        return this.asNMSCopy(item);
    }

    @FieldGetter(name = "handle", activeIf = "min_version=1.20.1 && !has_patch=paper")
    Object handle(ItemStack item);

    @MethodInvoker(name = "asNMSCopy", isStatic = true, activeIf = "min_version=1.20.1 && !has_patch=paper")
    Object asNMSCopy(ItemStack item);

    @MethodInvoker(name = "asCraftMirror", isStatic = true, activeIf = "min_version=1.20.1")
    ItemStack asCraftMirror(@Type(clazz = ItemStackProxy.class) Object item);
}
