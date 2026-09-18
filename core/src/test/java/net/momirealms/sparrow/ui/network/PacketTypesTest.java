package net.momirealms.sparrow.ui.network;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketTypesTest {
    @Test
    void initializesTheEntireCatalogueWithoutNmsOrProxyClasses() throws Exception {
        URL classes = PacketType.class.getProtectionDomain().getCodeSource().getLocation();
        // 隔离已有测试初始化过的 NMS 类, 逐组触发常量的静态初始化.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.") || name.startsWith("net.momirealms.sparrow.ui.proxy.")) {
                    throw new AssertionError("Packet catalogue accessed runtime class " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> catalogue = Class.forName(PacketTypes.class.getName(), true, loader);
            Class<?>[] stages = catalogue.getDeclaredClasses();
            for (int stageIndex = 0; stageIndex < stages.length; stageIndex++) {
                Class<?>[] directions = stages[stageIndex].getDeclaredClasses();
                for (int directionIndex = 0; directionIndex < directions.length; directionIndex++) {
                    Class.forName(directions[directionIndex].getName(), true, loader);
                }
            }
            Class<?> group = Class.forName(PacketTypes.Play.Serverbound.class.getName(), true, loader);
            Object rename = group.getField("RENAME_ITEM").get(null);
            assertEquals("minecraft:rename_item", rename.getClass().getMethod("name").invoke(rename));
        }
    }
}
