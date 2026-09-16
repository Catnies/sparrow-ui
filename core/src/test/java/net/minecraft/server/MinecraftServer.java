package net.minecraft.server;

import net.minecraft.server.network.ServerConnectionListener;

public final class MinecraftServer {

    private static final MinecraftServer INSTANCE = new MinecraftServer();
    public ServerConnectionListener connection = new ServerConnectionListener();
    public Object getRecipeManager() {
        return null;
    }

    public static MinecraftServer getServer() {
        return INSTANCE;
    }

    public static void reset() {
        INSTANCE.connection = new ServerConnectionListener();
    }
}
