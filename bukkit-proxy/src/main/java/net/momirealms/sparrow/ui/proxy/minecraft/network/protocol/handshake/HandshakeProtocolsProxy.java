package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.handshake.HandshakeProtocols", activeIf = "min_version=1.21.8")
public interface HandshakeProtocolsProxy {
    HandshakeProtocolsProxy INSTANCE = ASMProxyFactory.create(HandshakeProtocolsProxy.class);

    @FieldGetter(name = "SERVERBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object serverboundTemplate();
}
