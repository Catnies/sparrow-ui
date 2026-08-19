package net.momirealms.sparrow.ui;

public interface Subscription extends AutoCloseable {

    boolean isClosed();

    @Override
    void close();
}
