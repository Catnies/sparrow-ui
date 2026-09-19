package net.momirealms.sparrow.ui.network.listener.login;

import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NMSPacketHandler;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkPipelineOrder;
import net.momirealms.sparrow.ui.network.NetworkUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class LoginAcknowledgedListener implements NMSPacketHandler {
    public static final NMSPacketHandler INSTANCE = new LoginAcknowledgedListener();

    private LoginAcknowledgedListener() {
    }

    @Override
    public void handle(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
        // 客户端确认登录完成后, 两个方向都使用配置协议.
        user.setConnectionState(ConnectionState.CONFIGURATION);
        // 排在当前已入队的第三方调整之后, 按最终 pipeline 重新定位.
        Channel channel = user.channel();
        channel.eventLoop().execute(() -> NetworkPipelineOrder.relocateByteBufHandlers(user.networkManager(), channel));
    }
}
