package net.momirealms.sparrow.ui.network.listener.game;

import net.momirealms.sparrow.ui.network.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.ByteBufPacketHandler;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class LoginListener implements ByteBufPacketHandler {
    public static final ByteBufPacketHandler INSTANCE = new LoginListener();

    private LoginListener() {
    }

    @Override
    public void handle(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        user.decoderState(ConnectionState.PLAY);
    }
}
