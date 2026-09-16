package net.momirealms.sparrow.ui.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.util.CraftChatMessageProxy;
import org.jetbrains.annotations.NotNull;

public final class AdventureUtils {
    private AdventureUtils() {
    }

    /**
     * 将插件侧 Component 编码为目标版本的 JSON, 再由服务端解析为 NMS Component.
     * 跨类加载器只传递字符串, 插件可自行 relocate Adventure 及其序列化器.
     *
     * @param component 插件侧文本
     * @return 新建的 NMS Component
     */
    @NotNull
    public static Object asVanilla(@NotNull Component component) {
        return CraftChatMessageProxy.INSTANCE.fromJSON(JsonSerializer.INSTANCE.serialize(component));
    }

    /**
     * 编码为 Bukkit 旧式标题使用的节号格式, 保留 RGB 颜色与文本装饰.
     * 点击, 悬浮及其他富文本结构不属于该格式.
     *
     * @param component 插件侧标题
     * @return 节号格式的标题文本
     */
    @NotNull
    public static String asLegacy(@NotNull Component component) {
        return LegacySerializer.INSTANCE.serialize(component);
    }

    private static final class JsonSerializer {
        private static final GsonComponentSerializer INSTANCE = GsonComponentSerializer.builder()
                .options(JSONOptions.byDataVersion().at(VersionHelper.WORLD_VERSION))
                .build();
    }

    private static final class LegacySerializer {
        private static final LegacyComponentSerializer INSTANCE = LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.SECTION_CHAR)
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
    }
}
