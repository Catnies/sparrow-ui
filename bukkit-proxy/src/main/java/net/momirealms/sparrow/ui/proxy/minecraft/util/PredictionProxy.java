package net.momirealms.sparrow.ui.proxy.minecraft.util;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.util.Prediction")
public interface PredictionProxy {
    PredictionProxy INSTANCE = ASMProxyFactory.create(PredictionProxy.class);

    @FieldGetter(name = "PREDICTED", isStatic = true, activeIf = "min_version=26.3")
    Object getPredicted();
}
