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
 * 按槽位配置逐槽与全局两层视觉映射, 逐槽映射优先, {@code null} 结果继续向下一层求值.
 */
public interface SlotVisual extends Visual {

    /**
     * 返回当前全局视觉映射.
     *
     * @return 全局视觉映射, 未设置时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 替换全局视觉映射并标脏全部槽位.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示调用方给出的内容
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 替换不带占位的全局视觉映射.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换全局视觉映射.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 返回一个槽位的显式视觉映射.
     *
     * @param slot 宿主槽位
     * @return 逐槽视觉映射, 没有设置过时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot);

    /**
     * 替换一个槽位的视觉映射并只标脏该槽位.
     *
     * @param slot 宿主槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示调用方给出的内容
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    void setVisualizerProvider(
            int slot,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 替换一个槽位不带占位的视觉映射.
     *
     * @param slot 宿主槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(slot, visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换一个槽位的视觉映射.
     *
     * @param slot 宿主槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(slot, VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 播放一个动画, 参与的槽位在播放期间优先显示动画给出的帧, 盖过本宿主的逐槽与全局映射,
     * 结束或取消后自动恢复. 同时播放多个动画时后开始的优先, 帧放行处露出更早开始的动画.
     * <p>帧按描述的周期随服务器 tick 推进, 时长走完自动结束, 没有观看者不暂停时间轴.
     * <p>起播时刻对齐到该周期的共享节拍, 同周期的动画因此换帧同步. 首帧最多比其余帧短一个周期.
     * <p>空槽位序列的播放立即以 {@link AnimationHandle.FinishReason#COMPLETED} 结束.
     *
     * @param animationDefinition 动画描述, 槽位使用本宿主的坐标系
     * @return 这次播放的控制句柄
     * @throws IndexOutOfBoundsException 当动画槽位超出宿主范围时
     * @throws IllegalArgumentException 当动画槽位重复或周期不是正数时
     */
    @NotNull
    AnimationHandle play(@NotNull AnimationDefinition animationDefinition);

    /**
     * 求值一个槽位的显示, 播放中的动画最优先, 其后是逐槽与全局两层视觉映射.
     * <p>{@code actual} 由调用方提供, 渲染层用它避免重复读取, 映射按约定只读.
     *
     * @param slot 宿主槽位
     * @param actual 本轮读取到的实际内容, 空槽为 {@code null}
     * @return 命中的视觉结果, 全部放行时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    @Nullable
    @ApiStatus.Internal
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual);

    /**
     * 挂一条这个槽位的视觉失效订阅.
     * <p>订阅表只弱持有回执, 调用方丢掉回执即等于退订.
     *
     * @param slot 宿主槽位
     * @param invalidator 视觉失效时执行的回调
     * @return 必须由调用方持有的订阅回执
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    @NotNull
    @ApiStatus.Internal
    Subscription attach(int slot, @NotNull Runnable invalidator);
}
