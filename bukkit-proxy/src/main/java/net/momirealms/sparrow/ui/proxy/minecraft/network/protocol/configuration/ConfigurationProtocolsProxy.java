package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.configuration;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.configuration.ConfigurationProtocols", activeIf = "min_version=1.21.8")
public interface ConfigurationProtocolsProxy {
    ConfigurationProtocolsProxy INSTANCE = ASMProxyFactory.create(ConfigurationProtocolsProxy.class);

    @FieldGetter(name = "SERVERBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object serverboundTemplate();

    @FieldGetter(name = "CLIENTBOUND_TEMPLATE", isStatic = true, activeIf = "min_version=1.21.8")
    Object clientboundTemplate();
}
