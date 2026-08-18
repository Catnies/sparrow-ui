package net.momirealms.sparrow.ui.util;

import java.util.BitSet;
import java.util.stream.IntStream;

public class UnmodifiableBitSet extends BitSet {

    private final BitSet delegate;

    public UnmodifiableBitSet(BitSet bitSet) {
        if (bitSet == null) {
            throw new NullPointerException("bitSet must not be null");
        }
        this.delegate = bitSet;
    }

    // 读操作全部进行代理

    @Override
    public boolean get(int bitIndex) {
        return delegate.get(bitIndex);
    }

    @Override
    public BitSet get(int fromIndex, int toIndex) {
        return delegate.get(fromIndex, toIndex);
    }

    @Override
    public int cardinality() {
        return delegate.cardinality();
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public int nextSetBit(int fromIndex) {
        return delegate.nextSetBit(fromIndex);
    }

    @Override
    public int nextClearBit(int fromIndex) {
        return delegate.nextClearBit(fromIndex);
    }

    @Override
    public int previousSetBit(int fromIndex) {
        return delegate.previousSetBit(fromIndex);
    }

    @Override
    public int previousClearBit(int fromIndex) {
        return delegate.previousClearBit(fromIndex);
    }

    @Override
    public boolean intersects(BitSet set) {
        return delegate.intersects(set);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public byte[] toByteArray() {
        return delegate.toByteArray();
    }

    @Override
    public long[] toLongArray() {
        return delegate.toLongArray();
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    @Override
    public Object clone() {
        return delegate.clone();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public IntStream stream() {
        return delegate.stream();
    }

    // 拒绝全部写操作

    @Override
    public void set(int bitIndex) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void set(int bitIndex, boolean value) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void set(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void set(int fromIndex, int toIndex, boolean value) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void clear(int bitIndex) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void clear(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void flip(int bitIndex) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void flip(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void and(BitSet set) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void or(BitSet set) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void xor(BitSet set) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }

    @Override
    public void andNot(BitSet set) {
        throw new UnsupportedOperationException("UnmodifiableBitSet is immutable");
    }
}
