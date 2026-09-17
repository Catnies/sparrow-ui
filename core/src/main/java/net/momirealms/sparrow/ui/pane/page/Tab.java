package net.momirealms.sparrow.ui.pane.page;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 在若干片界面之间切换, 每个标签是一个完整的子 Pane, 选中哪个就显示哪个.
 * <p>标签按钮由使用方点击里调 {@link #select(Object)}, 而显示挂在 {@link #selected()} 上,
 * 选中态自己比一下 {@code selected().get()} 就行. 内容区交给 Builder 的 {@code addIngredient(identifier, tab)},
 * 它把区域按二维形状连接到当前子 Pane, 切换标签时整片重铺.
 *
 * <pre>{@code
 * Tab<Category> tabs = Tab.lazy(Map.of(
 *         Category.WEAPONS, () -> weaponsPane(),
 *         Category.MARKET, () -> marketPane()), Category.WEAPONS);
 *
 * NormalPane pane = Pane.builder("WM#######", "VVVVVVVVV", "VVVVVVVVV")
 *         .addIngredient('V', tabs)
 *         .addIngredient('W', Item.builder()
 *                 .dependsOn(tabs.selected())
 *                 .setItemProvider(context -> tabIcon(tabs, Category.WEAPONS))
 *                 .addClickHandler(click -> tabs.select(Category.WEAPONS))
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>每个标签换的是一整片界面: 子 Pane 里的布局, 按钮和数据绑定都是它自己的.
 * 只想换一条序列时, 给每个标签建一个各自 {@code addIngredient} 投影的子 Pane 就是了.
 * <p>标签的先后顺序不归它管, 按钮顺序由使用方决定.
 *
 * @param <K> 标识一个标签的 key 类型
 */
public final class Tab<K> {
    private final Set<K> keys;                                 // 合法标签集, 建组时定死
    private final Function<? super K, ? extends Pane> paneOf;  // 取某个标签的子 Pane, lazy 版内含建造缓存
    private final MutableSignal<K> selected;                    // 当前选中的 key, 切标签就是写它
    private final Signal<Pane> pane;                            // 跟着选中走的子 Pane, 内容区显示的就是它

    /**
     * 把现成的几个子 Pane 组成一组标签.
     * <p>建出来之后每个子 Pane 都活着, 挂在它们身上的投影从这一刻起就在跟随数据源.
     *
     * @param <K> 标签 key 类型
     * @param tabs 每个 key 对应一个子 Pane, <strong>不能为空</strong>, <strong>会复制一份</strong>
     * @param initial 一开始选中哪一个, 必须是 {@code tabs} 里有的 key
     * @return 标签组
     * @throws IllegalArgumentException tabs 里没有 initial 时
     */
    @NotNull
    public static <K> Tab<K> of(@NotNull Map<K, ? extends Pane> tabs, @NotNull K initial) {
        Map<K, ? extends Pane> copied = Map.copyOf(tabs);
        return new Tab<>(copied.keySet(), copied::get, initial);
    }

    /**
     * 只给建造函数, 某个标签第一次显示的时候才把那个子 Pane 建出来并缓存住.
     * <p>已经建出来的 Pane 切走之后照样跟着自己的数据源.
     *
     * @param <K> 标签 key 类型
     * @param tabs 每个 key 对应一个子 Pane 的建造函数, <strong>不能为空, 也不能返回 null</strong>
     * @param initial 一开始选中哪一个, 必须是 {@code tabs} 里有的 key
     * @return 标签组
     * @throws IllegalArgumentException tabs 里没有 initial 时
     */
    @NotNull
    public static <K> Tab<K> lazy(@NotNull Map<K, ? extends Supplier<? extends Pane>> tabs, @NotNull K initial) {
        Map<K, ? extends Supplier<? extends Pane>> copied = Map.copyOf(tabs);
        Map<K, Pane> built = new ConcurrentHashMap<>();
        return new Tab<>(
                copied.keySet(),
                key -> built.computeIfAbsent(key, k -> Objects.requireNonNull(copied.get(k).get(), () -> "tab supplier returned null for key: " + k)),
                initial
        );
    }

    private Tab(Set<K> keys, Function<? super K, ? extends Pane> paneOf, K initial) {
        if (!keys.contains(initial)) {
            throw new IllegalArgumentException("no tab for key: " + initial);
        }
        this.keys = keys;
        this.paneOf = paneOf;
        this.selected = Signal.of(initial);
        this.pane = this.selected.map(paneOf);
    }

    /**
     * 切到这个标签, 内容区之后显示的就是它的子 Pane.
     *
     * @param key 目标 key
     * @throws IllegalArgumentException {@code key} 不在这组标签里时
     */
    public void select(@NotNull K key) {
        if (!this.keys.contains(key)) {
            throw new IllegalArgumentException("no tab for key: " + key);
        }
        this.selected.set(key);
    }

    /**
     * 当前选中的是哪个 key, 按钮的选中态挂在它上面.
     *
     * @return 当前选中的 key
     */
    @NotNull
    public Signal<K> selected() {
        return this.selected;
    }

    /**
     * 跟着选中走的子 Pane, 内容区铺的就是它.
     *
     * @return 当前选中的子 Pane
     */
    @NotNull
    public Signal<Pane> pane() {
        return this.pane;
    }
}
