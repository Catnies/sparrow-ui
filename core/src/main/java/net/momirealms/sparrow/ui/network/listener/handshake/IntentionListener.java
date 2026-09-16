package net.momirealms.sparrow.ui.network.listener.handshake;

import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;
import net.momirealms.sparrow.ui.network.*;
import net.momirealms.sparrow.ui.network.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.ByteBufPacketListener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class IntentionListener implements ByteBufPacketListener {
    public static final ByteBufPacketListener INSTANCE = new IntentionListener();

    private IntentionListener() {
    }

    @Override
    public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        PacketBuf buffer = event.getBuffer();
        ConnectionState nextState;
        try {
            buffer.readVarInt();        // protocolVersion
            // serverAddress 只跳过不解析, BungeeCord/Floodgate 转发会把 IP/UUID/属性塞进该字段,
            // Paper 的上限是 Short.MAX_VALUE 而非 vanilla 的 255, 这里不能比平台解码器更严.
            buffer.skipBytes(buffer.readVarInt());
            buffer.readUnsignedShort(); // serverPort
            nextState = switch (buffer.readVarInt()) {
                case 1 -> ConnectionState.STATUS;
                case 2, 3 -> ConnectionState.LOGIN;
                default -> null;
            };
        } catch (Throwable e) {
            // 握手解析失败时丢弃当前帧并关闭连接.
            event.cancelled(true);
            user.channel().close();
            return;
        }
        // 如果乱发包直接强行断开连接
        if (nextState == null) {
            event.cancelled(true);
            user.channel().close();
            return;
        }
        user.setConnectionState(nextState);
        if (nextState == ConnectionState.LOGIN) {
            // CraftEngine 存在时先提交自己的重排任务, Sparrow 随后收口最终顺序.
            Channel channel = user.channel();
            channel.eventLoop().execute(() -> NetworkPipelineOrder.relocateByteBufHandlers(user.networkManager(), channel));
        }
    }
}
