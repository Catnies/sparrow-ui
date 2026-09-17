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
     * 这次播放动哪些槽位, 数组顺序就是 {@link #frame} 拿到的 {@code orderIndex}.
     * <p><strong>播放开始时只读一次, 返回的数组不许改.</strong>
     *
     * @return 槽位数组, 不得包含重复槽位
     */
    int @NotNull [] slots();

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
     * 算出某一格此刻显示什么, 给 {@code null} 表示这一格放行.
     * <p>放行之后轮到更早开始的播放, 再往下才是本宿主的配置层.
     * <p><strong>必须是参数的纯函数</strong>: 同一个 tick 可能被叫零次, 也可能被叫好几次,
     * 不能靠调用次数推进自己的状态. 帧内容也要便宜, 优先返回 {@link ImmediateItemProvider}.
     *
     * @param orderIndex 槽位在 {@link #slots()} 中的序号
     * @param slot 宿主槽位
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @param actual 该显示位的同步可读内容, 没有时为 {@code null}, <strong>只读, 不得修改</strong>
     * @return 此刻的帧; 这一格此刻放行时给 {@code null}
     */
    @Nullable
    ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual);

    /**
     * 帧函数怎么写就怎么播, 这条入口最短.
     * <p>槽位在这里只拷一份, 范围和重复留到 {@link SlotVisual#play} 时按宿主校验.
     *
     * @param slots 参与的宿主槽位, 数组序即 {@code orderIndex}
     * @param periodTicks 帧推进的 tick 周期
     * @param totalTicks 总 tick 数, 负数表示无限播放
     * @param frameFunction 帧函数, 契约见 {@link #frame}
     * @return 动画描述
     * @throws IllegalArgumentException 周期不是正数时
     */
    @NotNull
    static AnimationDefinition of(int @NotNull [] slots, long periodTicks, long totalTicks, @NotNull FrameFunction frameFunction) {
        Objects.requireNonNull(frameFunction, "frameFunction");
        return new FrameFunctionAnimation(slots.clone(), requirePositivePeriod(periodTicks), totalTicks, frameFunction);
    }

    /**
     * 所有槽位同步播这一串帧, 最后一帧走完自己结束.
     *
     * @param slots 参与的宿主槽位
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 每帧一个物品, <strong>创建时逐帧拷贝</strong>
     * @return 动画描述, 总时长 = 帧数 × 周期
     * @throws IllegalArgumentException 周期不是正数, 或者帧序列是空的时
     * @throws ArithmeticException 总时长超出 long 范围时
     */
    @NotNull
    static AnimationDefinition frames(int @NotNull [] slots, long periodTicks, @NotNull List<ItemStack> frames) {
        ImmediateItemProvider[] providers = frameProviders(frames);
        long totalTicks = Math.multiplyExact(requirePositivePeriod(periodTicks), providers.length);
        return new StaggeredFramesAnimation(slots.clone(), periodTicks, 0L, providers, null, totalTicks);
    }

    /**
     * 同 {@link #frames(int[], long, List)}, 槽位用排好的 {@link SlotSequence} 给.
     *
     * @param slots 参与的宿主槽位
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, <strong>创建时逐帧拷贝</strong>
     * @return 动画描述
     * @throws IllegalArgumentException 周期不是正数, 或者帧序列是空的时
     * @throws ArithmeticException 总时长超出 long 范围时
     */
    @NotNull
    static AnimationDefinition frames(@NotNull SlotSequence slots, long periodTicks, @NotNull List<ItemStack> frames) {
        return frames(slots.toArray(), periodTicks, frames);
    }

    /**
     * 逐格出现: 还没轮到的槽位盖着 cover, 轮到了就放行露出真实内容, 最后一格放行时结束.
     *
     * @param order 出现顺序, 第 i 格在 {@code staggerTicks × i} 时放行
     * @param staggerTicks 相邻两格的间隔, 同时就是换帧周期
     * @param cover 轮到前盖住的物品; {@code null} 表示轮到前也放行, 只留一条时间轴
     * @return 动画描述, 总时长 = 间隔 × (格数 − 1)
     * @throws IllegalArgumentException 间隔不是正数时
     * @throws ArithmeticException 总时长超出 long 范围时
     */
    @NotNull
    static AnimationDefinition reveal(int @NotNull [] order, long staggerTicks, @Nullable ItemStack cover) {
        requirePositivePeriod(staggerTicks);
        long totalTicks = order.length == 0 ? 0L : Math.multiplyExact(staggerTicks, order.length - 1);
        return new StaggeredFramesAnimation(order.clone(), staggerTicks, staggerTicks, StaggeredFramesAnimation.NO_FRAMES,
                cover == null ? null : ItemProvider.constant(cover), totalTicks);
    }

    /**
     * 同 {@link #reveal(int[], long, ItemStack)}, 顺序用 {@link SlotSequence} 给.
     *
     * @param order 出现顺序
     * @param staggerTicks 相邻两格的间隔
     * @param cover 轮到前显示的物品, {@code null} 表示放行
     * @return 动画描述
     * @throws IllegalArgumentException 间隔不是正数时
     * @throws ArithmeticException 总时长超出 long 范围时
     */
    @NotNull
    static AnimationDefinition reveal(@NotNull SlotSequence order, long staggerTicks, @Nullable ItemStack cover) {
        return reveal(order.toArray(), staggerTicks, cover);
    }

    /**
     * 逐格错峰播一串帧: 没轮到的盖着 pendingCover, 轮到后按周期走帧, 走完放行, 最后一格走完时结束.
     *
     * @param order 起播顺序, 第 i 格在 {@code staggerTicks × i} 时开始走帧
     * @param staggerTicks 相邻两格的起播间隔, 必须是周期的整数倍; 换帧的失效只在周期边界发出, 对不齐的话阶段切换会迟到
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 每帧一个物品, <strong>创建时逐帧拷贝</strong>
     * @param pendingCover 轮到前盖住槽位的物品, {@code null} 表示轮到前放行
     * @return 动画描述, 总时长 = 间隔 × (格数 − 1) + 帧数 × 周期
     * @throws IllegalArgumentException 周期不是正数, 间隔为负或不是周期的整数倍, 或者帧序列是空的时
     * @throws ArithmeticException 总时长超出 long 范围时
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

    /**
     * 同 {@link #staggeredFrames(int[], long, long, List, ItemStack)}, 顺序用 {@link SlotSequence} 给.
     *
     * @param order 起播顺序
     * @param staggerTicks 相邻两格的起播间隔
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, <strong>创建时逐帧拷贝</strong>
     * @param pendingCover 轮到前显示的物品, {@code null} 表示放行
     * @return 动画描述
     * @throws IllegalArgumentException 周期不是正数, 间隔为负或不是周期的整数倍, 或者帧序列是空的时
     * @throws ArithmeticException 总时长超出 long 范围时
     */
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
     * 所有槽位同步循环播这一串帧, 永远不会自己结束, 只能取消, 或者等宿主关闭.
     *
     * @param slots 参与的宿主槽位
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, 每帧一个物品, <strong>创建时逐帧拷贝</strong>
     * @return 动画描述, 无限时长
     * @throws IllegalArgumentException 周期不是正数, 或者帧序列是空的时
     */
    @NotNull
    static AnimationDefinition loop(int @NotNull [] slots, long periodTicks, @NotNull List<ItemStack> frames) {
        return new LoopFramesAnimation(slots.clone(), requirePositivePeriod(periodTicks), frameProviders(frames));
    }

    /**
     * 同 {@link #loop(int[], long, List)}, 槽位用 {@link SlotSequence} 给.
     *
     * @param slots 参与的宿主槽位
     * @param periodTicks 每帧持续的 tick 数
     * @param frames 帧序列, <strong>创建时逐帧拷贝</strong>
     * @return 无限时长的动画描述
     * @throws IllegalArgumentException 周期不是正数, 或者帧序列是空的时
     */
    @NotNull
    static AnimationDefinition loop(@NotNull SlotSequence slots, long periodTicks, @NotNull List<ItemStack> frames) {
        return loop(slots.toArray(), periodTicks, frames);
    }

    // 周期好几个入口都要, 卡一次放在这里, 调用方拿到的一定是正数
    private static long requirePositivePeriod(long periodTicks) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks 必须为正数: " + periodTicks);
        }
        return periodTicks;
    }

    // 帧物品逐个拷贝再包成即时提供器, 每帧全程共用同一个实例, 渲染层就能靠引用比较直接短路.
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
