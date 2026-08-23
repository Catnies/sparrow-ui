package net.momirealms.sparrow.ui.visual.animation;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.window.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 描述 Window 标题动画的时间轴与帧内容.
 */
public interface TitleAnimationDefinition {

    /**
     * 帧推进的 tick 周期, 播放期间每隔这么多 tick 换一帧.
     *
     * @return 正数, 单位 tick
     */
    long periodTicks();

    /**
     * 从开始到结束的总 tick 数, 到点后播放自动结束.
     *
     * @return 总 tick 数, 负数表示无限播放
     */
    long totalTicks();

    /**
     * 求值此刻的标题帧, 返回 {@code null} 表示此刻放行, 交给更早开始的播放或配置标题.
     * <p><strong>必须是参数的纯函数</strong>, 同一 tick 可能被调用零次或多次, 不得依赖调用次数推进内部状态.
     * 帧应当廉价, {@link Component} 不可变, 固定帧序列应在构造时定死并逐帧复用.
     *
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @return 此刻的标题帧, {@code null} 表示此刻放行
     */
    @Nullable
    Component frame(long elapsedTicks);

    /**
     * 用帧函数直接组成标题动画.
     *
     * @param periodTicks 帧推进的 tick 周期
     * @param totalTicks 总 tick 数, 负数表示无限播放
     * @param frameFunction 帧函数, 契约见 {@link #frame}
     * @return 标题动画描述, 经 {@link Window#playTitleAnimation} 播放
     * @throws IllegalArgumentException 当周期不是正数时
     */
    @NotNull
    static TitleAnimationDefinition of(long periodTicks, long totalTicks, @NotNull TitleFrameFunction frameFunction) {
        Objects.requireNonNull(frameFunction, "frameFunction");
        return new TitleFrameFunctionAnimation(requirePositivePeriod(periodTicks), totalTicks, frameFunction);
    }

    /**
     * 按帧序列依次播放, 走完最后一帧自动结束.
     *
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 创建时定死
     * @return 标题动画描述, 总时长 = 帧数 × 周期
     * @throws IllegalArgumentException 当周期不是正数或帧序列为空时
     * @throws ArithmeticException 当总时长超出 long 范围时
     */
    @NotNull
    static TitleAnimationDefinition frames(long periodTicks, @NotNull List<Component> frames) {
        Component[] sequence = frameSequence(frames);
        long totalTicks = Math.multiplyExact(requirePositivePeriod(periodTicks), sequence.length);
        return new TitleFrameFunctionAnimation(periodTicks, totalTicks, elapsedTicks -> {
            long index = elapsedTicks / periodTicks;
            return index < sequence.length ? sequence[(int) index] : null;
        });
    }

    /**
     * 帧序列取模无限循环, 不会自然结束, 只能被取消或随窗口关闭结束.
     *
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 创建时定死
     * @return 标题动画描述, 无限时长
     * @throws IllegalArgumentException 当周期不是正数或帧序列为空时
     */
    @NotNull
    static TitleAnimationDefinition loop(long periodTicks, @NotNull List<Component> frames) {
        Component[] sequence = frameSequence(frames);
        requirePositivePeriod(periodTicks);
        return new TitleFrameFunctionAnimation(periodTicks, -1L, elapsedTicks -> sequence[(int) (elapsedTicks / periodTicks % sequence.length)]);
    }

    private static long requirePositivePeriod(long periodTicks) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks 必须为正数: " + periodTicks);
        }
        return periodTicks;
    }

    // 固定帧复用不可变 Component, null 保留给帧函数表达放行
    private static Component @NotNull [] frameSequence(@NotNull List<Component> frames) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames 不能为空");
        }
        Component[] sequence = frames.toArray(new Component[0]);
        for (int index = 0; index < sequence.length; index++) {
            Objects.requireNonNull(sequence[index], "frame");
        }
        return sequence;
    }
}
