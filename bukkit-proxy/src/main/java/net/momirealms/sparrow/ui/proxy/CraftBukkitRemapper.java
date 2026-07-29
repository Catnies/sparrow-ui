package net.momirealms.sparrow.ui.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.remapper.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 在仍使用版本化 CraftBukkit 包名的旧服务端上补充 CraftBukkit 类名映射.
 */
public final class CraftBukkitRemapper implements Remapper {
    private static final String PREFIX_CRAFTBUKKIT = "org.bukkit.craftbukkit.";
    private static final String CB_PKG_VERSION;
    private static final boolean NEED_REMAP;
    private final Remapper delegate;

    private CraftBukkitRemapper(Remapper delegate) {
        this.delegate = delegate;
    }

    static {
        String cbPkgVersion = "";
        boolean needRemap = true;

        // 新版无混淆服务端不再需要版本化 CraftBukkit 包名
        if (SparrowClass.existsNoRemap("net.neoforged.art.internal.RenamerImpl")) {
            needRemap = false;
        } else {
            Class<?> minecraftClass = SparrowClass.find("net.minecraft.obfuscate.DontObfuscate", "net.minecraft.server.Main");
            int major = 0;
            int minor = 0;

            // 旧版服务端从 version.json 推导 v1_x_Ry 前缀的候选范围
            try (InputStream inputStream = minecraftClass.getResourceAsStream("/version.json")) {
                if (inputStream == null) {
                    throw new IOException("Failed to load version.json");
                }
                JsonObject json = new Gson().fromJson(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                if (json.get("world_version").getAsInt() >= 4764) {
                    needRemap = false;
                } else {
                    String versionString = json.getAsJsonPrimitive("id").getAsString()
                            .split("-", 2)[0]
                            .split("_", 2)[0];
                    String[] split = versionString.split("\\.");
                    major = Integer.parseInt(split[1]);
                    minor = split.length == 3 ? Integer.parseInt(split[2]) : 0;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to init CraftBukkitRemapper", e);
            }
            if (needRemap) {
                String name;

                // revision 没有直接元数据, 逐个探测实际存在的 CraftServer
                label:
                {
                    for (int i = 0; i <= minor; i++) {
                        try {
                            name = "v1_" + major + "_R" + i + ".";
                            Class.forName(CraftBukkitRemapper.PREFIX_CRAFTBUKKIT + name + "CraftServer");
                            break label;
                        } catch (ClassNotFoundException ignored) {
                        }
                    }
                    throw new RuntimeException("Could not find CraftServer version");
                }
                cbPkgVersion = name;
            }
        }
        CB_PKG_VERSION = cbPkgVersion;
        NEED_REMAP = needRemap;
    }

    /**
     * 按当前服务端是否仍使用版本化包名装饰给定映射器.
     *
     * @param delegate NMS 名称映射器
     * @return 当前服务端适用的映射器
     */
    public static Remapper create(Remapper delegate) {
        if (CraftBukkitRemapper.NEED_REMAP) {
            return new CraftBukkitRemapper(delegate);
        } else {
            return delegate;
        }
    }

    @Override
    public String remapClassName(String className) {
        if (!className.startsWith(CraftBukkitRemapper.PREFIX_CRAFTBUKKIT)) {
            return this.delegate.remapClassName(className);
        }
        return CraftBukkitRemapper.PREFIX_CRAFTBUKKIT
                + CraftBukkitRemapper.CB_PKG_VERSION
                + className.substring(CraftBukkitRemapper.PREFIX_CRAFTBUKKIT.length());
    }

    @Override
    public String remapFieldName(Class<?> clazz, String fieldName) {
        return this.delegate.remapFieldName(clazz, fieldName);
    }

    @Override
    public String remapMethodName(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return this.delegate.remapMethodName(clazz, methodName, parameterTypes);
    }
}
