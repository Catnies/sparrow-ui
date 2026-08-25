package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.login;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.login.LoginProtocols", activeIf = "min_version=1.21.8")
public interface LoginProtocolsProxy {
    LoginProtocolsProxy INSTANCE = ASMProxyFactory.create(LoginProtocolsProxy.class);

    @FieldGetter(name = "SERVERBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object serverboundTemplate();

    @FieldGetter(name = "CLIENTBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object clientboundTemplate();
}
