package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.util.ReflectionUtils;
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
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 将NMS 代理接口安装到 Minecraft 共享类加载器.
 * <p>代理实现必须先于任何代理接口被解析. 安装过程不能使用反射库和代理接口.
 */
@ApiStatus.Internal
public final class BukkitProxyInstaller {
    private static final String PROXY_ARCHIVE = "proxy.jarinjar";
    private static final String PROXY_BOOTSTRAP = "net.momirealms.sparrow.ui.proxy.BukkitProxy";

    private BukkitProxyInstaller() {
    }

    /**
     * 安装并初始化当前库的反射代理运行时.
     *
     * @throws IllegalStateException 代理 Jar 缺失、类路径安装失败或代理初始化失败
     */
    public static void setUp() {
        try {
            ClassLoader minecraftClassLoader = Bukkit.class.getClassLoader();
            byte[] archive = BukkitProxyInstaller.readProxyArchive();
            BukkitProxyInstaller.appendToMinecraftClassPath(minecraftClassLoader, archive);
            Class<?> bootstrapClass = ReflectionUtils.getClazz(PROXY_BOOTSTRAP);
            ReflectionUtils.getStaticMethod(bootstrapClass, 0).invoke(null, VersionHelper.MINECRAFT_VERSION.version(), VersionHelper.getPatches());
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to initialize the SparrowUI reflection proxy", e);
        }
    }

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

    private static void appendToMinecraftClassPath(ClassLoader minecraftClassLoader, byte[] archive) {
        try {
            URL archiveUrl = new URL(null, "sparrow-memory:/", new ArchiveUrlStreamHandler(BukkitProxyInstaller.readArchiveEntries(archive)));
            ClassPathAccess.ADD_URL.invoke((URLClassLoader) minecraftClassLoader, archiveUrl);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to append the proxy archive to the Minecraft class path", throwable);
        }
    }

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
        if (entries.isEmpty()) {
            throw new IllegalStateException(PROXY_ARCHIVE + " is empty");
        }
        return Map.copyOf(entries);
    }

    private static boolean classExists(ClassLoader classLoader, String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * 将内层 Jar 条目作为只读 URL 资源暴露给 URLClassLoader.
     */
    private static final class ArchiveUrlStreamHandler extends URLStreamHandler {
        private final Map<String, byte[]> entries;

        private ArchiveUrlStreamHandler(Map<String, byte[]> entries) {
            this.entries = entries;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new ArchiveUrlConnection(url, this.entries);
        }
    }

    /**
     * 为单个内层 Jar 条目提供独立输入流.
     */
    private static final class ArchiveUrlConnection extends URLConnection {
        private final byte[] content;

        private ArchiveUrlConnection(URL url, Map<String, byte[]> entries) {
            super(url);
            String path = url.getPath();
            String entryName = path.startsWith("/") ? path.substring(1) : path;
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
    
    @SuppressWarnings({"removal", "deprecation"})
    private static final class ClassPathAccess {
        private static final MethodHandle ADD_URL = ClassPathAccess.createAddUrlHandle();

        private ClassPathAccess() {
        }

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
