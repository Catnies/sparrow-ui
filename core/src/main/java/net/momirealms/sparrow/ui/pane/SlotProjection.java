package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 把一个 {@link Signal} 给出的序列持续写进某个 Pane 的一段槽位.
 * <p>创建入口返回之前第一轮就已经跑完了, 所以建出来当场就能看到内容; 这个对象自己只是给使用方提前停投影用的句柄.
 */
public final class SlotProjection implements AutoCloseable {
    // 默认执行器: 把重算丢到 Paper 的全局异步调度器上, 序列的派生函数因此只能读异步域里安全的数据
    private static final Executor ASYNC = command -> Bukkit.getAsyncScheduler().runNow(
            SparrowUI.getInstance().getPlugin(),
            ignoredTask -> command.run()
    );

    private final Pane pane;
    private final SlotSequence slots;
    private final Signal<? extends List<?>> source;
    private final Function<Object, ? extends Element> toElement;
    private final Executor executor;

    // 失效到达和求值轮次靠它对齐: 谁在跑, 谁在排队, 都记在这里
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SCHEDULED);
    private final Subscription binding;     // 这条信号上的订阅, 停投影时顺手摘掉
    private volatile boolean closed;

    /**
     * 把序列投影到选中槽位, 之后每次失效都在 Paper 全局异步调度器上重算.
     *
     * @param pane 接收写入的 Pane
     * @param slots 这次投影负责的槽位, <strong>必须属于这个 Pane</strong>
     * @param source 序列来源
     * @param toElement 把序列里的一条数据变成 Element, <strong>不能返回 null</strong>
     * @return 投影句柄, 可以拿它提前停掉这次投影
     */
    @NotNull
    static <T> SlotProjection attach(
            @NotNull Pane pane,
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement
    ) {
        return attach(pane, slots, source, toElement, ASYNC);
    }

    // 第一轮照样在调用线程跑, 之后的失效才换到 executor 上.
    @NotNull
    static <T> SlotProjection attach(
            @NotNull Pane pane,
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends T>> source,
            @NotNull Function<? super T, ? extends Element> toElement,
            @NotNull Executor executor
    ) {
        Objects.requireNonNull(toElement, "toElement");
        @SuppressWarnings("unchecked")
        Function<Object, ? extends Element> erased = (Function<Object, ? extends Element>) toElement;
        return create(pane, slots, source, erased, executor);
    }

    /**
     * 序列里已经是 Element 时用它, 省一个转换函数.
     *
     * @param pane 接收写入的 Pane
     * @param slots 这次投影负责的槽位, <strong>必须属于这个 Pane</strong>
     * @param source 序列来源
     * @return 投影句柄, 可以拿它提前停掉这次投影
     */
    @NotNull
    static SlotProjection attachElements(
            @NotNull Pane pane,
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source
    ) {
        return attachElements(pane, slots, source, ASYNC);
    }

    // 第一轮照样在调用线程跑, 之后的失效才换到 executor 上.
    @NotNull
    static SlotProjection attachElements(
            @NotNull Pane pane,
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source,
            @NotNull Executor executor
    ) {
        return create(pane, slots, source, value -> (Element) value, executor);
    }

    static Executor defaultExecutor() {
        return ASYNC;
    }

    // 落地入口: 尺寸对不上当场失败, 建好之后立刻跑第一轮.
    static SlotProjection create(
            Pane pane,
            SlotSequence slots,
            Signal<? extends List<?>> source,
            Function<Object, ? extends Element> toElement,
            Executor executor
    ) {
        if (!pane.size().equals(slots.paneSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.paneSize() + ", expected " + pane.size());
        }
        SlotProjection projection = new SlotProjection(pane, slots, source, toElement, executor);
        // 第一轮就在调用线程上跑完, executor 只负责后面失效触发的重算
        projection.runRound();
        return projection;
    }

    private SlotProjection(
            Pane pane,
            SlotSequence slots,
            Signal<? extends List<?>> source,
            Function<Object, ? extends Element> toElement,
            Executor executor
    ) {
        this.pane = pane;
        this.slots = slots;
        this.source = source;
        this.toElement = toElement;
        this.executor = Objects.requireNonNull(executor, "executor");
        // 订阅声明挂在 Pane 上, 调用方就算把返回值丢了, 这次投影也照样活着
        this.binding = pane.bind(source, ignoredHost -> this.onSourceDirty());
    }

    // 求值还在飞的时候来的失效不排队, 攒成一轮留到跑完再补
    private void onSourceDirty() {
        while (true) {
            Phase current = this.phase.get();
            switch (current) {
                case RESCHEDULE -> {
                    return;
                }
                case SCHEDULED -> {
                    if (this.phase.compareAndSet(Phase.SCHEDULED, Phase.RESCHEDULE)) {
                        return;
                    }
                }
                case IDLE -> {
                    if (this.phase.compareAndSet(Phase.IDLE, Phase.SCHEDULED)) {
                        this.submitRound();
                        return;
                    }
                }
            }
        }
    }

    private void runRound() {
        // 本轮开始之后再来的失效都算下一轮的事
        this.phase.set(Phase.SCHEDULED);
        try {
            this.evaluateReporting();
        } finally {
            while (true) {
                if (this.phase.compareAndSet(Phase.SCHEDULED, Phase.IDLE)) {
                    return;
                }
                if (this.phase.compareAndSet(Phase.RESCHEDULE, Phase.SCHEDULED)) {
                    this.submitRound();
                    return;
                }
            }
        }
    }

    // 执行器把任务拒了就把状态退回 IDLE, 下一次失效还能再提交
    private void submitRound() {
        try {
            this.executor.execute(this::runRound);
        } catch (RuntimeException | Error exception) {
            this.phase.set(Phase.IDLE);
            throw exception;
        }
    }

    // 派生函数是用户写的, 抛了就报上去并放弃本轮, 下一次失效还会再来
    private void evaluateReporting() {
        try {
            this.evaluate();
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to project a slot sequence", throwable);
        }
    }

    private void evaluate() {
        if (this.closed) return;
        Iterator<?> values = this.source.get().iterator();
        int length = this.slots.length();
        // 关闭和写入撞上时最多再写手上这一格
        for (int occurrence = 0; occurrence < length && !this.closed; occurrence++) {
            // 序列不够长就把余下的槽位清空, 超出去的尾部数据直接不要
            Element element = values.hasNext() ? this.toElement.apply(values.next()) : Element.empty();
            int slot = this.slots.slotAt(occurrence);
            if (!element.equals(this.pane.element(slot))) {
                this.pane.setElement(slot, element);
            }
        }
    }

    public boolean isClosed() {
        return this.closed;
    }

    // 停掉这次投影. 已经写进 Pane 的内容原样留着; 万一正好有一轮在写, 它最多再写完手上那一格.
    @Override
    public void close() {
        this.closed = true;
        this.binding.close();
    }

    private enum Phase {
        IDLE,       // 没有求值在飞
        SCHEDULED,  // 求值排上队了, 或者正在跑
        RESCHEDULE  // 跑的期间又来了失效, 跑完得再来一轮
    }
}
