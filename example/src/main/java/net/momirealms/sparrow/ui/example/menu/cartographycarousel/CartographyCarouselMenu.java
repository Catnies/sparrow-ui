package net.momirealms.sparrow.ui.example.menu.cartographycarousel;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.CartographyWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 使用制图台三个原生槽位展示远程图片轮播和预览形态切换.
 *
 * <p>本示例把一次打开分为两个明确阶段:
 * <ol>
 *     <li>通过 JDK HttpClient 异步下载三张 128x128 图片, 并只缓存在内存中.</li>
 *     <li>回到目标玩家的实体线程构建 Window, 再由 {@link Window#open()} 完成打开.</li>
 * </ol>
 *
 * <p>制图台左侧 raw slot 0 和 1 分别切换上一张与下一张图片, 右侧 raw slot 2
 * 在 {@link CartographyWindow.View} 的四种预览形态之间循环.
 */
public final class CartographyCarouselMenu {
    private static final int MAP_SIZE = CartographyWindow.MAP_SIZE; // 完整地图画布的边长
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;         // 单张远程图片允许占用的最大响应字节数
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10); // 每张图片从请求到完整响应的超时
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final List<SlideSource> SLIDE_SOURCES = List.of(
            new SlideSource(
                    "奶龙",
                    URI.create("https://i0.hdslb.com/bfs/bangumi/image/b13bdf2f38681f3420ddbde03fe3092642424b9b.png@128w_128h_1c.jpg")
            ),
            new SlideSource(
                    "永雏塔菲",
                    URI.create("https://i1.hdslb.com/bfs/face/5ddddba98f0265265662a8f7d5383e528a98412b.jpg@128w_128h_1c.jpg")
            ),
            new SlideSource(
                    "千早爱音",
                    URI.create("https://i0.hdslb.com/bfs/new_dyn/8b3ac308f638c610d7de3d2c2df82d16169208916.jpg@128w_128h_1c.jpg")
            )
    ); // 固定顺序同时决定初始图片和前后翻页顺序
    private static final List<ViewOption> VIEW_OPTIONS = List.of(
            new ViewOption(CartographyWindow.View.NORMAL, "普通预览", Material.STONE, "保持地图的普通预览大小。"),
            new ViewOption(CartographyWindow.View.SMALL, "缩小预览", Material.PAPER, "模拟放入纸张后的缩小结果。"),
            new ViewOption(CartographyWindow.View.DUPLICATE, "复制预览", Material.MAP, "模拟使用空地图复制当前地图。"),
            new ViewOption(CartographyWindow.View.LOCK, "锁定预览", Material.GLASS_PANE, "模拟使用玻璃板锁定当前地图。")
    ); // 展示顺序与按钮循环顺序保持一致
    private static CompletableFuture<List<Slide>> slideCache; // 首次成功下载后由后续菜单会话共享

    private final List<Slide> slides;                 // 当前菜单会话使用的不可变图片快照
    private final MutableSignal<Integer> slideIndex; // 当前显示图片在 slides 中的下标
    private final MutableSignal<Integer> viewIndex;  // 当前预览形态在 VIEW_OPTIONS 中的下标
    private final CartographyWindow window;          // 承载三个控制槽位和虚拟地图画布的窗口

    /**
     * 异步准备图片，并在目标玩家的实体线程创建和打开菜单.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单打开请求的最终结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return CartographyCarouselMenu.loadSlides()
                .thenCompose(slides -> CartographyCarouselMenu.openOnViewerThread(viewer, slides));
    }

    /**
     * 在玩家实体线程创建一次互不共享状态的轮播会话.
     *
     * @param viewer 要查看菜单的玩家
     * @param slides 已完成远程解析的图片快照
     */
    private CartographyCarouselMenu(@NotNull Player viewer, @NotNull List<Slide> slides) {
        this.slides = List.copyOf(slides);
        this.slideIndex = Signal.of(0);
        this.viewIndex = Signal.of(0);

        // 制图台两个输入槽按纵向 1x2 Pane 映射为上一张和下一张
        NormalPane inputPane = Pane.builder("P", "N")
                .addIngredient('P', this.buildPreviousButton())
                .addIngredient('N', this.buildNextButton())
                .build();
        // 单独的 1x1 结果 Pane 对应右侧展示形态按钮
        NormalPane resultPane = Pane.builder("V")
                .addIngredient('V', this.buildViewButton())
                .build();

        // 默认下栏连接玩家背包, 因此整个构建过程必须留在玩家实体线程
        this.window = CartographyWindow.builder()
                .setTitle(Component.text("制图台轮播图", NamedTextColor.DARK_AQUA))
                .setInputPane(inputPane)
                .setResultPane(resultPane)
                .setMap(this.slides.get(0).image())
                .setView(VIEW_OPTIONS.get(0).view())
                .build(viewer);
    }

    /**
     * 创建位于第一个输入槽的上一张按钮. 该槽同时显示当前虚拟地图画布.
     *
     * @return 随当前图片自动更新的按钮
     */
    @NotNull
    private Item buildPreviousButton() {
        return Item.builder()
                .dependsOn(this.slideIndex)
                .setItemProvider(ignoredContext -> {
                    int currentIndex = this.slideIndex.get();
                    int targetIndex = Math.floorMod(currentIndex - 1, this.slides.size());
                    Slide current = this.slides.get(currentIndex);
                    Slide target = this.slides.get(targetIndex);

                    // raw slot 0 会附加虚拟地图编号, Filled Map 因而能直接展示当前画布
                    ItemStack itemStack = new ItemStack(Material.FILLED_MAP);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("上一张图片", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("向前浏览轮播图中的图片。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("当前图片: ", NamedTextColor.GRAY).append(Component.text(current.title(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("切换目标: ", NamedTextColor.GRAY).append(Component.text(target.title(), NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false),
                            Component.text("轮播进度: ", NamedTextColor.GRAY).append(Component.text((currentIndex + 1) + " / " + this.slides.size(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("点击切换到上一张", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.changeSlide(-1))
                .build();
    }

    /**
     * 创建位于第二个输入槽的下一张按钮.
     * 客户端材质会同时反映当前预览形态.
     *
     * @return 随当前图片自动更新的按钮
     */
    @NotNull
    private Item buildNextButton() {
        return Item.builder()
                .dependsOn(this.slideIndex)
                .setItemProvider(ignoredContext -> {
                    int currentIndex = this.slideIndex.get();
                    int targetIndex = (currentIndex + 1) % this.slides.size();
                    Slide current = this.slides.get(currentIndex);
                    Slide target = this.slides.get(targetIndex);

                    // raw slot 1 的客户端材质由当前 View 接管, 按钮名称和 Lore 仍来自这里
                    ItemStack itemStack = new ItemStack(Material.PAPER);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("下一张图片", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("向后浏览轮播图中的图片。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("当前图片: ", NamedTextColor.GRAY).append(Component.text(current.title(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("切换目标: ", NamedTextColor.GRAY).append(Component.text(target.title(), NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false),
                            Component.text("轮播进度: ", NamedTextColor.GRAY).append(Component.text((currentIndex + 1) + " / " + this.slides.size(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("点击切换到下一张", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.changeSlide(1))
                .build();
    }

    /**
     * 创建位于结果槽的展示形态按钮.
     * 左键向后循环, 右键向前循环.
     *
     * @return 随当前图片和预览形态自动更新的按钮
     */
    @NotNull
    private Item buildViewButton() {
        return Item.builder()
                .dependsOn(this.slideIndex, this.viewIndex)
                .setItemProvider(ignoredContext -> {
                    int currentViewIndex = this.viewIndex.get();
                    ViewOption currentView = VIEW_OPTIONS.get(currentViewIndex);
                    ViewOption nextView = VIEW_OPTIONS.get((currentViewIndex + 1) % VIEW_OPTIONS.size());
                    Slide currentSlide = this.slides.get(this.slideIndex.get());

                    ItemStack itemStack = new ItemStack(currentView.material());
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("展示形态 · " + currentView.title(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text(currentView.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("当前图片: ", NamedTextColor.GRAY).append(Component.text(currentSlide.title(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("当前形态: ", NamedTextColor.GRAY).append(Component.text(currentView.title(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("下一形态: ", NamedTextColor.GRAY).append(Component.text(nextView.title(), NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("左键切换下一种形态", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                            Component.text("右键返回上一种形态", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(click -> this.changeView(click.clickType().isRightClick() ? -1 : 1))
                .build();
    }

    /**
     * 循环切换图片并覆盖完整的 128x128 地图画布.
     *
     * @param offset 图片下标变化量, 本示例使用 -1 或 1
     */
    private void changeSlide(int offset) {
        int nextIndex = Math.floorMod(this.slideIndex.get() + offset, this.slides.size());
        this.window.applyPatch(0, 0, this.slides.get(nextIndex).image());
        this.slideIndex.set(nextIndex);
    }

    /**
     * 循环切换客户端制图预览形态.
     *
     * @param offset 形态下标变化量, 本示例使用 -1 或 1
     */
    private void changeView(int offset) {
        int nextIndex = Math.floorMod(this.viewIndex.get() + offset, VIEW_OPTIONS.size());
        this.window.setView(VIEW_OPTIONS.get(nextIndex).view());
        this.viewIndex.set(nextIndex);
    }

    /**
     * 把 Window 构建和打开入口调度到目标玩家的实体线程. 玩家提前退役时返回可识别结果.
     *
     * @param viewer 要查看菜单的玩家
     * @param slides 已完成远程解析的图片
     * @return Window 打开结果
     */
    @NotNull
    private static CompletableFuture<Window.OpenResult> openOnViewerThread(@NotNull Player viewer, @NotNull List<Slide> slides) {
        CompletableFuture<Window.OpenResult> result = new CompletableFuture<>();
        Runnable retired = () -> result.complete(Window.OpenResult.VIEWER_UNAVAILABLE);
        if (viewer.getScheduler().run(
                SparrowExample.INSTANCE,
                ignoredTask -> {
                    try {
                        CartographyCarouselMenu menu = new CartographyCarouselMenu(viewer, slides);
                        menu.window.open().whenComplete((openResult, throwable) -> {
                            if (throwable == null) {
                                result.complete(openResult);
                            } else {
                                result.completeExceptionally(throwable);
                            }
                        });
                    } catch (Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                },
                retired
        ) == null) {
            retired.run();
        }
        return result;
    }

    /**
     * 返回共享图片缓存. 上一次下载失败时允许下一次命令重新请求全部图片.
     *
     * @return 正在加载或已经完成的图片列表
     */
    @NotNull
    private static synchronized CompletableFuture<List<Slide>> loadSlides() {
        if (slideCache == null || slideCache.isCompletedExceptionally()) {
            slideCache = CartographyCarouselMenu.downloadSlides();
        }
        return slideCache;
    }

    /**
     * 并行下载全部固定图片, 全部成功后按声明顺序组成不可变列表.
     *
     * @return 三张远程图片的异步加载结果
     */
    @NotNull
    private static CompletableFuture<List<Slide>> downloadSlides() {
        List<CompletableFuture<Slide>> downloads = new ArrayList<>(SLIDE_SOURCES.size());
        for (int index = 0; index < SLIDE_SOURCES.size(); index++) {
            SlideSource source = SLIDE_SOURCES.get(index);
            HttpRequest request = HttpRequest.newBuilder(source.uri())
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "image/png,image/jpeg")
                    .header("User-Agent", "SparrowUI-Example/1.0")
                    .GET()
                    .build();
            downloads.add(HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> CartographyCarouselMenu.decodeSlide(source, response)));
        }

        CompletableFuture<?>[] pending = new CompletableFuture<?>[downloads.size()];
        for (int index = 0; index < downloads.size(); index++) {
            pending[index] = downloads.get(index);
        }
        return CompletableFuture.allOf(pending).thenApply(ignoredResult -> {
            List<Slide> slides = new ArrayList<>(downloads.size());
            for (int index = 0; index < downloads.size(); index++) {
                slides.add(downloads.get(index).join());
            }
            return List.copyOf(slides);
        });
    }

    /**
     * 校验一项 HTTP 响应并通过内存缓存流解析图片.
     *
     * @param source 图片名称与远程地址
     * @param response HTTP 字节响应
     * @return 已确认恰好为 128x128 的图片
     * @throws CompletionException HTTP 状态、内容类型、响应大小或图片格式不符合要求时抛出
     */
    @NotNull
    private static Slide decodeSlide(@NotNull SlideSource source, @NotNull HttpResponse<byte[]> response) {
        if (response.statusCode() != 200) {
            throw new CompletionException(new IOException("Image request for " + source.title() + " returned HTTP " + response.statusCode()));
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.regionMatches(true, 0, "image/", 0, "image/".length())) {
            throw new CompletionException(new IOException("Image request for " + source.title() + " returned " + contentType));
        }
        byte[] bytes = response.body();
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new CompletionException(new IOException("Image response for " + source.title() + " exceeds " + MAX_IMAGE_BYTES + " bytes"));
        }

        BufferedImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            // ImageIO.read(ImageInputStream) 会自行关闭传入流, 直接使用 Reader 让关闭权留在本方法
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new CompletionException(new IOException("Image response for " + source.title() + " is not supported by ImageIO"));
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new CompletionException(new IOException("Failed to decode image for " + source.title(), exception));
        }
        if (image.getWidth() != MAP_SIZE || image.getHeight() != MAP_SIZE) {
            throw new CompletionException(new IOException("Image for " + source.title() + " must be 128x128, got " + image.getWidth() + "x" + image.getHeight()));
        }
        return new Slide(source.title(), image);
    }

    /**
     * 保存下载前就能确定的图片名称与远程地址.
     *
     * @param title 菜单中展示的图片名称
     * @param uri 可直接返回图片内容的 HTTPS 地址
     */
    private record SlideSource(@NotNull String title, @NotNull URI uri) {
    }

    /**
     * 保存已经完整下载并解析到内存中的一张轮播图片.
     *
     * @param title 菜单中展示的图片名称
     * @param image 恰好为 128x128 的图片
     */
    private record Slide(@NotNull String title, @NotNull BufferedImage image) {
    }

    /**
     * 把 SparrowUI 预览模式与面向玩家的名称、图标和说明放在同一个阅读位置.
     *
     * @param view SparrowUI 预览模式
     * @param title 面向玩家的模式名称
     * @param material 右侧控制按钮使用的物品材质
     * @param description 面向玩家的模式说明
     */
    private record ViewOption(@NotNull CartographyWindow.View view, @NotNull String title, @NotNull Material material, @NotNull String description) {
    }
}
