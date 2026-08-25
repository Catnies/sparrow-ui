package net.momirealms.sparrow.ui.network.listener.login;

import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.network.*;
import net.momirealms.sparrow.ui.network.listener.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.listener.ByteBufPacketListener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class LoginAcknowledgedListener implements ByteBufPacketListener {
    public static final ByteBufPacketListener INSTANCE = new LoginAcknowledgedListener();

    private LoginAcknowledgedListener() {
    }

    @Override
    public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        user.setConnectionState(ConnectionState.CONFIGURATION);
        // PacketEvents 可能到登录确认才装完, 再按最终 pipeline 收口一次.
        Channel channel = user.channel();
        channel.eventLoop().execute(() -> NetworkPipelineOrder.relocateByteBufHandlers(user.networkManager(), channel));
    }
}
