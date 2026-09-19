package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.network.packet.PacketType;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketTypeTest {
    @Test
    void constructsAnOpenDescriptorWithoutNmsOrProxyClasses() throws Exception {
        URL classes = PacketType.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.") || name.startsWith("net.momirealms.sparrow.ui.proxy.")) {
                    throw new AssertionError("Packet descriptor accessed runtime class " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> type = Class.forName(PacketType.class.getName(), true, loader);
            Class<?> state = loader.loadClass("net.momirealms.sparrow.ui.network.packet.ConnectionState");
            Class<?> flow = loader.loadClass("net.momirealms.sparrow.ui.network.packet.PacketFlow");
            Object descriptor = type.getConstructor(String.class, state, flow).newInstance("test:future_packet", state.getField("PLAY").get(null), flow.getField("SERVERBOUND").get(null));
            assertEquals("test:future_packet", type.getMethod("name").invoke(descriptor));
        }
    }
}
