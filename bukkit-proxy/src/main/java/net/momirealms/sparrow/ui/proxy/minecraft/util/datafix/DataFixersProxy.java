package net.momirealms.sparrow.ui.proxy.minecraft.util.datafix;

import com.mojang.datafixers.DataFixer;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.util.datafix.DataFixers")
public interface DataFixersProxy {
    DataFixersProxy INSTANCE = ASMProxyFactory.create(DataFixersProxy.class);

    @MethodInvoker(name = "getDataFixer", isStatic = true)
    DataFixer getDataFixer();
}
