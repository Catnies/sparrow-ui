package net.momirealms.sparrow.ui.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class VersionHelper {
    private VersionHelper() {}

    public static final String MINECRAFT_VERSION; // 服务端版本号, 例如 1.21.10
    public static final int WORLD_VERSION;        // 服务端数据版本, 用于存档数据升级
    private static final int version;             // 版本号编码后的整数, 例如 1.21.10 -> 12110
    private static final int majorVersion;        // 版本字符串第二段的整数值
    private static final int minorVersion;        // 版本字符串第三段的整数值, 两段式版本号为 0
    private static final boolean mojmap;
    private static final boolean folia;
    private static final boolean paper;
    private static final boolean leaves;
    private static final boolean canvas;
    private static final boolean v1_21_8;
    private static final boolean v1_21_9;
    private static final boolean v1_21_10;
    private static final boolean v1_21_11;
    private static final boolean v26_1;
    private static final boolean v26_1_1;
    private static final boolean v26_1_2;
    private static final boolean v26_2;

    // 多写几个候选名: 无混淆映射的版本缺少部分 Main 类
    private static final Class<?> UNOBFUSCATED_CLAZZ = ReflectionUtils.getClazz(
            "net.minecraft.obfuscate.DontObfuscate",
            "net.minecraft.data.Main",
            "net.minecraft.server.Main",
            "net.minecraft.gametest.Main",
            "net.minecraft.client.main.Main",
            "net.minecraft.client.data.Main"
    );

    static {
        // 一次性解析 version.json, 并探测运行平台特征供后续 isXxx 查询
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
                case "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "99.99.99" -> versionString;
                default -> throw new IllegalArgumentException("Unsupported version: " + versionString);
            };

            String[] split = versionString.split("\\.");
            int major = Integer.parseInt(split[1]);
            int minor = split.length == 3 ? Integer.parseInt(split[2]) : 0;

            // 12001 = 1.20.1
            // 12104 = 1.21.4
            version = parseVersionToInteger(versionString);

            // 版本特征位: 达到某版本即置位, 供兼容层按版本切换实现
            v1_21_8 = version >= 12108;
            v1_21_9 = version >= 12109;
            v1_21_10 = version >= 12110;
            v1_21_11 = version >= 12111;
            v26_1 = version >= 260100;
            v26_1_1 = version >= 260101;
            v26_1_2 = version >= 260102;
            v26_2 = version >= 260200;

            majorVersion = major;
            minorVersion = minor;

            mojmap = checkMojMap() || v26_1; // 26.1 起官方服务端默认使用 Mojang 映射
            folia = checkFolia();
            paper = checkPaper();
            leaves = checkLeaves();
            canvas = checkCanvas();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init VersionHelper", e);
        }
    }

    public static int parseVersionToInteger(String versionString) {
        // 逐段解析点分数字, 只取前三段
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
        // 处理最后一个数字部分
        if (part == 0) {  // 没有点号：如 "26"
            v1 = currentNumber;
        } else if (part == 1) {  // 一个点号：如 "26.1"
            v2 = currentNumber;
        } else if (part == 2) {  // 两个点号：如 "1.2.3"
            v3 = currentNumber;
        }
        return 10000 * v1 + v2 * 100 + v3;
    }

    /**
     * 返回服务端主版本号, 即版本字符串第二段的整数值, 例如 1.21.10 返回 21.
     *
     * @return 主版本号
     */
    public static int majorVersion() {
        return majorVersion;
    }

    /**
     * 返回服务端次版本号, 即版本字符串第三段的整数值, 两段式版本号(如 26.1)返回 0.
     *
     * @return 次版本号
     */
    public static int minorVersion() {
        return minorVersion;
    }

    /**
     * 返回编码后的完整版本号整数, 编码规则见 parseVersionToInteger.
     *
     * @return 版本整数, 例如 1.21.10 -> 12110
     */
    public static int version() {
        return version;
    }

    private static boolean checkMojMap() {
        return ReflectionUtils.classExists("net.neoforged.art.internal.RenamerImpl");
    }

    private static boolean checkFolia() {
        return ReflectionUtils.classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    private static boolean checkPaper() {
        return ReflectionUtils.classExists("io.papermc.paper.adventure.PaperAdventure");
    }

    private static boolean checkLeaves() {
        return ReflectionUtils.classExists("org.leavesmc.leaves.bot.BotList");
    }

    private static boolean checkCanvas() {
        return ReflectionUtils.classExists("io.canvasmc.canvas.Config");
    }

    /**
     * 判断服务端是否为 Folia 分支.
     *
     * @return 是 Folia 时返回 true
     */
    public static boolean isFolia() {
        return folia;
    }

    /**
     * 判断服务端是否为 Paper 或基于 Paper 的发行版.
     *
     * @return 是 Paper 系服务端时返回 true
     */
    public static boolean isPaper() {
        return paper;
    }

    /**
     * 判断服务端是否为 Canvas 分支.
     *
     * @return 是 Canvas 时返回 true
     */
    public static boolean isCanvas() {
        return canvas;
    }

    /**
     * 判断服务端是否为 Leaves 分支.
     *
     * @return 是 Leaves 时返回 true
     */
    public static boolean isLeaves() {
        return leaves;
    }

    /**
     * 判断服务端是否运行在 Mojang 官方映射(无混淆)环境.
     *
     * @return 是 Mojmap 环境时返回 true
     */
    public static boolean isMojmap() {
        return mojmap;
    }

    /**
     * 判断服务端版本是否不低于 1.21.8.
     *
     * @return 不低于 1.21.8 时返回 true
     */
    public static boolean isOrAbove1_21_8() {
        return v1_21_8;
    }

    /**
     * 判断服务端版本是否不低于 1.21.9.
     *
     * @return 不低于 1.21.9 时返回 true
     */
    public static boolean isOrAbove1_21_9() {
        return v1_21_9;
    }

    /**
     * 判断服务端版本是否不低于 1.21.10.
     *
     * @return 不低于 1.21.10 时返回 true
     */
    public static boolean isOrAbove1_21_10() {
        return v1_21_10;
    }

    /**
     * 判断服务端版本是否不低于 1.21.11.
     *
     * @return 不低于 1.21.11 时返回 true
     */
    public static boolean isOrAbove1_21_11() {
        return v1_21_11;
    }

    /**
     * 判断服务端版本是否不低于 26.1.
     *
     * @return 不低于 26.1 时返回 true
     */
    public static boolean isOrAbove26_1() {
        return v26_1;
    }

    /**
     * 判断服务端版本是否不低于 26.1.1.
     *
     * @return 不低于 26.1.1 时返回 true
     */
    public static boolean isOrAbove26_1_1() {
        return v26_1_1;
    }

    /**
     * 判断服务端版本是否不低于 26.1.2.
     *
     * @return 不低于 26.1.2 时返回 true
     */
    public static boolean isOrAbove26_1_2() {
        return v26_1_2;
    }

    /**
     * 判断服务端版本是否不低于 26.2.
     *
     * @return 不低于 26.2 时返回 true
     */
    public static boolean isOrAbove26_2() {
        return v26_2;
    }

    /**
     * 收集当前服务端所具备的补丁标识.
     * 该列表会用于初始化代理层, 以便根据具体发行版差异加载不同兼容逻辑.
     *
     * @return 当前服务端命中的补丁名称列表, 如 paper, folia 等
     */
    public static List<String> getPatches() {
        List<String> patches = new ArrayList<>();
        if (isPaper()) {
            patches.add("paper");
        }
        if (isFolia()) {
            patches.add("folia");
        }
        if (isLeaves()) {
            patches.add("leaves");
        }
        if (isCanvas()) {
            patches.add("canvas");
        }
        return patches;
    }
}
