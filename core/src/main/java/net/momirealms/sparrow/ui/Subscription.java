package net.momirealms.sparrow.ui;

/**
 * 持有 {@link Observable} 与 {@link Observer} 之间的一条订阅关系.
 */
public interface Subscription extends AutoCloseable {

    boolean isClosed();

    /**
     * 取消此订阅关系.
     * <p>重复调用不会产生任何影响.
     */
    @Override
    void close();
}
