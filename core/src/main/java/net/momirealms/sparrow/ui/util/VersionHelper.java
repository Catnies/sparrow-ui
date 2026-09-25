package net.momirealms.sparrow.ui.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class VersionHelper {
    private VersionHelper() {}

    public static final boolean IS_RUNNING_IN_DEV = Boolean.getBoolean("net.momirealms.plugin.dev");
    public static final String MINECRAFT_VERSION; // 服务端版本号, 例如 1.21.10
    public static final int WORLD_VERSION;        // 服务端数据版本, 用于存档数据升级
    public static final int version;             // 版本号编码后的整数, 例如 1.21.10 -> 12110
    public static final int majorVersion;        // 版本字符串第二段的整数值
    public static final int minorVersion;        // 版本字符串第三段的整数值, 两段式版本号为 0
    public static final boolean isMojmap;
    public static final boolean hasSpigotPatch;
    public static final boolean hasFoliaPatch;
    public static final boolean hasPaperPatch;
    public static final boolean hasLeavesPatch;
    public static final boolean hasCanvasPatch;
    public static final boolean hasLeafPatch;
    public static final boolean hasLithiumPatch;
    public static final boolean hasUniverseSpigotPatch;
    public static final boolean isOrAbove1_21_4;
    public static final boolean isOrAbove1_21_5;
    public static final boolean isOrAbove1_21_6;
    public static final boolean isOrAbove1_21_7;
    public static final boolean isOrAbove1_21_8;
    public static final boolean isOrAbove1_21_9;
    public static final boolean isOrAbove1_21_10;
    public static final boolean isOrAbove1_21_11;
    public static final boolean isOrAbove26_1;
    public static final boolean isOrAbove26_1_1;
    public static final boolean isOrAbove26_1_2;
    public static final boolean isOrAbove26_2;
    public static final boolean isOrAbove26_3;

    // 无混淆发行版可能缺少某些 Main 类, 候选名称覆盖这些差异.
    private static final Class<?> UNOBFUSCATED_CLAZZ = ReflectionUtils.getClazz(
            "net.minecraft.obfuscate.DontObfuscate",
            "net.minecraft.data.Main",
            "net.minecraft.server.Main",
            "net.minecraft.gametest.Main",
            "net.minecraft.client.main.Main",
            "net.minecraft.client.data.Main"
    );

    static {
        try (InputStream inputStream = UNOBFUSCATED_CLAZZ.getResourceAsStream("/version.json")) {
            if (inputStream == null) {
                throw new IOException("Failed to load version.json");
            }
            JsonObject json = JsonParser.parseString(
                    new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
            ).getAsJsonObject();
            JsonElement worldVersion = json.get("world_version");
            WORLD_VERSION = worldVersion == null || worldVersion.isJsonNull() ? -1 : worldVersion.getAsInt();
            if (WORLD_VERSION == -1) {
                throw new IllegalStateException("Failed to get world_version from version.json");
            }
            String versionString = json.getAsJsonPrimitive("id").getAsString()
                    .split("-", 2)[0]  // 1.21.10-rc1          -> 1.21.10
                    .split("_", 2)[0]; // 1.21.11_unobfuscated -> 1.21.11

            MINECRAFT_VERSION = switch (versionString) {
                case "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3", "99.99.99" -> versionString;
                default -> throw new IllegalArgumentException("Unsupported version: " + versionString);
            };

            String[] split = versionString.split("\\.");
            int major = Integer.parseInt(split[1]);
            int minor = split.length == 3 ? Integer.parseInt(split[2]) : 0;

            // 1.21.4 -> 12104, 26.3 -> 260300.
            version = parseVersionToInteger(versionString);

            isOrAbove1_21_4 = version >= 12104;
            isOrAbove1_21_5 = version >= 12105;
            isOrAbove1_21_6 = version >= 12106;
            isOrAbove1_21_7 = version >= 12107;
            isOrAbove1_21_8 = version >= 12108;
            isOrAbove1_21_9 = version >= 12109;
            isOrAbove1_21_10 = version >= 12110;
            isOrAbove1_21_11 = version >= 12111;
            isOrAbove26_1 = version >= 260100;
            isOrAbove26_1_1 = version >= 260101;
            isOrAbove26_1_2 = version >= 260102;
            isOrAbove26_2 = version >= 260200;
            isOrAbove26_3 = version >= 260300;

            majorVersion = major;
            minorVersion = minor;

            isMojmap = checkMojMap() || isOrAbove26_1;
            hasSpigotPatch = checkSpigot();
            hasFoliaPatch = checkFolia();
            hasPaperPatch = checkPaper();
            hasLeavesPatch = checkLeaves();
            hasCanvasPatch = checkCanvas();
            hasLeafPatch = checkLeaf();
            hasLithiumPatch = checkLithium();
            hasUniverseSpigotPatch = checkUniverseSpigot();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init VersionHelper", e);
        }
    }

    /**
     * 将一至三段点分数字编码为可直接比较的整数.
     * <p>编码公式为 {@code first * 10000 + second * 100 + third}, 缺少的段按 0 处理.
     *
     * @param versionString 点分数字版本, 例如 {@code 1.21.10} 或 {@code 26.1}
     * @return 编码后的版本整数
     */
    public static int parseVersionToInteger(@NotNull String versionString) {
        // 按字符累计当前数字段.
        int v1 = 0;
        int v2 = 0;
        int v3 = 0;
        int currentNumber = 0;
        int part = 0;
        for (int i = 0; i < versionString.length(); i++) {
            char c = versionString.charAt(i);
            if (c >= '0' && c <= '9') {
                currentNumber = currentNumber * 10 + (c - '0');
            } else if (c == '.') {
                if (part == 0) {
                    v1 = currentNumber;
                }
                if (part == 1) {
                    v2 = currentNumber;
                }
                part++;
                currentNumber = 0;
                if (part > 2) {
                    break;
                }
            }
        }
        // 循环结束时最后一段还没有写入对应槽位.
        if (part == 0) {  // 例如 26
            v1 = currentNumber;
        } else if (part == 1) {  // 例如 26.1
            v2 = currentNumber;
        } else if (part == 2) {  // 例如 1.2.3
            v3 = currentNumber;
        }
        return 10000 * v1 + v2 * 100 + v3;
    }

    /**
     * 返回代理层用于选择发行版兼容逻辑的补丁标识.
     *
     * @return 新的补丁名称列表, 可能包含 {@code paper}, {@code folia}, {@code leaves} 或 {@code canvas}
     */
    public static List<String> getPatches() {
        List<String> patches = new ArrayList<>();
        if (hasPaperPatch) patches.add("paper");
        if (hasLeafPatch) patches.add("leaf");
        if (hasLeavesPatch) patches.add("leaves");
        if (hasFoliaPatch) patches.add("folia");
        if (hasCanvasPatch) patches.add("canvas");
        if (hasLithiumPatch) patches.add("lithium");
        return patches;
    }

    private static boolean checkMojMap() {
        return exists("net.neoforged.art.internal.RenamerImpl");
    }

    private static boolean checkFolia() {
        return exists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    private static boolean checkPaper() {
        return exists("io.papermc.paper.adventure.PaperAdventure");
    }

    private static boolean checkLeaves() {
        return exists("org.leavesmc.leaves.bot.BotList");
    }

    private static boolean checkCanvas() {
        return exists("io.canvasmc.canvas.Config", "io.canvasmc.canvas.GlobalConfiguration");
    }

    private static boolean checkSpigot() {
        return exists("org.spigotmc.SpigotConfig");
    }

    private static boolean checkLeaf() {
        return exists("org.dreeam.leaf.config.LeafConfig", "org.dreeam.leaf.async.chunk.AsyncChunkSender");
    }

    private static boolean checkLithium() {
        return exists("net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette");
    }

    private static boolean checkUniverseSpigot() {
        return exists("com.universeprojects.util.palette.CompactHashPalette");
    }

    private static boolean exists(@NotNull String... classNames) {
        for (String className : classNames) {
            try {
                Class.forName(className, false, VersionHelper.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }
}
