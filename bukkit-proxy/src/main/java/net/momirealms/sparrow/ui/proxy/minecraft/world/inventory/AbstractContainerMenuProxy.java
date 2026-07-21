package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 容器 state id 与完整远端同步操作的访问代理.
 */
@ReflectionProxy(name = "net.minecraft.world.inventory.AbstractContainerMenu")
public interface AbstractContainerMenuProxy {
    AbstractContainerMenuProxy INSTANCE = ASMProxyFactory.create(AbstractContainerMenuProxy.class);

    @MethodInvoker(name = "getStateId")
    int stateId(Object target);

    @MethodInvoker(name = "incrementStateId")
    int incrementStateId(Object target);

    @MethodInvoker(name = "sendAllDataToRemote")
    void sendAllDataToRemote(Object target);
}
