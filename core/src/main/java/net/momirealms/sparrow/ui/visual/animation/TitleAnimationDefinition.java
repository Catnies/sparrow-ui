package net.momirealms.sparrow.ui.visual.animation;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.window.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Window 标题动画的描述: 时间轴加上每一刻该显示什么.
 * <p>描述自己不带播放状态, 同一份可以拿去播多次; 播放入口是 {@link Window#playTitleAnimation}.
 */
public interface TitleAnimationDefinition {

    /**
     * 每隔这么多 tick 推进一帧.
     *
     * @return 正数, 单位 tick
     */
    long periodTicks();

    /**
     * 这条时间轴一共多长, 到点播放自己结束.
     *
     * @return 总 tick 数; 负数表示无限播
     */
    long totalTicks();

    /**
     * 算出此刻的标题帧, 给 {@code null} 表示此刻放行.
     * <p>放行之后轮到更早开始的播放, 再往下才是配置标题.
     * <p><strong>必须是参数的纯函数</strong>: 同一个 tick 可能被叫零次, 也可能被叫好几次,
     * 不能靠调用次数推进自己的状态. 帧要便宜, {@link Component} 本身不可变, 固定的帧序列应该在构造时定死再逐帧复用.
     *
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @return 此刻的标题帧; 此刻放行时给 {@code null}
     */
    @Nullable
    Component frame(long elapsedTicks);

    /**
     * 帧函数怎么写就怎么播, 这条入口最短.
     *
     * @param periodTicks 帧推进的 tick 周期
     * @param totalTicks 总 tick 数, 负数表示无限播放
     * @param frameFunction 帧函数, 契约见 {@link #frame}
     * @return 标题动画描述, 经 {@link Window#playTitleAnimation} 播放
     * @throws IllegalArgumentException 周期不是正数时
     */
    @NotNull
    static TitleAnimationDefinition of(long periodTicks, long totalTicks, @NotNull TitleFrameFunction frameFunction) {
        Objects.requireNonNull(frameFunction, "frameFunction");
        return new TitleFrameFunctionAnimation(requirePositivePeriod(periodTicks), totalTicks, frameFunction);
    }

    /**
     * 按帧序列一帧帧播过去, 最后一帧走完自己结束.
     *
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 创建时定死
     * @return 标题动画描述, 总时长 = 帧数 × 周期
     * @throws IllegalArgumentException 周期不是正数, 或者帧序列是空的时
     * @throws ArithmeticException 总时长超出 long 范围时
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
     * 帧序列取模循环, 永远播不完, 只能取消或者等窗口关闭.
     *
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 创建时定死
     * @return 标题动画描述, 无限时长
     * @throws IllegalArgumentException 周期不是正数, 或者帧序列是空的时
     */
    @NotNull
    static TitleAnimationDefinition loop(long periodTicks, @NotNull List<Component> frames) {
        Component[] sequence = frameSequence(frames);
        requirePositivePeriod(periodTicks);
        return new TitleFrameFunctionAnimation(periodTicks, -1L, elapsedTicks -> sequence[(int) (elapsedTicks / periodTicks % sequence.length)]);
    }

    // 周期好几个入口都要, 卡一次放在这里, 调用方拿到的一定是正数
    private static long requirePositivePeriod(long periodTicks) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks 必须为正数: " + periodTicks);
        }
        return periodTicks;
    }

    // 固定帧直接复用这些不可变 Component; null 留给帧函数去表达放行, 所以这里逐个查空
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
