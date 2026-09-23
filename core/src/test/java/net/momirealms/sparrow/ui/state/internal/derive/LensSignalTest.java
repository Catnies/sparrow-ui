package net.momirealms.sparrow.ui.state.internal.derive;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LensSignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void writeThroughTheLensReachesTheHost() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        MutableSignal<Boolean> sound = source.lens(Settings::sound, Settings::withSound);
        sound.set(false);

        assertEquals(new Settings(false, 5), source.get());
        assertFalse(sound.get());
    }

    @Test
    void updateThroughTheLensSeesTheCurrentField() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        MutableSignal<Integer> volume = source.lens(Settings::volume, Settings::withVolume);
        volume.update(value -> value + 3);

        assertEquals(8, volume.get());
        assertEquals(new Settings(true, 8), source.get());
    }

    @Test
    void writingASiblingFieldDoesNotDisturbTheLens() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        MutableSignal<Boolean> sound = source.lens(Settings::sound, Settings::withSound);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sound.onDirty(invalidations::incrementAndGet));
        source.set(new Settings(true, 9));

        assertEquals(0, invalidations.get());
        source.set(new Settings(false, 9));

        assertEquals(1, invalidations.get());
    }

    @Test
    void writingTheSameFieldValueIsSilentAtBothLevels() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        MutableSignal<Integer> volume = source.lens(Settings::volume, Settings::withVolume);
        AtomicInteger hostInvalidations = new AtomicInteger();
        AtomicInteger lensInvalidations = new AtomicInteger();
        this.bindings.bind(() -> source.onDirty(hostInvalidations::incrementAndGet));
        this.bindings.bind(() -> volume.onDirty(lensInvalidations::incrementAndGet));
        volume.set(5);

        assertEquals(0, hostInvalidations.get(), "record 判等认出这是同一个值, 宿主那层就停住了");
        assertEquals(0, lensInvalidations.get());
    }

    @Test
    void aHostThatAlwaysMakesANewObjectIsTruncatedByTheLens() {
        MutableSignal<Bean> source = Signal.of(new Bean(true, 5));
        MutableSignal<Boolean> sound = source.lens(Bean::sound, Bean::withSound);
        AtomicInteger hostInvalidations = new AtomicInteger();
        AtomicInteger lensInvalidations = new AtomicInteger();
        this.bindings.bind(() -> source.onDirty(hostInvalidations::incrementAndGet));
        this.bindings.bind(() -> sound.onDirty(lensInvalidations::incrementAndGet));
        source.update(bean -> bean.withVolume(5));

        assertEquals(1, hostInvalidations.get(), "Bean 没有 equals, 新对象一律算变了");
        assertEquals(0, lensInvalidations.get(), "sound 没动, 截断发生在 lens 这一层");
    }

    @Test
    void lensSameValueOverloadDecidesTruncation() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        MutableSignal<Integer> volume = source.lens(Settings::volume, Settings::withVolume, (a, b) -> a / 10 == b / 10);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> volume.onDirty(invalidations::incrementAndGet));
        volume.set(7);

        assertEquals(0, invalidations.get());
        assertEquals(7, source.get().volume(), "宿主照样写进去了, 只是这条 lens 不通知");
        volume.set(15);

        assertEquals(1, invalidations.get());
    }

    @Test
    void nestedLensReadsAndWrites() {
        MutableSignal<Profile> profile = Signal.of(new Profile("catnies", new Settings(true, 5)));
        MutableSignal<Settings> settings = profile.lens(Profile::settings, Profile::withSettings);
        MutableSignal<Boolean> sound = settings.lens(Settings::sound, Settings::withSound);

        assertTrue(sound.get());
        sound.set(false);

        assertEquals(new Profile("catnies", new Settings(false, 5)), profile.get());
        assertEquals(new Settings(false, 5), settings.get());
        assertFalse(sound.get());
    }

    @Test
    void lensBehavesLikeAnyOtherSignalDownstream() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        MutableSignal<Integer> volume = source.lens(Settings::volume, Settings::withVolume);
        Signal<String> label = volume.map(value -> "音量 " + value);
        MutableSignal<Boolean> muted = Signal.of(false);
        Signal<String> shown = Signals.combine(volume, muted, (value, off) -> off ? "静音" : "音量 " + value);
        volume.set(7);

        assertEquals("音量 7", label.get());
        assertEquals("音量 7", shown.get());
        muted.set(true);

        assertEquals("静音", shown.get());
    }

    @Test
    void aDerivedNodeSubscribedOnALensFollowsTheFieldAndReleasesTheHost() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        AbstractSignal<Settings> internal = (AbstractSignal<Settings>) source;
        MutableSignal<Integer> volume = source.lens(Settings::volume, Settings::withVolume);
        Signal<String> label = volume.map(value -> "音量 " + value);
        List<String> received = new ArrayList<>();
        Subscription subscription = label.onDirty(() -> received.add(label.get()));

        assertEquals(1, SignalTestAccess.entryCount(internal), "整条 lens 在宿主上只挂一条订阅");
        source.set(new Settings(false, 5));

        assertEquals(List.of(), received, "只动了兄弟字段, 派生节点不该被惊动");
        source.set(new Settings(false, 7));

        assertEquals(List.of("音量 7"), received);
        subscription.close();

        assertEquals(0, SignalTestAccess.entryCount(internal), "末端退订后宿主上不留订阅");
    }

    @Test
    void lensOnAWritablePartitionHandleRoundTrips() {
        MutableKeyedSignal<String, Settings> signal = KeyedSignal.of(key -> new Settings(true, 5));
        MutableSignal<Boolean> sound = signal.at("k").lens(Settings::sound, Settings::withSound);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sound.onDirty(invalidations::incrementAndGet));
        sound.set(false);

        assertEquals(new Settings(false, 5), signal.get("k"));
        assertEquals(1, invalidations.get());
        signal.set("k", new Settings(false, 9));

        assertEquals(1, invalidations.get(), "只动了兄弟字段, 这条 lens 不该被惊动");
    }

    @Test
    void lensOnAnEvictedPartitionRebuildsThroughUpdate() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Settings> signal = KeyedSignal.of(key -> {
            loads.incrementAndGet();
            return new Settings(true, 5);
        });
        MutableSignal<Boolean> sound = signal.at("k").lens(Settings::sound, Settings::withSound);

        assertTrue(sound.get());
        signal.remove("k");
        loads.set(0);
        sound.set(false);

        assertEquals(1, loads.get());
        assertEquals(new Settings(false, 5), signal.get("k"));
    }

    @Test
    void concurrentWritesThroughTwoLensesBothLand() throws InterruptedException {
        MutableSignal<Pair> source = Signal.of(new Pair(0, 0));
        MutableSignal<Integer> left = source.lens(Pair::left, Pair::withLeft);
        MutableSignal<Integer> right = source.lens(Pair::right, Pair::withRight);
        int rounds = 2000;
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(bump(start, left, rounds), "lens-writer-left");
        Thread second = new Thread(bump(start, right, rounds), "lens-writer-right");
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertEquals(rounds, left.get());
        assertEquals(rounds, right.get());
    }

    @Test
    void concurrentSetsThroughTwoLensesDoNotClobberEachOther() throws InterruptedException {
        MutableSignal<Pair> source = Signal.of(new Pair(0, 0));
        MutableSignal<Integer> left = source.lens(Pair::left, Pair::withLeft);
        MutableSignal<Integer> right = source.lens(Pair::right, Pair::withRight);
        int rounds = 2000;
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(countUp(start, left, rounds), "lens-setter-left");
        Thread second = new Thread(countUp(start, right, rounds), "lens-setter-right");
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertEquals(rounds, left.get());
        assertEquals(rounds, right.get());
    }

    @Test
    void unreachableLensReleasesItsSubscriptionOnTheHost() {
        MutableSignal<Settings> source = Signal.of(new Settings(true, 5));
        AbstractSignal<Settings> internal = (AbstractSignal<Settings>) source;
        WeakReference<?> probe = bindLensAndForget(source);

        assertEquals(1, SignalTestAccess.entryCount(internal));
        GcSupport.awaitCollected(probe);
        source.set(new Settings(false, 5));

        assertEquals(0, SignalTestAccess.entryCount(internal));
    }

    private static WeakReference<?> bindLensAndForget(MutableSignal<Settings> source) {
        MutableSignal<Boolean> lens = source.lens(Settings::sound, Settings::withSound);
        lens.onDirty(() -> {
        });
        return new WeakReference<>(lens);
    }

    private static Runnable countUp(CountDownLatch start, MutableSignal<Integer> field, int rounds) {
        return () -> {
            if (!awaitStart(start)) return;
            for (int i = 1; i <= rounds; i++) {
                field.set(i);
            }
        };
    }

    private static Runnable bump(CountDownLatch start, MutableSignal<Integer> field, int rounds) {
        return () -> {
            if (!awaitStart(start)) return;
            for (int i = 0; i < rounds; i++) {
                field.update(value -> value + 1);
            }
        };
    }

    private static boolean awaitStart(CountDownLatch start) {
        try {
            start.await();
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Settings(boolean sound, int volume) {
        Settings withSound(boolean sound) {
            return new Settings(sound, this.volume);
        }
        Settings withVolume(int volume) {
            return new Settings(this.sound, volume);
        }
    }

    private record Profile(String name, Settings settings) {
        Profile withSettings(Settings settings) {
            return new Profile(this.name, settings);
        }
    }

    private record Pair(int left, int right) {
        Pair withLeft(int left) {
            return new Pair(left, this.right);
        }
        Pair withRight(int right) {
            return new Pair(this.left, right);
        }
    }

    private static final class Bean {
        private final boolean sound;
        private final int volume;
        private Bean(boolean sound, int volume) {
            this.sound = sound;
            this.volume = volume;
        }
        private boolean sound() {
            return this.sound;
        }
        private Bean withSound(boolean sound) {
            return new Bean(sound, this.volume);
        }
        private Bean withVolume(int volume) {
            return new Bean(this.sound, volume);
        }
    }
}
