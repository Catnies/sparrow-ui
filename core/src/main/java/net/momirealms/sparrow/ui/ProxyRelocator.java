package net.momirealms.sparrow.ui;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ProxyRelocator {
    // 原始包名在运行时还原, 与内层归档中尚未重定位到使用者根包的类保持一致.
    private static final String SOURCE_PACKAGE = "net{}momirealms{}sparrow{}ui".replace("{}", ".");
    private static final String RELOCATOR_CLASS = SOURCE_PACKAGE + ".libraries.jarrelocator.JarRelocator";

    private ProxyRelocator() {
    }

    // 把代理运行时的所有类移动到当前插件副本的根包下。
    @NotNull
    static byte[] relocate(@NotNull byte[] archive, @NotNull String runtimePackage) throws IOException, ReflectiveOperationException {
        if (SOURCE_PACKAGE.equals(runtimePackage)) return archive;

        Path directory = Files.createTempDirectory("sparrow-ui-relocation-");
        Path input = directory.resolve("proxy.jar");
        Path output = directory.resolve("relocated.jar");
        try {
            Files.write(input, archive);
            // 从原始代理归档加载重定位工具, 输出归档随后交给 Minecraft 类加载器.
            try (URLClassLoader loader = new URLClassLoader(new URL[]{input.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> relocatorClass = loader.loadClass(RELOCATOR_CLASS);
                Object relocator = relocatorClass.getConstructor(File.class, File.class, Map.class)
                        .newInstance(input.toFile(), output.toFile(), Map.of(SOURCE_PACKAGE, runtimePackage));
                relocatorClass.getMethod("run").invoke(relocator);
            }
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(input);
            Files.deleteIfExists(directory);
        }
    }
}
