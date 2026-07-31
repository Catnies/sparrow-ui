package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

@FunctionalInterface
public interface SlotPattern {

    /**
     * 决定如何从候选槽位中选出一组槽位, 以及选中槽位的顺序.
     * 选择槽位并按结果顺序传给 {@code output}.
     * 可以跳过某些候选槽位, 也可以改变顺序, 但不能输出非候选槽位或重复槽位.
     *
     * @param candidates 可以选择的槽位
     * @param output 接收选中槽位, 只能在本方法返回前调用
     */
    void emit(@NotNull SlotSequence candidates, @NotNull IntConsumer output);
}
