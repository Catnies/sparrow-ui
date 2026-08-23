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
 * 把一个 {@link Signal} 给出的序列持续写进某个 Pane 的一段槽位, 本类型只作为停止投影的句柄交给使用方.
 * <p>创建入口会在返回前完成第一轮求值.
 */
public final class SlotProjection implements AutoCloseable {
    private static final Executor ASYNC = command -> Bukkit.getAsyncScheduler().runNow(
            SparrowUI.getInstance().getPlugin(),
            ignoredTask -> command.run()
    );

    private final Pane pane;
    private final SlotSequence slots;
    private final Signal<? extends List<?>> source;
    private final Function<Object, ? extends Element> toElement;
    private final Executor executor;

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SCHEDULED);
    private final Subscription binding;
    private volatile boolean closed;

    /**
     * 把序列投影到选中槽位, 在 Paper 全局异步调度器上求值.
     *
     * @param pane 接收写入的 Pane
     * @param slots 本投影负责的槽位, 必须属于该 Pane
     * @param source 序列来源
     * @param toElement 把序列里的一条数据变成一个 Element, 不得返回 {@code null}
     * @return 投影, 可用来提前停止
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
     * 把已经是 Element 的序列投影到选中槽位, 在 Paper 全局异步调度器上求值.
     *
     * @param pane 接收写入的 Pane
     * @param slots 本投影负责的槽位, 必须属于该 Pane
     * @param source 序列来源
     * @return 投影, 可用来提前停止
     */
    @NotNull
    static SlotProjection attachElements(
            @NotNull Pane pane,
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source
    ) {
        return attachElements(pane, slots, source, ASYNC);
    }

    @NotNull
    static SlotProjection attachElements(
            @NotNull Pane pane,
            @NotNull SlotSequence slots,
            @NotNull Signal<? extends List<? extends Element>> source,
            @NotNull Executor executor
    ) {
        return create(pane, slots, source, value -> (Element) value, executor);
    }

    // 给 Builder 用的默认执行器入口.
    static Executor defaultExecutor() {
        return ASYNC;
    }

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
        // 第一轮在调用线程完成, executor 只接后续失效
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
        // Pane 持有绑定声明, 投影不会因调用方丢掉返回值而失效
        this.binding = pane.bind(source, ignoredHost -> this.onSourceDirty());
    }

    // 在飞求值期间到达的失效合并成下一轮
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
        // 本轮开始后的失效归入下一轮
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

    // 执行器拒绝任务时允许下一次失效重新提交
    private void submitRound() {
        try {
            this.executor.execute(this::runRound);
        } catch (RuntimeException | Error exception) {
            this.phase.set(Phase.IDLE);
            throw exception;
        }
    }

    // 用户提供的派生函数失败时放弃本轮, 下次失效仍可重试
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
        // 关闭与写入竞争时最多再写当前一格
        for (int occurrence = 0; occurrence < length && !this.closed; occurrence++) {
            // 序列不足时清空余下槽位, 超出区域的尾部数据忽略
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

    /**
     * 停止跟随序列.
     * <p>调用时若已经有一轮求值在写这片槽位, 它最多再写完手上那一格.
     * 已经写进去的内容原样留在 Pane 上, 重复调用安全.
     */
    @Override
    public void close() {
        this.closed = true;
        this.binding.close();
    }

    private enum Phase {
        IDLE,       // 没有求值在飞
        SCHEDULED,  // 求值已排队或正在跑
        RESCHEDULE  // 跑的期间又来了失效, 跑完要再来一轮
    }
}
