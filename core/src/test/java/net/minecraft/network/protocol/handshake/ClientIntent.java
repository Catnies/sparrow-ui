package net.minecraft.network.protocol.handshake;

public enum ClientIntent {
    STATUS(1), LOGIN(2), TRANSFER(3);

    private final int id;

    ClientIntent(int id) {
        this.id = id;
    }

    public int id() {
        return this.id;
    }

    public static ClientIntent byId(int id) {
        return switch (id) {
            case 1 -> STATUS;
            case 2 -> LOGIN;
            case 3 -> TRANSFER;
            default -> throw new IllegalArgumentException("Unknown connection intent: " + id);
        };
    }
}
