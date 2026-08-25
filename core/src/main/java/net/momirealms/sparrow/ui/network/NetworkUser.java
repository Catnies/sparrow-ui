package net.momirealms.sparrow.ui.network;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@ApiStatus.Experimental
public final class NetworkUser {
    public final NetworkManager network;

    private final Channel channel;
    private volatile ConnectionState decoderState = ConnectionState.HANDSHAKING;
    private volatile ConnectionState encoderState = ConnectionState.HANDSHAKING;
    private volatile @Nullable Player player;
    private int bypassDepth;

    NetworkUser(@NotNull NetworkManager network, @NotNull Channel channel) {
        this.network = network;
        this.channel = channel;
    }

    @NotNull
    public Channel channel() {
        return this.channel;
    }

    @NotNull
    public ConnectionState decoderState() {
        return this.decoderState;
    }

    @NotNull
    public ConnectionState encoderState() {
        return this.encoderState;
    }

    @Nullable
    public Player player() {
        return this.player;
    }

    @Nullable
    public UUID uuid() {
        Player player = this.player;
        return player == null ? null : player.getUniqueId();
    }

    @Nullable
    public String name() {
        Player player = this.player;
        return player == null ? null : player.getName();
    }

    void player(@Nullable Player player) {
        this.player = player;
    }

    @ApiStatus.Internal
    public void setConnectionState(@NotNull ConnectionState state) {
        this.decoderState = state;
        this.encoderState = state;
    }

    @ApiStatus.Internal
    public void decoderState(@NotNull ConnectionState state) {
        this.decoderState = state;
    }

    @ApiStatus.Internal
    public void encoderState(@NotNull ConnectionState state) {
        this.encoderState = state;
    }

    boolean bypassing() {
        return this.bypassDepth != 0;
    }

    void beginBypass() {
        this.bypassDepth++;
    }

    void endBypass() {
        this.bypassDepth--;
    }
}
