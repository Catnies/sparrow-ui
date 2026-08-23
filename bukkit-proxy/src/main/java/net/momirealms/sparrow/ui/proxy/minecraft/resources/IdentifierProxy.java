package net.momirealms.sparrow.ui.proxy.minecraft.resources;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = {
        "net.minecraft.resources.Identifier",
        "net.minecraft.resources.ResourceLocation"
})
public interface IdentifierProxy {
    IdentifierProxy INSTANCE = ASMProxyFactory.create(IdentifierProxy.class);

    /**
     * 使用 {@code minecraft} 默认命名空间创建资源标识符.
     *
     * @param path 资源路径
     * @return NMS 资源标识符
     */
    @MethodInvoker(name = "withDefaultNamespace", isStatic = true, activeIf = "min_version=1.21")
    Object withDefaultNamespace(String path);

    @MethodInvoker(name = "fromNamespaceAndPath", isStatic = true, activeIf = "min_version=1.21")
    Object fromNamespaceAndPath(String namespace, String path);

    @MethodInvoker(name = "getNamespace", activeIf = "min_version=1.20.1")
    String getNamespace(Object target);

    @MethodInvoker(name = "getPath", activeIf = "min_version=1.20.1")
    String getPath(Object target);
}
