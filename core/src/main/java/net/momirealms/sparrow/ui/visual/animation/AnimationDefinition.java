package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.visual.SlotVisual;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public interface AnimationDefinition {

    /**
     * 参与播放的槽位, 数组序就是 {@link #frame} 收到的 {@code orderIndex}.
     * <p><strong>播放开始时读取一次, 返回的数组不得修改.</strong>
     *
     * @return 槽位数组, 不得包含重复槽位
     */
    int @NotNull [] slots();

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
     * 求值一个参与槽位此刻的显示, 返回 {@code null} 表示这一槽此刻放行, 交给更早开始的播放或本宿主的配置层.
     * <p><strong>必须是参数的纯函数</strong>, 同一 tick 可能被调用零次或多次, 不得依赖调用次数推进内部状态.
     * 帧内容应当廉价, 优先返回 {@link ImmediateItemProvider}.
     *
     * @param orderIndex 槽位在 {@link #slots()} 中的序号
     * @param slot 宿主槽位
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @param actual 该显示位的同步可读内容, 没有时为 {@code null}, <strong>只读, 不得修改</strong>
     * @return 此刻的帧, {@code null} 表示这一槽此刻放行
     */
    @Nullable
    ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual);

    /**
     * 用帧函数直接组成动画.
     * <p>槽位只在这里拷贝一份, 范围与重复留到 {@link SlotVisual#play} 时按宿主校验.
     *
     * @param slots 参与的宿主槽位, 数组序即 {@code orderIndex}
     * @param periodTicks 帧推进的 tick 周期
     * @param totalTicks 总 tick 数, 负数表示无限播放
     * @param frameFunction 帧函数, 契约见 {@link #frame}
     * @return 动画描述
     * @throws IllegalArgumentException 当周期不是正数时
     */
    @NotNull
    static AnimationDefinition of(int @NotNull [] slots, long periodTicks, long totalTicks, @NotNull FrameFunction frameFunction) {
        Objects.requireNonNull(frameFunction, "frameFunction");
        return new FrameFunctionAnimation(slots.clone(), requirePositivePeriod(periodTicks), totalTicks, frameFunction);
    }

    /**
     * 全部槽位同步播放同一帧序列, 走完最后一帧自动结束.
     *
     * @param slots 参与的宿主槽位
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 每帧一个物品, 创建时逐帧拷贝
     * @return 动画描述, 总时长 = 帧数 × 周期
     * @throws IllegalArgumentException 当周期不是正数或帧序列为空时
     */
    @NotNull
    static AnimationDefinition frames(int @NotNull [] slots, long periodTicks, @NotNull List<ItemStack> frames) {
        ImmediateItemProvider[] providers = frameProviders(frames);
        long totalTicks = Math.multiplyExact(requirePositivePeriod(periodTicks), providers.length);
        return new StaggeredFramesAnimation(slots.clone(), periodTicks, 0L, providers, null, totalTicks);
    }

    @NotNull
    static AnimationDefinition frames(@NotNull SlotSequence slots, long periodTicks, @NotNull List<ItemStack> frames) {
        return frames(slots.toArray(), periodTicks, frames);
    }

    /**
     * 逐格出现, 每槽未轮到时显示 cover, 轮到即放行显示真实内容, 最后一槽放行时结束.
     *
     * @param order 出现顺序, 第 i 个槽位在 staggerTicks × i 时放行
     * @param staggerTicks 相邻两槽的出现间隔, 也是帧推进周期
     * @param cover 轮到前盖住槽位的物品, {@code null} 表示轮到前也放行(只保留时间轴)
     * @return 动画描述, 总时长 = 间隔 × (槽数 − 1)
     * @throws IllegalArgumentException 当间隔不是正数时
     */
    @NotNull
    static AnimationDefinition reveal(int @NotNull [] order, long staggerTicks, @Nullable ItemStack cover) {
        requirePositivePeriod(staggerTicks);
        long totalTicks = order.length == 0 ? 0L : Math.multiplyExact(staggerTicks, order.length - 1);
        return new StaggeredFramesAnimation(order.clone(), staggerTicks, staggerTicks, StaggeredFramesAnimation.NO_FRAMES,
                cover == null ? null : ItemProvider.constant(cover), totalTicks);
    }

    @NotNull
    static AnimationDefinition reveal(@NotNull SlotSequence order, long staggerTicks, @Nullable ItemStack cover) {
        return reveal(order.toArray(), staggerTicks, cover);
    }

    /**
     * 逐槽错峰的帧序列, 每槽未轮到时显示 pendingCover, 轮到后按周期走帧, 走完放行, 最后一槽走完时结束.
     *
     * @param order 起播顺序, 第 i 个槽位在 staggerTicks × i 时开始走帧
     * @param staggerTicks 相邻两槽的起播间隔, 必须是周期的整数倍(帧推进的失效只在周期边界发出, 不对齐会让阶段切换显示迟到)
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 每帧一个物品, 创建时逐帧拷贝
     * @param pendingCover 轮到前盖住槽位的物品, {@code null} 表示轮到前放行
     * @return 动画描述, 总时长 = 间隔 × (槽数 − 1) + 帧数 × 周期
     * @throws IllegalArgumentException 当周期不是正数, 间隔为负数或不是周期的整数倍, 或帧序列为空时
     */
    @NotNull
    static AnimationDefinition staggeredFrames(
            int @NotNull [] order,
            long staggerTicks,
            long periodTicks,
            @NotNull List<ItemStack> frames,
            @Nullable ItemStack pendingCover
    ) {
        requirePositivePeriod(periodTicks);
        if (staggerTicks < 0) {
            throw new IllegalArgumentException("staggerTicks 不能为负数: " + staggerTicks);
        }
        if (staggerTicks % periodTicks != 0) {
            throw new IllegalArgumentException("staggerTicks 必须是 periodTicks 的整数倍: " + staggerTicks + " % " + periodTicks);
        }
        ImmediateItemProvider[] providers = frameProviders(frames);
        long playingTicks = Math.multiplyExact(periodTicks, providers.length);
        long totalTicks = order.length == 0 ? 0L : Math.addExact(Math.multiplyExact(staggerTicks, order.length - 1), playingTicks);
        return new StaggeredFramesAnimation(order.clone(), periodTicks, staggerTicks, providers,
                pendingCover == null ? null : ItemProvider.constant(pendingCover), totalTicks);
    }

    @NotNull
    static AnimationDefinition staggeredFrames(
            @NotNull SlotSequence order,
            long staggerTicks,
            long periodTicks,
            @NotNull List<ItemStack> frames,
            @Nullable ItemStack pendingCover
    ) {
        return staggeredFrames(order.toArray(), staggerTicks, periodTicks, frames, pendingCover);
    }

    /**
     * 全部槽位同步循环播放帧序列, 不会自然结束, 只能被取消或随宿主关闭结束.
     *
     * @param slots 参与的宿主槽位
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 每帧一个物品, 创建时逐帧拷贝
     * @return 动画描述, 无限时长
     * @throws IllegalArgumentException 当周期不是正数或帧序列为空时
     */
    @NotNull
    static AnimationDefinition loop(int @NotNull [] slots, long periodTicks, @NotNull List<ItemStack> frames) {
        return new LoopFramesAnimation(slots.clone(), requirePositivePeriod(periodTicks), frameProviders(frames));
    }

    @NotNull
    static AnimationDefinition loop(@NotNull SlotSequence slots, long periodTicks, @NotNull List<ItemStack> frames) {
        return loop(slots.toArray(), periodTicks, frames);
    }

    private static long requirePositivePeriod(long periodTicks) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks 必须为正数: " + periodTicks);
        }
        return periodTicks;
    }

    // 把帧物品逐个拷贝并包成即时提供器, 同一帧全程共用同一个实例, 渲染层据此按引用短路比较.
    private static ImmediateItemProvider @NotNull [] frameProviders(@NotNull List<ItemStack> frames) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames 不能为空");
        }
        ImmediateItemProvider[] providers = new ImmediateItemProvider[frames.size()];
        for (int index = 0; index < providers.length; index++) {
            providers[index] = ItemProvider.constant(frames.get(index));
        }
        return providers;
    }
}
