package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Netty 生产, 玩家实体线程消费的有界 FIFO.
 * <p>Window 在实体 tick 批量取走消息, 固定容量限制单次会话的积压并报告溢出.
 *
 * @param <T> 入站消息类型
 */
final class IncomingPacketQueue<T> implements AutoCloseable {

    public enum OfferResult {
        ACCEPTED,
        CLOSED,
        OVERFLOW
    }

    public record Entry<T>(long sequence, long generation, @NotNull T packet) {
    }

    private final int capacity;
    private final Object[] packets;
    private final long[] sequences;
    private final long[] generations;
    private int head;
    private int size;
    private long nextSequence;
    private boolean closed;
    private boolean overflowed;

    public IncomingPacketQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.packets = new Object[capacity];
        this.sequences = new long[capacity];
        this.generations = new long[capacity];
    }

    // 满队列保留已有消息并记录溢出, Window 随后会关闭这次会话.
    @NotNull
    public synchronized OfferResult offer(long generation, @NotNull T packet) {
        if (this.closed) {
            return OfferResult.CLOSED;
        }
        if (this.size == this.capacity) {
            this.overflowed = true;
            return OfferResult.OVERFLOW;
        }
        int tail = this.head + this.size;
        if (tail >= this.capacity) {
            tail -= this.capacity;
        }
        this.packets[tail] = packet;
        this.sequences[tail] = this.nextSequence++;
        this.generations[tail] = generation;
        this.size++;
        return OfferResult.ACCEPTED;
    }

    // 按 FIFO 顺序取走至多 limit 条消息.
    @NotNull
    public synchronized List<Entry<T>> drain(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int count = Math.min(limit, this.size);
        if (count == 0) {
            return List.of();
        }
        ArrayList<Entry<T>> drained = new ArrayList<>(count);
        int index = this.head;
        for (int offset = 0; offset < count; offset++) {
            @SuppressWarnings("unchecked") T packet = (T) this.packets[index];
            drained.add(new Entry<>(this.sequences[index], this.generations[index], packet));
            this.packets[index] = null;
            index = this.nextIndex(index);
        }
        this.finishDrain(index, count);
        return List.copyOf(drained);
    }

    // 旧代际消息照常出队, 只有当前代际的负载会交给菜单.
    @NotNull
    public synchronized List<T> drain(long generation, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int count = Math.min(limit, this.size);
        if (count == 0) {
            return List.of();
        }
        ArrayList<T> drained = new ArrayList<>(count);
        int index = this.head;
        for (int offset = 0; offset < count; offset++) {
            @SuppressWarnings("unchecked") T packet = (T) this.packets[index];
            if (this.generations[index] == generation) {
                drained.add(packet);
            }
            this.packets[index] = null;
            index = this.nextIndex(index);
        }
        this.finishDrain(index, count);
        return drained.isEmpty() ? List.of() : List.copyOf(drained);
    }

    public synchronized int size() {
        return this.size;
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
        Arrays.fill(this.packets, null);
        this.head = 0;
        this.size = 0;
    }

    private int nextIndex(int index) {
        int next = index + 1;
        return next == this.capacity ? 0 : next;
    }

    private void finishDrain(int nextHead, int count) {
        this.size -= count;
        this.head = this.size == 0 ? 0 : nextHead;
    }
}
