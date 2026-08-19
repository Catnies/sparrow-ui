package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.visual.SlotVisual;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
     * 求值一个参与槽位此刻的显示, 返回 {@code null} 表示当前槽位不存在动画帧.
     * <p><strong>必须是参数的纯函数</strong>, 同一 tick 可能被调用零次或多次, 不得依赖调用次数推进内部状态.
     * 帧内容应当廉价, 优先返回 {@link ImmediateItemProvider}.
     *
     * @param orderIndex 槽位在 {@link #slots()} 中的序号
     * @param slot 宿主槽位
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @param actual 该显示位的同步可读内容, 没有时为 {@code null}, <strong>只读, 不得修改</strong>
     * @return 此刻的帧, {@code null} 表示当前槽位不存在动画帧.
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
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks 必须为正数: " + periodTicks);
        }
        Objects.requireNonNull(frameFunction, "frameFunction");
        return new FrameFunctionAnimation(slots.clone(), periodTicks, totalTicks, frameFunction);
    }
}
