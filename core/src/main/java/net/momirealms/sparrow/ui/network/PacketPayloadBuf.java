package net.momirealms.sparrow.ui.network;

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

final class PacketPayloadBuf extends AbstractByteBuf {
    private final ByteBuf frame;
    private final int offset;
    private final ByteBufPacketEvent event;

    PacketPayloadBuf(ByteBuf frame, int offset, ByteBufPacketEvent event) {
        super(frame.maxCapacity() - offset);
        this.frame = frame;
        this.offset = offset;
        this.event = event;
        this.begin(frame.writerIndex() - offset);
    }

    // 每个监听器独立从 payload 起点读, 标记位置也重新建立, 这一步不属于业务修改.
    void begin(int length) {
        super.setIndex(0, length);
        this.markReaderIndex();
        this.markWriterIndex();
    }

    @Override
    public int capacity() {
        return this.frame.capacity() - this.offset;
    }

    @Override
    public ByteBuf capacity(int capacity) {
        this.checkNewCapacity(capacity);
        if (capacity != this.capacity()) {
            this.event.beforeWrite();
            // 扩容与缩容始终包含原帧前缀, payload 下标 0 固定映射到 offset.
            this.frame.capacity(this.offset + capacity);
            this.trimIndicesToCapacity(capacity);
        }
        return this;
    }

    @Override
    public ByteBufAllocator alloc() {
        return this.frame.alloc();
    }

    @Override
    @SuppressWarnings("deprecation")
    public ByteOrder order() {
        return this.frame.order();
    }

    @Override
    @Nullable
    public ByteBuf unwrap() {
        // 共享派生视图最多解包到当前 payload, 原帧与包 ID 前缀由事件持有.
        return null;
    }

    @Override
    public boolean isDirect() {
        return this.frame.isDirect();
    }

    @Override
    public boolean isReadOnly() {
        return this.frame.isReadOnly();
    }

    @Override
    public boolean isWritable() {
        return !this.isReadOnly() && super.isWritable();
    }

    @Override
    public boolean isWritable(int length) {
        return !this.isReadOnly() && super.isWritable(length);
    }

    @Override
    public ByteBuf clear() {
        this.event.beforeWrite();
        return super.clear();
    }

