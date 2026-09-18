package net.momirealms.sparrow.ui.network.listener.handshake;

import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;
import net.momirealms.sparrow.ui.network.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.ByteBufPacketHandler;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkPipelineOrder;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.PacketBuf;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class IntentionListener implements ByteBufPacketHandler {
    public static final ByteBufPacketHandler INSTANCE = new IntentionListener();

    private IntentionListener() {
    }

    @Override
    public void handle(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        PacketBuf buffer = event.buffer();
        ConnectionState next;
        try {
            // 握手 payload 依次为版本号、地址、端口和下一阶段, 这里只需要最后一个字段.
            buffer.readVarInt();
            // 转发地址可能包含 BungeeCord/Floodgate 属性, 按线上的长度跳过.
            buffer.skipBytes(buffer.readVarInt());
            buffer.skipBytes(2);
            next = switch (buffer.readVarInt()) {
                case 1 -> ConnectionState.STATUS;
                case 2, 3 -> ConnectionState.LOGIN;
                default -> throw new DecoderException("Unknown handshake intention");
            };
        } catch (IndexOutOfBoundsException | IllegalArgumentException | DecoderException failure) {
            // 非法握手由当前监听器消费并断开, 后续监听器不再接收这帧.
            event.cancel();
            user.channel().close();
            return;
        }
        // 握手确定整条连接用途, 同时推进入站和出站; transfer 的 nextState=3 也进入 LOGIN.
        user.setConnectionState(next);
        if (next == ConnectionState.LOGIN) {
            // 排在当前已入队的第三方调整之后, 按最终 pipeline 重新定位.
            Channel channel = user.channel();
            channel.eventLoop().execute(() -> NetworkPipelineOrder.relocateByteBufHandlers(user.networkManager(), channel));
        }
    }
}
