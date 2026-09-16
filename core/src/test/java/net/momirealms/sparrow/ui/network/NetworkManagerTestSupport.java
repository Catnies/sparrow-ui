package net.momirealms.sparrow.ui.network;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;

final class NetworkManagerTestSupport {

    private NetworkManagerTestSupport() {
    }

    static NetworkManager openManager() {
        BukkitProxy.init("1.21.8", List.of("paper"));
        net.minecraft.server.MinecraftServer.reset();
        Plugin plugin = MockBukkit.createMockPlugin("SparrowUiNetworkTest");
        installPlugin(plugin);
        return new NetworkManager();
    }

    static void closeManager(NetworkManager manager) {
        manager.close();
        installPlugin(null);
    }

    static EmbeddedChannel openChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("packet_handler", new net.minecraft.network.Connection());
        return channel;
    }

    private static void installPlugin(Plugin plugin) {
        try {
            Field field = SparrowUI.class.getDeclaredField("plugin");
            field.setAccessible(true);
            field.set(SparrowUI.getInstance(), plugin);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to install the test plugin", exception);
        }
    }
}
