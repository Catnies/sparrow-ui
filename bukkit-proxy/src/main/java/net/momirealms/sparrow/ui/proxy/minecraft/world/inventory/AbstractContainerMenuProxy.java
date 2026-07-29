package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.inventory.AbstractContainerMenu")
public interface AbstractContainerMenuProxy {
    AbstractContainerMenuProxy INSTANCE = ASMProxyFactory.create(AbstractContainerMenuProxy.class);

    @FieldGetter(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "getStateId")
    int getStateId(Object target);

    @MethodInvoker(name = "incrementStateId")
    int incrementStateId(Object target);

    @MethodInvoker(name = "sendAllDataToRemote")
    void sendAllDataToRemote(Object target);

    @MethodInvoker(name = "getCarried")
    Object getCarried(Object target);

    @MethodInvoker(name = "setCarried")
    void setCarried(Object target, @Type(clazz = ItemStackProxy.class) Object item);
}
