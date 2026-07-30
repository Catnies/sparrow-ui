package net.momirealms.sparrow.ui.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.remapper.Remapper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 在代理接口首次解析前配置 sparrow-reflection 的版本条件和 Paper 映射.
 */
public final class BukkitProxy {
    public static final int INSTALLATION_VERSION = 1;

    private static boolean initialized;

    private BukkitProxy() {
    }

    /**
     * 初始化共享反射运行时. 重复安装只保留首次成功配置.
     *
     * @param version 纯 Minecraft 版本字符串
     * @param patches 当前服务端发行版补丁标记
     */
    public static synchronized void init(String version, List<String> patches) {
        if (BukkitProxy.initialized) {
            return;
        }

        SReflection.setAsmClassPrefix("sparrow_ui");
        SReflection.setActivePredicate(new MinecraftPredicate(version, patches));
        Remapper remapper = BukkitProxy.createFromPaperJar();
        if (remapper != Remapper.noOp()) {
            SReflection.setRemapper(CraftBukkitRemapper.create(remapper));
        }
        BukkitProxy.initialized = true;
    }

    private static Remapper createFromPaperJar() {
        // NeoForge 的运行时已经使用 Mojang 命名
        if (SparrowClass.existsNoRemap("net.neoforged.art.internal.RenamerImpl")) {
            return Remapper.noOp();
        }
        Class<?> minecraftClass = SparrowClass.find(
                "net.minecraft.obfuscate.DontObfuscate",
                "net.minecraft.server.Main"
        );
        if (minecraftClass == null) {
            return Remapper.noOp();
        }

        // 新版无混淆服务端通过 world version 直接识别
        try (InputStream input = minecraftClass.getClassLoader().getResourceAsStream("version.json")) {
            if (input != null) {
                JsonObject json = new Gson().fromJson(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8),
                        JsonObject.class
                );
                if (json.get("world_version").getAsInt() >= 4764) {
                    return Remapper.noOp();
                }
            }
        } catch (Throwable ignored) {
            // version.json 只用于快速识别, 失败后继续检查 Paper 映射资源
        }

        try (InputStream input = minecraftClass.getClassLoader().getResourceAsStream("META-INF/mappings/reobf.tiny")) {
            if (input == null) {
                return Remapper.noOp(); // mojmap version
            }
            InputStream buffered = input instanceof BufferedInputStream ? input : new BufferedInputStream(input);
            if (BukkitProxy.firstLine(buffered).contains("mojang+yarn")) {
                return Remapper.create(buffered, "mojang+yarn", "spigot");
            }
            return Remapper.create(buffered, "mojang", "spigot");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read META-INF/mappings/reobf.tiny", exception);
        }
    }

    private static String firstLine(InputStream input) {
        try {
            input.mark(1024);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            input.reset();
            return line;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read first line of input stream", exception);
        }
    }
}
