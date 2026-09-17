package net.momirealms.sparrow.ui.pane;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntConsumer;

/**
 * 内置的几种见 {@link SlotPatterns}.
 */
@FunctionalInterface
public interface SlotPattern {

    /**
     * 挑出要用的槽位, 按结果顺序交给 {@code output}.
     *
     * <p>可以跳过候选里的某些槽位, 也可以改顺序, 但只能输出候选里有的槽位, 而且不能重复.
     * {@code output} 只在这次调用期间有效, 方法返回之后就别再碰它了.
     *
     * @param candidates 可以选的槽位
     * @param output 接收选中的槽位
     */
    void emit(@NotNull SlotSequence candidates, @NotNull IntConsumer output);
}
