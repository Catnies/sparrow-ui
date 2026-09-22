package net.momirealms.sparrow.ui;

import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerStub extends PlayerMock {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final @Nullable PlayerInventory inventory;
    private PlayerStub(ServerMock server, String name, @Nullable PlayerInventory inventory) {
        super(server, name);
        this.inventory = inventory;
    }

    public static PlayerStub addTo(ServerMock server) {
        return addTo(server, null);
    }

    public static PlayerStub addTo(ServerMock server, @Nullable PlayerInventory inventory) {
        PlayerConnectionTestSupport.install();
        PlayerStub player = new PlayerStub(server, "Player" + COUNTER.incrementAndGet(), inventory);
        server.addPlayer(player);
        return player;
    }

    @Override
    public PlayerInventory getInventory() {
        return this.inventory == null ? super.getInventory() : this.inventory;
    }

    @Override
    public boolean isConnected() {
        return this.isOnline();
    }
}
