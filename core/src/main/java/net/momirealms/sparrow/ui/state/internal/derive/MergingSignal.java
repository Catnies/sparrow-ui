package net.momirealms.sparrow.ui.state.internal.derive;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.function.Function;

@ApiStatus.Internal
public final class MergingSignal<T> extends AbstractSignal<Long> {
    private final AbstractSignal<? extends Collection<? extends T>> sources;
    private final Function<? super T, ? extends Signal<?>> signalOf;
    private final Object mergeLock = new Object();
    private final Runnable upstreamDirty = this::onUpstreamDirty;

    @Nullable private volatile Aligned aligned;         // 当前成员与读取到的版本
    private volatile long version;                      // 成员集合或任一成员失效时递增
    private long notifiedVersion;                       // 最近一次已派发的版本
    @Nullable private Subscription sourcesUpstream;
    private Subscription @Nullable [] memberUpstream;   // 有下游订阅时存在, 与 aligned 同步换批

    public MergingSignal(
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
    public long version() {
        // 无锁检查当前对齐记录
        Aligned current = this.aligned;
        if (
                current != null
                && current.sourcesVersion == this.sources.version()
                && current.memberVersionSum == versionSumOf(current.members)
        ) {
            return this.version;
        }

        Subscription[] previous;
        synchronized (this.mergeLock) {
            previous = this.alignLocked();
        }
        closeSubscriptions(previous);
        return this.version;
    }

    /**
     * 把成员列表对齐到集合当前内容, 换过成员或成员失效过时推进版本, 已经对齐时只更新记账.
     * <p>先推进 {@code version}, 再发布成员版本和或 {@code aligned}. {@link #version()} 按相反顺序读取,
     * 因此不会观察到新成员配旧版本而漏掉变化. 旧成员配新版本只会多计算一次.
     *
     * @return 需要在锁外关闭的旧凭证, 已复用的位置为 {@code null}, 没有换成员时返回 {@code null}
     */
    private Subscription @Nullable [] alignLocked() {
        long sourcesVersion = this.sources.version();
        Aligned current = this.aligned;
        // 集合版本未变时直接沿用上次换算出的成员
        AbstractSignal<?>[] members = current != null && current.sourcesVersion == sourcesVersion
                ? current.members
                : this.currentMembers();

        // 成员未换时只比较各自版本
        if (current != null && Arrays.equals(current.members, members)) {
            long sum = versionSumOf(members);
            if (sum != current.memberVersionSum) {
                this.version++;
            }
            if (current.sourcesVersion == sourcesVersion) {
                current.memberVersionSum = sum;
            } else {
                this.aligned = new Aligned(members, sourcesVersion, sum);
            }
            return null;
        }

        // 无下游订阅时不建立转发, 由拉取路径推进版本
        Subscription[] previous = this.memberUpstream;
        int[] retained = previous == null ? null : retainedIndices(current.members, members);
        Subscription[] attached = previous == null ? null : new Subscription[members.length];
        // 全部挂载和求值成功后再移交旧凭证, 失败时仅撤销本轮新建的转发
        long sum;
        try {
            if (attached != null) {
                for (int index = 0; index < members.length; index++) {
                    int oldIndex = retained[index];
                    attached[index] = oldIndex >= 0 ? previous[oldIndex] : this.linkTo(members[index], this.upstreamDirty);
                }
            }
            sum = versionSumOf(members);
        } catch (RuntimeException | Error exception) {
            if (attached != null) {
                for (int index = attached.length - 1; index >= 0; index--) {
                    if (retained[index] < 0 && attached[index] != null) {
                        attached[index].close();
                    }
                }
            }
            throw exception;
        }
        if (attached != null) {
            // 挂载新成员可能同步触发重入, 外层仍可能持有旧数组
            previous = previous.clone();
            for (int index = 0; index < retained.length; index++) {
                if (retained[index] >= 0) {
                    previous[retained[index]] = null;
                }
            }
            this.memberUpstream = attached;
        }
        this.version++;
        this.aligned = new Aligned(members, sourcesVersion, sum);
        return previous;
    }

    // 每个旧位置只能复用一次, 重复成员仍保留各自的转发凭证
    private static int[] retainedIndices(AbstractSignal<?>[] previous, AbstractSignal<?>[] members) {
        int[] retained = new int[members.length];
        Arrays.fill(retained, -1);
        int prefix = 0;
        int limit = Math.min(previous.length, members.length);
        while (prefix < limit && previous[prefix] == members[prefix]) {
            retained[prefix] = prefix;
            prefix++;
        }
        int oldEnd = previous.length;
        int newEnd = members.length;
        while (oldEnd > prefix && newEnd > prefix && previous[oldEnd - 1] == members[newEnd - 1]) {
            retained[--newEnd] = --oldEnd;
        }
        if (prefix == oldEnd || prefix == newEnd) return retained;

        // 中间重排按对象身份匹配, 同一来源的多个旧位置串成索引链
        IdentityHashMap<AbstractSignal<?>, Integer> available = new IdentityHashMap<>(oldEnd - prefix);
        int[] next = new int[oldEnd - prefix];
        for (int index = oldEnd - 1; index >= prefix; index--) {
            Integer following = available.put(previous[index], index);
            next[index - prefix] = following == null ? -1 : following;
        }
        for (int index = prefix; index < newEnd; index++) {
            Integer oldIndex = available.remove(members[index]);
            if (oldIndex != null) {
                retained[index] = oldIndex;
                int following = next[oldIndex - prefix];
                if (following >= 0) {
                    available.put(members[index], following);
                }
            }
        }
        return retained;
    }

    // 换批后的旧数组中, 已移交给新数组的凭证位置为空
    private static void closeSubscriptions(Subscription @Nullable [] subscriptions) {
        if (subscriptions == null) return;
        for (int index = 0; index < subscriptions.length; index++) {
            Subscription subscription = subscriptions[index];
            if (subscription != null) {
                subscription.close();
            }
        }
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
        closeSubscriptions(previous);
        if (shouldNotify) {
            this.notifyDirty();
        }
    }

    @Override
    protected void onActive() {
        Subscription[] discarded = null;
        synchronized (this.mergeLock) {
            this.sourcesUpstream = this.linkTo(this.sources, this.upstreamDirty);
            try {
                this.alignLocked();
                Aligned current = this.aligned;
                assert current != null; // alignLocked 一定会留下一次对齐结果
                this.memberUpstream = this.linkAll(current.members, this.upstreamDirty);
                // 建完转发后再次对齐, 收进挂载窗口内发生的成员失效
                discarded = this.alignLocked();
            } catch (RuntimeException | Error exception) {
                // 激活求值失败时撤销本轮订阅, 配合 register 回滚
                closeSubscriptions(this.memberUpstream);
                this.memberUpstream = null;
                this.sourcesUpstream.close();
                this.sourcesUpstream = null;
                throw exception;
            }
            // 首次订阅以当前版本建立通知基线
            this.notifiedVersion = this.version;
        }
        closeSubscriptions(discarded);
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
        closeSubscriptions(previousMembers);
    }

    // 成员固定且各版本单调递增, 版本和不变即可确认所有成员都未失效
    private static long versionSumOf(AbstractSignal<?>[] members) {
        long sum = 0L;
        for (int index = 0; index < members.length; index++) {
            sum += members[index].version();
        }
        return sum;
    }

    private static final class Aligned {
        private final AbstractSignal<?>[] members;
        private final long sourcesVersion;
        private volatile long memberVersionSum; // mergeLock 内更新, 供 version() 无锁读取

        private Aligned(AbstractSignal<?>[] members, long sourcesVersion, long memberVersionSum) {
            this.members = members;
            this.sourcesVersion = sourcesVersion;
            this.memberVersionSum = memberVersionSum;
        }
    }
}
