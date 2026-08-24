package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

/**
 * 多来源汇合节点, 成员由一个集合 signal 给出, 集合换了成员或任何一个成员失效, 都向下游失效.
 *
 * @param <T> 集合成员类型
 */
final class MergingSignal<T> extends AbstractSignal<Long> {
    private final AbstractSignal<? extends Collection<? extends T>> sources;
    private final Function<? super T, ? extends Signal<?>> signalOf;
    private final Object mergeLock = new Object();

    @Nullable private volatile Aligned aligned;         // 当前对齐到的成员及其版本快照
    private volatile long version;                      // 单调递增, 换成员或成员失效时推进
    private long notifiedVersion;                       // 已向下游通知过的版本
    @Nullable private Subscription sourcesUpstream;     // 有下游订阅时挂着
    private Subscription @Nullable [] memberUpstream;   // 有下游订阅时挂着, 与 aligned 一起换

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
        // 无锁对一次快照确认有没有变化.
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
     * <p>版本一律先推进再发布快照. {@link #version()} 按 aligned 到 version 的顺序读, 这里写成相反的顺序,
     * 读者才不会看见新快照却配上旧版本 —— 那会让没有下游订阅的拉取路径把这次变化整个漏掉.
     * 反过来看见旧快照配新版本是安全的: 那只会让下游多算一遍.
     *
     * @return 需要在锁外关闭的上一批成员转发凭证, 没有换成员时为 {@code null}
     */
    private Subscription @Nullable [] alignLocked() {
        long sourcesVersion = this.sources.version();
        Aligned current = this.aligned;
        // 集合没失效过, 成员就不可能换, 这里可以直接沿用上次算好的那批成员, 省掉一次重算.
        AbstractSignal<?>[] members = current != null && current.sourcesVersion() == sourcesVersion
                ? current.members()
                : this.currentMembers();

        // 成员没换, 只看自上次记录以来有没有失效过
        if (current != null && Arrays.equals(current.members(), members)) {
            long sum = versionSumOf(members);
            if (sum != current.memberVersionSum()) {
                this.version++;
            }
            // 集合版本一并记新, 否则集合每失效一次都要白重算一遍成员
            this.aligned = new Aligned(members, sourcesVersion, sum);
            return null;
        }

        // 无下游订阅时不挂转发, 版本改由 version 的拉取路径推进
        Subscription[] previous = this.memberUpstream;
        Subscription[] attached = null;
        if (previous != null) {
            attached = this.linkAll(members, this::onUpstreamDirty);
        }
        // 版本之和抛出时整笔换成员作废: 新转发当场撤掉, 对齐结果与上一批转发都维持原状,
        // 否则逻辑上还对着旧成员, 转发却已经改听新成员, 而换回旧成员走的是快路径, 不会再重挂.
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

    /**
     * 读出集合当前内容, 并把每个成员换成它的 signal.
     * <p>换算出来的 signal 数组才是"成员有没有换"的依据: 集合值换了不等于成员换了, 使用方完全可能
     * 给出一个内容相同的新集合.
     *
     * @return 按集合迭代顺序排列的成员 signal
     */
    private AbstractSignal<?>[] currentMembers() {
        Collection<? extends T> elements = this.sources.get();
        AbstractSignal<?>[] members = new AbstractSignal<?>[elements.size()];
        int index = 0;
        for (T element : elements) {
            members[index++] = AbstractSignal.require(this.signalOf.apply(element));
        }
        return members;
    }

    // 集合或某个成员失效时重新对齐一次, 真的变了才向下游通知.
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
                // 上一句之前发生的成员失效收不到推送, 所以挂完转发再对一次快照, 把它收进版本里.
                discarded = this.alignLocked();
            } catch (RuntimeException | Error exception) {
                // 集合求值或成员换算抛出时撤销已挂的订阅, 让 register 的回滚留下干净现场.
                closeAll(this.memberUpstream);
                this.memberUpstream = null;
                this.sourcesUpstream.close();
                this.sourcesUpstream = null;
                throw exception;
            }
            // 没有基线时首次订阅会把无订阅期间攒下的版本推进误判为变化, 手动对齐.
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

    /**
     * 把全部成员的版本加起来.
     * <p>成员固定不变时各自的版本只增不减, 所以这个和只要没变, 就一定谁都没失效过.
     *
     * @param members 成员 signal
     * @return 版本之和
     */
    private static long versionSumOf(AbstractSignal<?>[] members) {
        long sum = 0L;
        for (int index = 0; index < members.length; index++) {
            sum += members[index].version();
        }
        return sum;
    }

    // 一次对齐结果: 成员 signal, 算出这批成员时集合的版本, 以及记下这一次时成员的版本之和.
    private record Aligned(AbstractSignal<?>[] members, long sourcesVersion, long memberVersionSum) {
    }
}
