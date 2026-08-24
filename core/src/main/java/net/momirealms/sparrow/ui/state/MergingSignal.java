package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

final class MergingSignal<T> extends AbstractSignal<Long> {
    private final AbstractSignal<? extends Collection<? extends T>> sources;
    private final Function<? super T, ? extends Signal<?>> signalOf;
    private final Object mergeLock = new Object();

    @Nullable private volatile Aligned aligned;         // 当前成员与读取到的版本
    private volatile long version;                      // 成员集合或任一成员失效时递增
    private long notifiedVersion;                       // 最近一次已派发的版本
    @Nullable private Subscription sourcesUpstream;
    private Subscription @Nullable [] memberUpstream;   // 有下游订阅时存在, 与 aligned 同步换批

    MergingSignal(
            AbstractSignal<? extends Collection<? extends T>> sources,
            Function<? super T, ? extends Signal<?>> signalOf
    ) {
        this.sources = sources;
        this.signalOf = signalOf;
    }

    @Override
    public Long get() {
        return this.version();
    }

    @Override
    long version() {
        // 无锁检查当前对齐记录
        Aligned current = this.aligned;
        if (
                current != null
                && current.sourcesVersion() == this.sources.version()
                && current.memberVersionSum() == versionSumOf(current.members())
        ) {
            return this.version;
        }

        Subscription[] previous;
        synchronized (this.mergeLock) {
            previous = this.alignLocked();
        }
        closeAll(previous);
        return this.version;
    }

    /**
     * 把成员列表对齐到集合当前内容, 换过成员或成员失效过时推进版本, 已经对齐时只更新记账.
     * <p>先推进 {@code version}, 再发布 {@code aligned}. {@link #version()} 按相反顺序读取,
     * 因此不会观察到新成员配旧版本而漏掉变化. 旧成员配新版本只会多计算一次.
     *
     * @return 需要在锁外关闭的上一批成员转发凭证, 没有换成员时为 {@code null}
     */
    private Subscription @Nullable [] alignLocked() {
        long sourcesVersion = this.sources.version();
        Aligned current = this.aligned;
        // 集合版本未变时直接沿用上次换算出的成员
        AbstractSignal<?>[] members = current != null && current.sourcesVersion() == sourcesVersion
                ? current.members()
                : this.currentMembers();

        // 成员未换时只比较各自版本
        if (current != null && Arrays.equals(current.members(), members)) {
            long sum = versionSumOf(members);
            if (sum != current.memberVersionSum()) {
                this.version++;
            }
            // 同步记录集合版本, 避免重复换算相同成员
            this.aligned = new Aligned(members, sourcesVersion, sum);
            return null;
        }

        // 无下游订阅时不建立转发, 由拉取路径推进版本
        Subscription[] previous = this.memberUpstream;
        Subscription[] attached = null;
        if (previous != null) {
            attached = this.linkAll(members, this::onUpstreamDirty);
        }
        // 读取成员版本失败时撤销新转发, 让对齐记录和上一批订阅继续对应
        long sum;
        try {
            sum = versionSumOf(members);
        } catch (RuntimeException | Error exception) {
            closeAll(attached);
            throw exception;
        }
        if (attached != null) {
            this.memberUpstream = attached;
        }
        this.version++;
        this.aligned = new Aligned(members, sourcesVersion, sum);
        return previous;
    }

    // 按集合迭代顺序换算成员 signal, 数组内容才是成员是否变化的依据
    private AbstractSignal<?>[] currentMembers() {
        Collection<? extends T> elements = this.sources.get();
        AbstractSignal<?>[] members = new AbstractSignal<?>[elements.size()];
        int index = 0;
        for (T element : elements) {
            members[index++] = AbstractSignal.require(this.signalOf.apply(element));
        }
        return members;
    }

    // 上游失效后重新对齐, 版本确实前进时才派发
    private void onUpstreamDirty() {
        Subscription[] previous;
        boolean shouldNotify = false;
        synchronized (this.mergeLock) {
            previous = this.alignLocked();
            if (this.version > this.notifiedVersion) {
                this.notifiedVersion = this.version;
                shouldNotify = true;
            }
        }
        closeAll(previous);
        if (shouldNotify) {
            this.notifyDirty();
        }
    }

    @Override
    protected void onActive() {
        Subscription[] discarded = null;
        synchronized (this.mergeLock) {
            this.sourcesUpstream = this.linkTo(this.sources, this::onUpstreamDirty);
            try {
                this.alignLocked();
                Aligned current = this.aligned;
                assert current != null; // alignLocked 一定会留下一次对齐结果
                this.memberUpstream = this.linkAll(current.members(), this::onUpstreamDirty);
                // 建完转发后再次对齐, 收进挂载窗口内发生的成员失效
                discarded = this.alignLocked();
            } catch (RuntimeException | Error exception) {
                // 激活求值失败时撤销本轮订阅, 配合 register 回滚
                closeAll(this.memberUpstream);
                this.memberUpstream = null;
                this.sourcesUpstream.close();
                this.sourcesUpstream = null;
                throw exception;
            }
            // 首次订阅以当前版本建立通知基线
            this.notifiedVersion = this.version;
        }
        closeAll(discarded);
    }

    @Override
    protected void onInactive() {
        Subscription previousSources;
        Subscription[] previousMembers;
        synchronized (this.mergeLock) {
            previousSources = this.sourcesUpstream;
            previousMembers = this.memberUpstream;
            this.sourcesUpstream = null;
            this.memberUpstream = null;
        }
        previousSources.close();
        closeAll(previousMembers);
    }

    // 成员固定且各版本单调递增, 版本和不变即可确认所有成员都未失效
    private static long versionSumOf(AbstractSignal<?>[] members) {
        long sum = 0L;
        for (int index = 0; index < members.length; index++) {
            sum += members[index].version();
        }
        return sum;
    }

    // 一次对齐使用的成员、集合版本和成员版本和
    private record Aligned(AbstractSignal<?>[] members, long sourcesVersion, long memberVersionSum) {
    }
}
