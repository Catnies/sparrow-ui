package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * 按槽位配两层视觉映射: 逐槽那层先看, 放行再问全局那层, 都放行就轮到调用方自己的内容.
 */
public interface SlotVisual extends Visual {

    /**
     * 当前的全局视觉映射.
     *
     * @return 全局视觉映射; 没设时为 null
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 换掉全局视觉映射, 并把宿主所有槽位标脏.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示调用方给出的内容
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 同 {@link #setVisualizerProvider(Function, ImmediateItemProvider)}, 不带占位.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 用直接返回 ItemStack 的映射当全局视觉映射.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 这一格自己的视觉映射, 不含全局那层.
     *
     * @param slot 宿主槽位
     * @return 逐槽视觉映射; 没设过时为 null
     * @throws IndexOutOfBoundsException 槽号超出宿主范围时
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot);

    /**
     * 换掉这一格的视觉映射, 只把这一格标脏.
     *
     * @param slot 宿主槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示调用方给出的内容
     * @throws IndexOutOfBoundsException 槽号超出宿主范围时
     */
    void setVisualizerProvider(
            int slot,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 同 {@link #setVisualizerProvider(int, Function, ImmediateItemProvider)}, 不带占位.
     *
     * @param slot 宿主槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 槽号超出宿主范围时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(slot, visualizerProvider, null);
    }

    /**
     * 用直接返回 ItemStack 的映射当这一格的视觉映射.
     *
     * @param slot 宿主槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 槽号超出宿主范围时
     */
    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(slot, VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 播一个动画, 参与的槽位在播放期间显示动画给出的帧.
     * <p>帧盖过本宿主的逐槽和全局映射, 播完或者被取消就自动恢复. 同时播多个时后开始的盖在上面,
     * 某格放行的地方露出更早开始的播放.
     * <p>帧跟着描述的周期随服务器 tick 推进, 时长走完自己结束; 没有观看者也不会把时间轴停下来.
     * 起播时刻会对齐到该周期的共享节拍, 所以同周期的动画换帧是同步的, 代价是首帧最多比其余帧短一个周期.
     * <p>槽位序列是空的时候, 这次播放立刻以 {@link AnimationHandle.FinishReason#COMPLETED} 结束.
     *
     * @param animationDefinition 动画描述, 槽位使用本宿主的坐标系
     * @return 这次播放的控制句柄
     * @throws IndexOutOfBoundsException 动画槽位超出宿主范围时
     * @throws IllegalArgumentException 动画槽位有重复, 或者周期不是正数时
     */
    @NotNull
    AnimationHandle play(@NotNull AnimationDefinition animationDefinition);

    /**
     * 算出一格现在该显示什么: 正在播的动画最优先, 然后是逐槽映射, 最后是全局映射.
     * <p>{@code actual} 由调用方给, 渲染层借此少读一次; 映射按约定只读它.
     *
     * @param slot 宿主槽位
     * @param actual 本轮读取到的实际内容, 空槽为 {@code null}
     * @return 命中的视觉结果, 全部放行时为 {@code null}
     * @throws IndexOutOfBoundsException 槽号超出宿主范围时
     */
    @Nullable
    @ApiStatus.Internal
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual);

    /**
     * 订阅这一格的视觉失效.
     * <p>订阅表只弱持有回执, 所以调用方把回执丢了就等于退订.
     *
     * @param slot 宿主槽位
     * @param invalidator 视觉失效时执行的回调
     * @return 必须由调用方持有的订阅回执
     * @throws IndexOutOfBoundsException 槽号超出宿主范围时
     */
    @NotNull
    @ApiStatus.Internal
    Subscription attach(int slot, @NotNull Runnable invalidator);
}
