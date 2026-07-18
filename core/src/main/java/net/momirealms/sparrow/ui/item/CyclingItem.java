package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * 根据服务器当前 tick 在多个 ItemProvider 之间轮换的被动 Item.
 *
 * <p>该类型不保存递增帧状态, 也不创建调度任务. Window 在自己的单一 tick 任务中
 * 根据 {@link #updatePeriodTicks()} 决定何时重新渲染.</p>
 */
public final class CyclingItem implements PeriodicItem {
    private final int periodTicks;
    private final List<ItemProvider> frames;
    private final LongSupplier tickSource;
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;
    private final ItemProvider renderingProvider;

    /**
     * 使用服务器当前 tick 创建轮播 Item.
     *
     * @param periodTicks 帧切换周期，必须为正数
     * @param frames 非空、不可含 null 的帧列表
     */
    public CyclingItem(int periodTicks, List<? extends ItemProvider> frames) {
        this(periodTicks, frames, Bukkit::getCurrentTick, (_, _) -> { });
    }

    /**
     * 使用服务器当前 tick 创建带点击处理器的轮播 Item.
     *
     * @param periodTicks 帧切换周期，必须为正数
     * @param frames 非空、不可含 null 的帧列表
     * @param clickHandler 点击处理器
     */
    public CyclingItem(
            int periodTicks,
            List<? extends ItemProvider> frames,
            BiConsumer<? super Item, ? super ItemClick> clickHandler
    ) {
        this(periodTicks, frames, Bukkit::getCurrentTick, clickHandler);
    }

    CyclingItem(
            int periodTicks,
            List<? extends ItemProvider> frames,
            LongSupplier tickSource,
            BiConsumer<? super Item, ? super ItemClick> clickHandler
    ) {
        if (periodTicks <= 0) throw new IllegalArgumentException("periodTicks must be positive");
        this.frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        if (this.frames.isEmpty()) throw new IllegalArgumentException("frames must not be empty");

        this.periodTicks = periodTicks;
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.renderingProvider = context -> this.frames.get(this.frameIndex()).provide(context);
    }

    @Override
    public ItemProvider getItemProvider() {
        return renderingProvider;
    }

    @Override
    public void handleClick(ItemClick click) {
        clickHandler.accept(this, Objects.requireNonNull(click, "click"));
    }

    /**
     * 获取配置的帧切换周期.
     *
     * @return 正数 tick 周期
     */
    public int periodTicks() {
        return periodTicks;
    }

    @Override
    public int updatePeriodTicks() {
        return frames.size() > 1 ? periodTicks : NO_PERIODIC_UPDATE;
    }

    /**
     * 获取构建后不可变的帧数量.
     *
     * @return 帧数量
     */
    public int frameCount() {
        return frames.size();
    }

    /**
     * 根据当前 tick 计算帧下标.
     *
     * @return 当前帧下标
     */
    public int frameIndex() {
        long frame = Math.floorDiv(tickSource.getAsLong(), periodTicks);
        return (int) Math.floorMod(frame, frames.size());
    }
}
