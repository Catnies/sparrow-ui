package net.momirealms.sparrow.ui.network.listener.game;

import net.momirealms.sparrow.ui.network.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.ByteBufPacketListener;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class StartConfigurationListener implements ByteBufPacketListener {
    public static final ByteBufPacketListener INSTANCE = new StartConfigurationListener();

    private StartConfigurationListener() {
    }

    @Override
    public void onPacketSend(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        user.encoderState(ConnectionState.CONFIGURATION);
    }
}