    @Override
    public ByteBuf writerIndex(int writerIndex) {
        if (writerIndex != this.writerIndex()) {
            this.event.beforeWrite();
        }
        return super.writerIndex(writerIndex);
    }

    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) {
        if (writerIndex != this.writerIndex()) {
            this.event.beforeWrite();
        }
        return super.setIndex(readerIndex, writerIndex);
    }

    @Override
    public ByteBuf discardReadBytes() {
        if (this.readerIndex() != 0) {
            this.event.beforeWrite();
        }
        return super.discardReadBytes();
    }

    @Override
    public ByteBuf discardSomeReadBytes() {
        if (this.readerIndex() != 0) {
            this.event.beforeWrite();
        }
        return super.discardSomeReadBytes();
    }

    // AbstractByteBuf 处理相对下标、顺序读取和边界检查, 所有实际字节访问在这里加上帧内偏移.
    @Override
    protected byte _getByte(int index) {
        return this.frame.getByte(this.offset + index);
    }

    @Override
    protected short _getShort(int index) {
        return this.frame.getShort(this.offset + index);
    }

    @Override
    protected short _getShortLE(int index) {
        return this.frame.getShortLE(this.offset + index);
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        return this.frame.getUnsignedMedium(this.offset + index);
    }

    @Override
    protected int _getUnsignedMediumLE(int index) {
        return this.frame.getUnsignedMediumLE(this.offset + index);
    }

    @Override
    protected int _getInt(int index) {
        return this.frame.getInt(this.offset + index);
    }

    @Override
    protected int _getIntLE(int index) {
        return this.frame.getIntLE(this.offset + index);
    }

    @Override
    protected long _getLong(int index) {
        return this.frame.getLong(this.offset + index);
    }

    @Override
    protected long _getLongLE(int index) {
        return this.frame.getLongLE(this.offset + index);
    }

    // 顺序写、绝对写以及 slice/duplicate/order 视图的写入都会经过这些入口.
    @Override
    protected void _setByte(int index, int value) {
        this.event.beforeWrite();
        this.frame.setByte(this.offset + index, value);
    }

    @Override
    protected void _setShort(int index, int value) {
        this.event.beforeWrite();
        this.frame.setShort(this.offset + index, value);
    }

    @Override
    protected void _setShortLE(int index, int value) {
        this.event.beforeWrite();
        this.frame.setShortLE(this.offset + index, value);
    }

    @Override
    protected void _setMedium(int index, int value) {
        this.event.beforeWrite();
        this.frame.setMedium(this.offset + index, value);
    }

    @Override
    protected void _setMediumLE(int index, int value) {
        this.event.beforeWrite();
        this.frame.setMediumLE(this.offset + index, value);
    }

    @Override
    protected void _setInt(int index, int value) {
        this.event.beforeWrite();
        this.frame.setInt(this.offset + index, value);
    }

    @Override
    protected void _setIntLE(int index, int value) {
        this.event.beforeWrite();
        this.frame.setIntLE(this.offset + index, value);
    }

    @Override
    protected void _setLong(int index, long value) {
        this.event.beforeWrite();
        this.frame.setLong(this.offset + index, value);
    }

    @Override
    protected void _setLongLE(int index, long value) {
        this.event.beforeWrite();
        this.frame.setLongLE(this.offset + index, value);
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf target, int targetIndex, int length) {
        this.checkIndex(index, length);
        this.frame.getBytes(this.offset + index, target, targetIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] target, int targetIndex, int length) {
        this.checkIndex(index, length);
        this.frame.getBytes(this.offset + index, target, targetIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer target) {
        this.checkIndex(index, target.remaining());
        this.frame.getBytes(this.offset + index, target);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream target, int length) throws IOException {
        this.checkIndex(index, length);
        this.frame.getBytes(this.offset + index, target, length);
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel target, int length) throws IOException {
        this.checkIndex(index, length);
        return this.frame.getBytes(this.offset + index, target, length);
    }

    @Override
    public int getBytes(int index, FileChannel target, long position, int length) throws IOException {
        this.checkIndex(index, length);
        return this.frame.getBytes(this.offset + index, target, position, length);
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf source, int sourceIndex, int length) {
        this.checkIndex(index, length);
        this.event.beforeWrite();
        this.frame.setBytes(this.offset + index, source, sourceIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] source, int sourceIndex, int length) {
        this.checkIndex(index, length);
        this.event.beforeWrite();
        this.frame.setBytes(this.offset + index, source, sourceIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer source) {
        this.checkIndex(index, source.remaining());
        this.event.beforeWrite();
        this.frame.setBytes(this.offset + index, source);
        return this;
    }

    @Override
    public int setBytes(int index, InputStream source, int length) throws IOException {
        this.checkIndex(index, length);
        // 流读取可能先改写一部分再抛异常, 调用前就登记为写入.
        this.event.beforeWrite();
        return this.frame.setBytes(this.offset + index, source, length);
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel source, int length) throws IOException {
        this.checkIndex(index, length);
        // 流读取可能先改写一部分再抛异常, 调用前就登记为写入.
        this.event.beforeWrite();
        return this.frame.setBytes(this.offset + index, source, length);
    }

    @Override
    public int setBytes(int index, FileChannel source, long position, int length) throws IOException {
        this.checkIndex(index, length);
        // 流读取可能先改写一部分再抛异常, 调用前就登记为写入.
        this.event.beforeWrite();
        return this.frame.setBytes(this.offset + index, source, position, length);
    }

    @Override
    public ByteBuf copy(int index, int length) {
        this.checkIndex(index, length);
        // 显式 copy 的结果拥有独立字节和引用计数, 其修改与当前事件无关.
        return this.frame.copy(this.offset + index, length);
    }

    @Override
    public int nioBufferCount() {
        return this.frame.nioBufferCount();
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        this.checkIndex(index, length);
        // NIO 写入无法回调事件, 因此这里只交出限定 payload 范围的只读视图.
        return this.frame.nioBuffer(this.offset + index, length).asReadOnlyBuffer().order(this.order());
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        return this.nioBuffer(index, length);
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        this.checkIndex(index, length);
        ByteBuffer[] buffers = this.frame.nioBuffers(this.offset + index, length);
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = buffers[i].asReadOnlyBuffer().order(this.order());
        }
        return buffers;
    }

    @Override
    public boolean hasArray() {
        return false;
    }

    @Override
    public byte[] array() {
        throw new UnsupportedOperationException("Payload array is not exposed");
    }

    @Override
    public int arrayOffset() {
        throw new UnsupportedOperationException("Payload array is not exposed");
    }

    @Override
    public boolean hasMemoryAddress() {
        return false;
    }

    @Override
    public long memoryAddress() {
        throw new UnsupportedOperationException("Payload memory address is not exposed");
    }

    @Override
    public int refCnt() {
        return this.frame.refCnt();
    }

    // payload 及其共享派生视图仅在当前回调内借用, 异步保存内容需要显式 copy.
    @Override
    public ByteBuf retain() {
        throw new UnsupportedOperationException("Borrowed payload cannot be retained");
    }

    @Override
    public ByteBuf retain(int increment) {
        throw new UnsupportedOperationException("Borrowed payload cannot be retained");
    }

    @Override
    public boolean release() {
        throw new UnsupportedOperationException("Borrowed payload cannot be released");
    }

    @Override
    public boolean release(int decrement) {
        throw new UnsupportedOperationException("Borrowed payload cannot be released");
    }

    @Override
    public ByteBuf touch() {
        this.frame.touch();
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        this.frame.touch(hint);
        return this;
    }
}
