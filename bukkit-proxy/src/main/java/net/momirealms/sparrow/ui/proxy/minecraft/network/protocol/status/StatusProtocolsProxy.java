package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.status;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.status.StatusProtocols", activeIf = "min_version=1.21.8")
public interface StatusProtocolsProxy {
    StatusProtocolsProxy INSTANCE = ASMProxyFactory.create(StatusProtocolsProxy.class);

    @FieldGetter(name = "SERVERBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object serverboundTemplate();

    @FieldGetter(name = "CLIENTBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object clientboundTemplate();
}
