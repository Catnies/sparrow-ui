package net.minecraft.world;

public interface Clearable {
    default void clearContent() {
        throw new UnsupportedOperationException();
    }
}
