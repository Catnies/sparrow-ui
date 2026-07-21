package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Netty 生产、玩家实体线程消费的有界 FIFO.
 *
 * <p>队列只负责保序和背压. Window 已有唯一实体 tick, 因此不再为每个入站包创建调度任务.</p>
 *
 * @param <T> 入站消息类型
 */
@ApiStatus.Internal
public final class IncomingPacketQueue<T> implements AutoCloseable {

    /**
     * 一次入队尝试的结果.
     */
    public enum OfferResult {
        ACCEPTED,
        CLOSED,
        OVERFLOW
    }

    /**
     * 保留 Netty 到实体线程顺序的入站消息.
     *
     * @param sequence 队列内单调递增的顺序号
     * @param generation 接收消息时所属的 Window 代际
     * @param packet 领域化后的入站消息
     * @param <T> 消息类型
     */
    public record Entry<T>(long sequence, long generation, @NotNull T packet) {
    }

    private final int capacity;
    private final ArrayDeque<Entry<T>> queue;
    private long nextSequence;
    private boolean closed;
    private boolean overflowed;

    /**
     * 创建容量固定的 FIFO.
     *
     * @param capacity 可暂存的最大消息数量
     * @throws IllegalArgumentException 容量不为正数
     */
    public IncomingPacketQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    /**
     * 从 Netty 线程追加一条入站消息.
     * <p>队列满时不会丢弃已有消息, 而是记录溢出并返回 {@link OfferResult#OVERFLOW}.
     * 调用方可据此关闭已无法安全同步的 Window.
     *
     * @param generation 接收消息时的 Window 代际
     * @param packet 要追加的消息
     * @return 本次入队结果
     */
    public synchronized @NotNull OfferResult offer(long generation, @NotNull T packet) {
        if (this.closed) {
            return OfferResult.CLOSED;
        }
        if (this.queue.size() == this.capacity) {
            this.overflowed = true;
            return OfferResult.OVERFLOW;
        }
        this.queue.addLast(new Entry<>(this.nextSequence++, generation, packet));
        return OfferResult.ACCEPTED;
    }

    /**
     * 按 FIFO 顺序移除至多指定数量的消息.
     *
     * @param limit 本次最多移除的数量
     * @return 不可变的已移除消息列表
     * @throws IllegalArgumentException 上限不为正数
     */
    public synchronized @NotNull List<Entry<T>> drain(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int count = Math.min(limit, this.queue.size());
        ArrayList<Entry<T>> drained = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            drained.add(this.queue.removeFirst());
        }
        return List.copyOf(drained);
    }

    public synchronized int size() {
        return this.queue.size();
    }

    public synchronized boolean isClosed() {
        return this.closed;
    }

    public synchronized boolean hasOverflowed() {
        return this.overflowed;
    }

    /**
     * 关闭队列并丢弃尚未消费的消息.
     */
    @Override
    public synchronized void close() {
        this.closed = true;
        this.queue.clear();
    }
}
