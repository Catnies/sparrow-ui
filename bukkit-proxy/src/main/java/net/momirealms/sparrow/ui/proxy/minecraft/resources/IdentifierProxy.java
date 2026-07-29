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
    @MethodInvoker(name = "withDefaultNamespace", isStatic = true)
    Object withDefaultNamespace(String path);

    @MethodInvoker(name = "fromNamespaceAndPath", isStatic = true)
    Object fromNamespaceAndPath(String namespace, String path);

    @MethodInvoker(name = "getNamespace")
    String getNamespace(Object target);

    @MethodInvoker(name = "getPath")
    String getPath(Object target);
}
