package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MergingSignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void anyMemberInvalidationReachesDownstream() {
        MutableSignal<Integer> left = Signal.of(0);
        MutableSignal<Integer> right = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(left, right));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> merged.onDirty(invalidations::incrementAndGet));
        left.set(1);
        right.set(1);

        assertEquals(2, invalidations.get());
    }

    @Test
    void addedMemberBecomesReachable() {
        MutableSignal<Integer> present = Signal.of(0);
        MutableSignal<Integer> added = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(present));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> merged.onDirty(invalidations::incrementAndGet));
        members.set(List.of(present, added));

        assertEquals(1, invalidations.get());
        added.set(1);

        assertEquals(2, invalidations.get());
    }

    @Test
    void memberSwapDuringActivationClosesTheReplacedForwarding() {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(first));
        AtomicInteger lookups = new AtomicInteger();
        Signal<Long> merged = Signals.merging(members, member -> {
            if (lookups.incrementAndGet() == 1) {
                members.set(List.of(second));
            }
            return member;
        });
        this.bindings.bind(() -> merged.onDirty(() -> {
        }));

        assertEquals(0, ((AbstractSignal<?>) first).entryCount());
        assertEquals(1, ((AbstractSignal<?>) second).entryCount());
    }

    @Test
    void failedActivationLeavesNoMemberForwardingBehind() {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(first));
        AtomicInteger lookups = new AtomicInteger();
        Signal<Long> merged = Signals.merging(members, member -> {
            int lookup = lookups.incrementAndGet();
            if (lookup == 1) {
                members.set(List.of(second));
            }
            if (lookup == 3) {
                throw new IllegalStateException("member lookup exploded");
            }
            return member;
        });

        assertThrows(IllegalStateException.class, () -> merged.onDirty(() -> {
        }));

        assertEquals(0, ((AbstractSignal<?>) first).entryCount());
    }

    @Test
    void removedMemberStopsReachingDownstream() {
        MutableSignal<Integer> kept = Signal.of(0);
        MutableSignal<Integer> removed = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(kept, removed));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> merged.onDirty(invalidations::incrementAndGet));
        members.set(List.of(kept));
        int afterRemoval = invalidations.get();
        removed.set(1);

        assertEquals(afterRemoval, invalidations.get());
        kept.set(1);

        assertEquals(afterRemoval + 1, invalidations.get());
    }

    @Test
    void unchangedMembersDoNotDisturbDownstream() {
        MutableSignal<Integer> member = Signal.of(0);
        MutableSignal<Collection<MutableSignal<Integer>>> members = Signal.of(new ArrayDeque<>(List.of(member)));
        Signal<Long> merged = Signals.merging(members, element -> element);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> merged.onDirty(invalidations::incrementAndGet));
        members.set(new ArrayDeque<>(List.of(member)));

        assertEquals(0, invalidations.get(), "成员没换就不该打扰下游");
    }

    @Test
    void versionAdvancesOnPullPathWithoutSubscribers() {
        MutableSignal<Integer> member = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(member));
        Signal<Long> merged = Signals.merging(members, element -> element);
        long before = merged.get();
        member.set(1);

        assertNotEquals(before, merged.get());
    }

    @Test
    void memberSetChangeIsSeenOnPullPathWithoutSubscribers() {
        MutableSignal<Integer> present = Signal.of(0);
        MutableSignal<Integer> added = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(present));
        Signal<Long> merged = Signals.merging(members, element -> element);
        long before = merged.get();
        members.set(List.of(present, added));

        assertNotEquals(before, merged.get());
        long afterJoin = merged.get();
        added.set(1);

        assertNotEquals(afterJoin, merged.get());
    }

    @Test
    void removedMemberIsCollectedAfterRebind() {
        MutableSignal<Integer> kept = Signal.of(0);
        MutableSignal<Integer> removed = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(new ArrayList<>(List.of(kept, removed)));
        Signal<Long> merged = Signals.merging(members, member -> member);
        this.bindings.bind(() -> merged.onDirty(() -> {
        }));
        WeakReference<MutableSignal<Integer>> probe = new WeakReference<>(removed);
        members.set(List.of(kept));
        removed = null;
        GcSupport.awaitCollected(probe);
    }

    @Test
    void deadHostReleasesTheChainEvenWhenEveryInvalidationIsSwallowed() {
        MutableSignal<Integer> member = Signal.of(0);
        AbstractSignal<Integer> internalMember = (AbstractSignal<Integer>) member;
        MutableSignal<Collection<MutableSignal<Integer>>> members = Signal.of(new ArrayDeque<>(List.of(member)));
        AbstractSignal<?> internalMembers = (AbstractSignal<?>) members;
        Signal<Long> merged = Signals.merging(members, element -> element);
        Bindings dead = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(dead);
        dead.bind(() -> merged.onDirty(() -> {
        }));

        assertEquals(1, internalMembers.entryCount());
        assertEquals(1, internalMember.entryCount());
        dead = null;
        GcSupport.awaitCollected(probe);
        for (int round = 0; round < 5; round++) {
            members.set(new ArrayDeque<>(List.of(member)));
        }

        assertEquals(0, internalMembers.entryCount(), "持有方死亡后集合来源应当解挂");
        assertEquals(0, internalMember.entryCount(), "成员转发也应当一起摘掉");
    }

    @Test
    void failedVersionSumLeavesTheAlignmentUntouched() {
        MutableSignal<Integer> present = Signal.of(0);
        MutableSignal<Integer> raw = Signal.of(10);
        AtomicBoolean explode = new AtomicBoolean();
        Signal<Integer> exploding = raw.mapDistinct(value -> {
            if (explode.get()) {
                throw new IllegalStateException("version exploded");
            }
            return value;
        });
        this.bindings.bind(() -> exploding.onDirty(() -> {
        }));
        MutableSignal<List<Signal<Integer>>> members = Signal.of(List.of(present));
        Signal<Long> merged = Signals.merging(members, member -> member);
        this.bindings.bind(() -> merged.onDirty(() -> {
        }));
        explode.set(true);
        try (ExceptionHandlerProbe ignored = new ExceptionHandlerProbe()) {
            raw.set(20);
            members.set(List.of(present, exploding));
        }

        assertEquals(1, ((AbstractSignal<?>) present).entryCount(), "对齐没成, 上一批成员的转发要原样留着");
        assertEquals(1, ((AbstractSignal<?>) exploding).entryCount(), "失败的对齐不该在新成员上留下转发");
        explode.set(false);
        merged.get();

        assertEquals(1, ((AbstractSignal<?>) present).entryCount(), "重挂之后仍然只有一条");
        assertEquals(2, ((AbstractSignal<?>) exploding).entryCount(), "测试自己那条, 加上挂过来的这条");
    }
}
