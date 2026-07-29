package net.momirealms.sparrow.ui.proxy.minecraft.util.datafix;

import com.mojang.datafixers.DataFixer;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.util.datafix.DataFixers")
public interface DataFixersProxy {
    DataFixersProxy INSTANCE = ASMProxyFactory.create(DataFixersProxy.class);

    @MethodInvoker(name = "getDataFixer", isStatic = true)
    DataFixer getDataFixer();
}
