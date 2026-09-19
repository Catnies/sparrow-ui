package net.momirealms.sparrow.ui.network.listener.handshake;

import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NMSPacketHandler;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkPipelineOrder;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake.ClientIntentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake.ClientIntentionPacketProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class IntentionListener implements NMSPacketHandler {
    public static final NMSPacketHandler INSTANCE = new IntentionListener();

    private IntentionListener() {
    }

    @Override
    public void handle(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
        // 原版解码器已验证握手内容, 这里只读取解码后的连接用途.
        Object intention = ClientIntentionPacketProxy.INSTANCE.intention(packet);
        ConnectionState next = switch (ClientIntentProxy.INSTANCE.id(intention)) {
            case 1 -> ConnectionState.STATUS;
            case 2, 3 -> ConnectionState.LOGIN;
            default -> throw new IllegalArgumentException("Unknown handshake intention: " + intention);
        };
        // 握手确定整条连接用途, 同时推进入站和出站; transfer 的 nextState=3 也进入 LOGIN.
        user.setConnectionState(next);
        if (next == ConnectionState.LOGIN) {
            // 排在当前已入队的第三方调整之后, 按最终 pipeline 重新定位.
            Channel channel = user.channel();
            channel.eventLoop().execute(() -> NetworkPipelineOrder.relocateByteBufHandlers(user.networkManager(), channel));
        }
    }
}
