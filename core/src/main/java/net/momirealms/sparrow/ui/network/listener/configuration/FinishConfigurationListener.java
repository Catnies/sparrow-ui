package net.momirealms.sparrow.ui.network.listener.configuration;

import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NMSPacketHandler;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class FinishConfigurationListener implements NMSPacketHandler {
    public static final NMSPacketHandler INSTANCE = new FinishConfigurationListener();

    private FinishConfigurationListener() {
    }

    @Override
    public void handle(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
        user.encoderState(ConnectionState.PLAY);
    }
}
