package net.minecraft.server.network;

import io.netty.channel.ChannelFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ServerConnectionListener {

    public List<ChannelFuture> channels = Collections.synchronizedList(new ArrayList<>());
    private final List<Object> connections = Collections.synchronizedList(new ArrayList<>());
    public List<?> getConnections() {
        return this.connections;
    }
}
