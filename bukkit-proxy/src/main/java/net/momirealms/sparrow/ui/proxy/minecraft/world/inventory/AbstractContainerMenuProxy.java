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

    @FieldGetter(name = "containerId", activeIf = "min_version=1.20.1")
    int containerId(Object target);

    @MethodInvoker(name = "getStateId", activeIf = "min_version=1.20.1")
    int getStateId(Object target);

    @MethodInvoker(name = "incrementStateId", activeIf = "min_version=1.20.1")
    int incrementStateId(Object target);

    @MethodInvoker(name = "sendAllDataToRemote", activeIf = "min_version=1.20.1")
    void sendAllDataToRemote(Object target);

    @MethodInvoker(name = "resumeRemoteUpdates", activeIf = "min_version=1.20.1")
    void resumeRemoteUpdates(Object target);

    @MethodInvoker(name = "getCarried", activeIf = "min_version=1.20.1")
    Object getCarried(Object target);

    @MethodInvoker(name = "setCarried", activeIf = "min_version=1.20.1")
    void setCarried(Object target, @Type(clazz = ItemStackProxy.class) Object item);
}
