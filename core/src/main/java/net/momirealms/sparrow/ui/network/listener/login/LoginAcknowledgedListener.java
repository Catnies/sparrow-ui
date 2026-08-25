package net.momirealms.sparrow.ui.network.listener.login;

import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.network.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.ByteBufPacketListener;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

@ApiStatus.Internal
public final class LoginAcknowledgedListener implements ByteBufPacketListener {
    private final Consumer<Channel> relocate;

    public LoginAcknowledgedListener(@NotNull Consumer<Channel> relocate) {
        this.relocate = relocate;
    }

    @Override
    public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        user.setConnectionState(ConnectionState.CONFIGURATION);
        // PacketEvents 可能到登录确认才装完, 再按最终 pipeline 收口一次.
        user.channel().eventLoop().execute(() -> this.relocate.accept(user.channel()));
    }
}
