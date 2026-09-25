package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import sun.misc.Unsafe;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@ApiStatus.Internal
public final class BukkitProxyInstaller {
    private static final String PROXY_ARCHIVE = "sparrow-ui-proxy.jarinjar";

    private BukkitProxyInstaller() {
    }

    // 安装并初始化当前库的反射代理运行时.
    public static void setUp() {
        setUpForJarInJar();
    }

    private static void setUpForJarInJar() {
        try {
            String runtimePackage = BukkitProxyInstaller.class.getPackageName();
            ClassLoader minecraftClassLoader = Bukkit.class.getClassLoader();
            byte[] archive = ProxyRelocator.relocate(BukkitProxyInstaller.readProxyArchive(), runtimePackage);
            appendToMinecraftClassPath(minecraftClassLoader, archive, runtimePackage);
            // 版本条件和映射必须在 Proxy 接口的静态 INSTANCE 初始化前配置完成.
            Class<?> bootstrapClass = Class.forName(runtimePackage + ".proxy.BukkitProxy", true, minecraftClassLoader);
            bootstrapClass.getMethod("init", String.class, List.class).invoke(null, VersionHelper.MINECRAFT_VERSION, VersionHelper.getPatches());
            if (VersionHelper.IS_RUNNING_IN_DEV) {
                Bukkit.getLogger().info("[SparrowUI] Initializing ASM proxies...");
                BukkitProxyInstaller.initASMProxies(archive, runtimePackage, minecraftClassLoader);
            }
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to initialize the SparrowUI reflection proxy", e);
        }
    }

    private static void initASMProxies(byte[] archive, String runtimePackage, ClassLoader minecraftClassLoader) throws IOException {
        String proxyPath = runtimePackage.replace('.', '/') + "/proxy/";
        ArrayList<Throwable> failures = new ArrayList<>();
        int initialized = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!entryName.startsWith(proxyPath) || !entryName.endsWith("Proxy.class")) continue;
                String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');
                try {
                    Class.forName(className, true, minecraftClassLoader);
                    initialized++;
                } catch (Throwable failure) {
                    failures.add(new IllegalStateException("Failed to initialize " + className, failure));
                }
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException exception = new IllegalStateException("Failed to initialize " + failures.size() + " SparrowUI ASM proxies");
            failures.forEach(exception::addSuppressed);
            throw exception;
        }
        Bukkit.getLogger().info("[SparrowUI] Initialized " + initialized + " ASM proxies.");
    }

    // 读取内嵌的代理 Jar 字节.
    private static byte[] readProxyArchive() {
        ClassLoader libraryClassLoader = BukkitProxyInstaller.class.getClassLoader();
        try (InputStream input = libraryClassLoader.getResourceAsStream(BukkitProxyInstaller.PROXY_ARCHIVE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing embedded resource " + BukkitProxyInstaller.PROXY_ARCHIVE
                );
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read embedded resource " + BukkitProxyInstaller.PROXY_ARCHIVE,
                    exception
            );
        }
    }

    // 把代理 Jar 追加到 Minecraft 类路径. URLClassLoader 使用内存 URL, 其他加载器使用转换路径接口.
    private static void appendToMinecraftClassPath(ClassLoader minecraftClassLoader, byte[] archive, String runtimePackage) {
        try {
            if (minecraftClassLoader instanceof URLClassLoader urlClassLoader) {
                // 每个根包拥有独立 URL, URLClassLoader 按 URL 判断归档是否已经追加.
                String rootPath = "/" + runtimePackage.replace('.', '/') + "/";
                URL archiveUrl = new URL(null, "sparrow-memory:" + rootPath, new ArchiveUrlStreamHandler(rootPath, BukkitProxyInstaller.readArchiveEntries(archive)));
                ClassPathAccess.ADD_URL.invoke(urlClassLoader, archiveUrl);
                return;
            }
            // 使用兼容方式注入JarInJar.
            Method addTransformationPath = minecraftClassLoader.getClass().getMethod("addTransformationPath", Path.class);
            Path archivePath = Files.createTempFile("sparrow-ui-proxy-", ".jar");
            boolean added = false;
            try {
                Files.write(archivePath, archive);
                addTransformationPath.invoke(minecraftClassLoader, archivePath);
                archivePath.toFile().deleteOnExit();
                added = true;
            } finally {
                if (!added) {
                    Files.deleteIfExists(archivePath);
                }
            }
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to append the proxy archive to the Minecraft class path", throwable);
        }
    }

    // 把代理 Jar 的全部条目读成条目名到字节的映射.
    private static Map<String, byte[]> readArchiveEntries(byte[] archive) {
        HashMap<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), input.readAllBytes());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + PROXY_ARCHIVE, exception);
        }
        // 空 Jar 说明构建产物损坏, 提前失败
        if (entries.isEmpty()) {
            throw new IllegalStateException(PROXY_ARCHIVE + " is empty");
        }
        return Map.copyOf(entries);
    }

    // 将内层 Jar 条目作为只读 URL 资源暴露给 URLClassLoader.
    private static final class ArchiveUrlStreamHandler extends URLStreamHandler {
        private final String rootPath;
        private final Map<String, byte[]> entries; // 条目名到条目字节

        private ArchiveUrlStreamHandler(String rootPath, Map<String, byte[]> entries) {
            this.rootPath = rootPath;
            this.entries = entries;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new ArchiveUrlConnection(url, this.rootPath, this.entries);
        }
    }

    // 为单个内层 Jar 条目提供独立输入流.
    private static final class ArchiveUrlConnection extends URLConnection {
        private final byte[] content; // 条目字节, 条目不存在时为 null

        // 根路径标识库副本, 剩余路径对应归档中的条目名.
        private ArchiveUrlConnection(URL url, String rootPath, Map<String, byte[]> entries) {
            super(url);
            String path = url.getPath();
            String entryName = path.substring(rootPath.length());
            this.content = entries.get(entryName);
        }

        @Override
        public void connect() throws IOException {
            if (this.content == null) {
                throw new FileNotFoundException(this.url.toExternalForm());
            }
            this.connected = true;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            this.connect();
            return new ByteArrayInputStream(this.content);
        }

        @Override
        public int getContentLength() {
            return this.content == null ? -1 : this.content.length;
        }

        @Override
        public long getContentLengthLong() {
            return this.getContentLength();
        }
    }

    // 持有 URLClassLoader.addURL 的全权限句柄.
    @SuppressWarnings("deprecation")
    private static final class ClassPathAccess {
        private static final MethodHandle ADD_URL = ClassPathAccess.createAddUrlHandle(); // URLClassLoader.addURL 的全权限句柄

        private ClassPathAccess() {
        }

        // 创建 URLClassLoader.addURL 的 MethodHandle.
        private static MethodHandle createAddUrlHandle() {
            try {
                Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Unsafe unsafe = (Unsafe) unsafeField.get(null);

                Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                Object lookupBase = unsafe.staticFieldBase(lookupField);
                long lookupOffset = unsafe.staticFieldOffset(lookupField);
                MethodHandles.Lookup lookup = (MethodHandles.Lookup) unsafe.getObject(
                        lookupBase,
                        lookupOffset
                );

                return lookup.findVirtual(
                        URLClassLoader.class,
                        "addURL",
                        MethodType.methodType(void.class, URL.class)
                );
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
