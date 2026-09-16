package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.Bindings;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementHooksTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();
    private final Bindings bindings = new Bindings();
    private final List<String> log = new ArrayList<>();
    private ListSignal<String> wrappingList(List<String> delegate) {
        ListSignal<String> signal = ListSignal.wrap(delegate);
        this.hookTokens.add(signal.beforeAdd(element -> {
            this.log.add("add:" + element);
            return "<" + element + ">";
        }));
        this.hookTokens.add(signal.afterRemove(element -> this.log.add("removed:" + element)));
        return signal;
    }

    private SetSignal<String> wrappingSet(Set<String> delegate) {
        SetSignal<String> signal = SetSignal.wrap(delegate);
        this.hookTokens.add(signal.beforeAdd(element -> {
            this.log.add("add:" + element);
            return "<" + element + ">";
        }));
        this.hookTokens.add(signal.afterRemove(element -> this.log.add("removed:" + element)));
        return signal;
    }

    private MapSignal<String, String> wrappingMap(Map<String, String> delegate) {
        MapSignal<String, String> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.beforePut((key, value) -> {
            this.log.add("put:" + key + "=" + value);
            return "<" + value + ">";
        }));
        this.hookTokens.add(signal.afterRemove((key, value) -> this.log.add("removed:" + key + "=" + value)));
        return signal;
    }

    @Test
    void listAddStoresWhatTheHookReturnsAndRemovedSeesTheStoredOne() {
        ListSignal<String> signal = this.wrappingList(new ArrayList<>());
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        signal.add("a");
        signal.addAll(List.of("b", "c"));
        signal.add(0, "z");

        assertEquals(List.of("<z>", "<a>", "<b>", "<c>"), List.copyOf(signal), "存进去的是钩子返回的");
        signal.remove(1);
        signal.removeLast();

        assertEquals(List.of("add:a", "add:b", "add:c", "add:z", "removed:<a>", "removed:<c>"), this.log);
        assertEquals(5, notifications.get());
    }

    @Test
    void replacementRemovesTheOldBeforeAddingTheNew() {
        ListSignal<String> signal = this.wrappingList(new ArrayList<>(List.of("x")));
        signal.set(0, "y");

        assertEquals(List.of("removed:x", "add:y"), this.log, "先摘旧再放新");
        assertEquals(List.of("<y>"), List.copyOf(signal));
        this.log.clear();
        ListIterator<String> it = signal.listIterator();
        it.next();
        it.set("z");

        assertEquals(List.of("removed:<y>", "add:z"), this.log);
        this.log.clear();
        signal.replaceAll(s -> s + "!");

        assertEquals(List.of("removed:<z>", "add:<z>!"), this.log);
        assertEquals(List.of("<<z>!>"), List.copyOf(signal));
    }

    @Test
    void hooksChainInRegistrationOrder() {
        ListSignal<String> signal = ListSignal.of();
        this.hookTokens.add(signal.beforeAdd(element -> element + "1"));
        this.hookTokens.add(signal.beforeAdd(element -> element + "2"));
        this.hookTokens.add(signal.afterRemove(element -> this.log.add("first:" + element)));
        this.hookTokens.add(signal.afterRemove(element -> this.log.add("second:" + element)));
        signal.add("a");
        signal.remove("a12");

        assertEquals(List.of(), List.copyOf(signal));
        assertEquals(List.of("first:a12", "second:a12"), this.log, "前一个的返回值是后一个的入参, 移除钩子按挂的顺序跑");
    }

    @Test
    void addHookRunsBeforeTheElementIsStored() {
        ListSignal<String> signal = ListSignal.of();
        List<Integer> sizesSeenInHook = new ArrayList<>();
        signal.beforeAdd(element -> {
            sizesSeenInHook.add(signal.size());
            return element;
        });
        signal.add("a");
        signal.add("b");

        assertEquals(List.of(0, 1), sizesSeenInHook, "onAdd 里看到的还是放入之前的集合");
    }

    @Test
    void subscribersSeeTheHookSideEffectsAlreadyDone() {
        List<String> sideTable = new ArrayList<>();
        ListSignal<String> signal = ListSignal.of();
        this.hookTokens.add(signal.beforeAdd(element -> {
            sideTable.add(element);
            return element;
        }));
        this.hookTokens.add(signal.afterRemove(sideTable::remove));
        List<List<String>> seen = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> seen.add(List.copyOf(sideTable))));
        signal.add("a");
        signal.remove("a");

        assertEquals(List.of(List.of("a"), List.of()), seen, "通知时旁表已经改完");
    }

    @Test
    void bulkRemovalsOnACopyOnWriteDelegateCallTheHookPerElement() {
        ListSignal<String> list = this.wrappingList(new CopyOnWriteArrayList<>());
        list.addAll(List.of("a", "b", "c", "d"));
        this.log.clear();
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> list.onDirty(notifications::incrementAndGet));
        list.removeIf(s -> s.contains("a") || s.contains("b"));

        assertEquals(List.of("removed:<a>", "removed:<b>"), this.log);
        assertEquals(1, notifications.get());
        this.log.clear();
        list.retainAll(List.of("<c>"));

        assertEquals(List.of("removed:<d>"), this.log);
        list.clear();

        assertEquals(List.of("removed:<d>", "removed:<c>"), this.log);
        assertEquals(3, notifications.get());
        SetSignal<String> set = this.wrappingSet(new CopyOnWriteArraySet<>());
        set.addAll(List.of("x", "y"));

        assertEquals(List.of("<x>", "<y>"), List.copyOf(set));
        set.removeIf(s -> true);

        assertTrue(set.isEmpty());
    }

    @Test
    void setDeduplicatesOnTheOriginalElementBeforeCallingTheHook() {
        SetSignal<String> signal = this.wrappingSet(new LinkedHashSet<>());
        signal.add("a");
        signal.add("<a>");
        signal.addAll(List.of("<a>", "b"));

        assertEquals(List.of("add:a", "add:b"), this.log, "已有元素不惊动钩子");
        assertEquals(List.of("<a>", "<b>"), List.copyOf(signal));
    }

    @Test
    void mapPutStoresWhatTheHookReturnsAndReplacementRemovesOldFirst() {
        MapSignal<String, String> signal = this.wrappingMap(new LinkedHashMap<>());
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        signal.put("k", "v1");

        assertEquals("<v1>", signal.get("k"));
        signal.put("k", "v2");

        assertEquals(List.of("put:k=v1", "removed:k=<v1>", "put:k=v2"), this.log, "替换先摘旧再放新");
        this.log.clear();
        signal.remove("k");

        assertEquals(List.of("removed:k=<v2>"), this.log, "移除收到的是存着的代理");
        assertEquals(3, notifications.get());
    }

    @Test
    void mapReplacementPathsAllGoThroughTheHooks() {
        MapSignal<String, String> signal = this.wrappingMap(new LinkedHashMap<>());
        signal.put("a", "1");
        signal.put("b", "2");
        this.log.clear();
        signal.entrySet().iterator().next().setValue("10");

        assertEquals(List.of("removed:a=<1>", "put:a=10"), this.log);
        this.log.clear();
        signal.replaceAll((k, v) -> v + "!");

        assertEquals(List.of("removed:a=<10>", "put:a=<10>!", "removed:b=<2>", "put:b=<2>!"), this.log);
        this.log.clear();
        signal.replace("a", "x");
        signal.replace("b", "<<2>!>", "y");

        assertEquals(List.of("removed:a=<<10>!>", "put:a=x", "removed:b=<<2>!>", "put:b=y"), this.log);
        this.log.clear();
        signal.putAll(Map.of("c", "3"));
        signal.putIfAbsent("d", "4");
        signal.putIfAbsent("d", "ignored");

        assertEquals(List.of("put:c=3", "put:d=4"), this.log);
    }

    @Test
    void computeFamilyRunsHooksInsideTheDelegateAndKeepsAtomicity() {
        MapSignal<String, String> signal = this.wrappingMap(new ConcurrentHashMap<>());
        signal.computeIfAbsent("a", k -> "1");
        signal.compute("a", (k, v) -> v + "+");
        signal.merge("a", "m", String::concat);
        signal.computeIfPresent("a", (k, v) -> null);
        signal.merge("b", "n", String::concat);

        assertEquals(List.of(
                "put:a=1",
                "removed:a=<1>", "put:a=<1>+",
                "removed:a=<<1>+>", "put:a=<<1>+>m",
                "removed:a=<<<1>+>m>",
                "put:b=n"
        ), this.log);

        assertEquals(Map.of("b", "<n>"), Map.copyOf(signal));
    }

    @Test
    void mapViewRemovalsCallTheHookWithTheStoredValue() {
        MapSignal<String, String> signal = this.wrappingMap(new LinkedHashMap<>());
        signal.put("a", "1");
        signal.put("b", "2");
        signal.put("c", "3");
        this.log.clear();
        signal.keySet().remove("a");
        signal.values().remove("<2>");
        Iterator<Map.Entry<String, String>> it = signal.entrySet().iterator();
        it.next();
        it.remove();

        assertEquals(List.of("removed:a=<1>", "removed:b=<2>", "removed:c=<3>"), this.log);
        assertTrue(signal.isEmpty());
    }

    @Test
    void aThrowingHookStillLetsTheChangeReachSubscribers() {
        IllegalStateException boom = new IllegalStateException("hook failed");
        ListSignal<String> signal = ListSignal.wrap(new ArrayList<>(List.of("a")));
        this.hookTokens.add(signal.afterRemove(element -> {
            throw boom;
        }));
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> signal.remove("a"));

        assertSame(boom, thrown, "钩子的异常原样抛给写入方");
        assertTrue(signal.isEmpty(), "变更已经落地");
        assertEquals(1, notifications.get(), "订阅者仍然收到这次变更");
    }

    @Test
    void aHookMayMutateTheSameCollectionAndEachLayerNotifiesOnce() {
        ListSignal<String> signal = ListSignal.of();
        signal.beforeAdd(element -> {
            if (!element.endsWith("'")) signal.add(element + "'");
            return element;
        });
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        signal.add("a");

        assertEquals(List.of("a'", "a"), List.copyOf(signal), "内层先落地");
        assertEquals(2, notifications.get(), "内外各通知一次, 不抛重入");
    }

    @Test
    void injectedWrapperKeepsASideTableInSyncAndDiesWithItsHost() {
        Map<Object, Object> sideTable = new HashMap<>();
        WeakReference<?> probe = injectAndUse(sideTable);
        GcSupport.awaitCollected(probe);

        assertTrue(sideTable.isEmpty());
    }

    private static WeakReference<?> injectAndUse(Map<Object, Object> sideTable) {
        MapSignal<String, Object> injected = MapSignal.wrap(new HashMap<>());
        Subscription putHook = injected.beforePut((key, value) -> {
            Object proxy = new Object[]{value};
            sideTable.put(key, proxy);
            return proxy;
        });
        Subscription removeHook = injected.afterRemove(sideTable::remove);
        Object[] host = {injected};
        injected.put("pos", "block entity");

        assertTrue(injected.get("pos") instanceof Object[], "表里存的是代理");
        assertSame(injected.get("pos"), sideTable.get("pos"));
        injected.remove("pos");

        assertTrue(sideTable.isEmpty());
        assertFalse(putHook.isClosed());
        assertFalse(removeHook.isClosed());
        return new WeakReference<>(host);
    }
}
