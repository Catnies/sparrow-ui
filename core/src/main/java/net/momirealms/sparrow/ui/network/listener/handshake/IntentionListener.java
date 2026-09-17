package net.momirealms.sparrow.ui.network.listener.handshake;

import io.netty.channel.Channel;
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
            // serverAddress 只跳过不解析. BungeeCord 和 Floodgate 转发会把 IP, UUID 和属性都塞进这个字段,
            // 长度上限也比 vanilla 宽松(Paper 到 Short.MAX_VALUE), 照着 vanilla 的 255 读会把转发的玩家挡在门外.
            buffer.skipBytes(buffer.readVarInt());
            buffer.readUnsignedShort(); // serverPort
            nextState = switch (buffer.readVarInt()) {
                case 1 -> ConnectionState.STATUS;
                case 2, 3 -> ConnectionState.LOGIN;
                default -> null;
            };
        } catch (Throwable e) {
            // 帧本身就坏, 没什么好继续谈的, 丢掉这一帧并断开.
            event.cancelled(true);
            user.channel().close();
            return;
        }
        // 既不是状态查询(1)也不是登录(2, 3), 这种握手下不去, 断开.
        if (nextState == null) {
            event.cancelled(true);
            user.channel().close();
            return;
        }
        user.setConnectionState(nextState);
        if (nextState == ConnectionState.LOGIN) {
            // 推到 event loop 上重排: 这一刻可能还有别的插件在排队改 pipeline(CraftEngine 就会在此时重排),
            // 等它们的任务先跑完, Sparrow 再按最终形状收口, handler 的相对顺序才定得下来.
            Channel channel = user.channel();
            channel.eventLoop().execute(() -> NetworkPipelineOrder.relocateByteBufHandlers(user.networkManager(), channel));
        }
    }
}
