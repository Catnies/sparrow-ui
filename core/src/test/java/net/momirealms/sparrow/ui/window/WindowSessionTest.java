package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.time.TickingTestSupport;
import net.momirealms.sparrow.ui.visual.VisualLayer;
import net.momirealms.sparrow.ui.window.handle.AnvilMenuHandle;
import net.momirealms.sparrow.ui.window.handle.BrewingMenuHandle;
import net.momirealms.sparrow.ui.window.handle.CartographyMenuHandle;
import net.momirealms.sparrow.ui.window.handle.CrafterMenuHandle;
import net.momirealms.sparrow.ui.window.handle.EnchantmentMenuHandle;
import net.momirealms.sparrow.ui.window.handle.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuHandle;
import net.momirealms.sparrow.ui.window.handle.MerchantMenuHandle;
import net.momirealms.sparrow.ui.window.handle.RecipeBookMenuHandle;
import net.momirealms.sparrow.ui.window.handle.StonecutterMenuHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSessionTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        SparrowUiTestRuntime.restoreOwnership();
        MockBukkit.unmock();
    }

    @Test
    void openCreatesARootSessionAndOpenNextJoinsIt() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = window(manager, player);

        assertNull(root.session(), "打开前不属于任何会话");
        assertEquals(Window.OpenResult.OPENED, root.open().join());
        WindowSession session = root.session();

        assertNotNull(session, "直接打开的窗成为根窗, 会话在此刻诞生");
        assertEquals(WindowSession.Kind.STACK, session.kind(), "默认型是 STACK");
        assertTrue(session.active());
        assertSame(root, session.current());
        assertEquals(List.of(root), session.chain());
        assertFalse(session.hasBack());
        assertSame(second, root.navigate(second).join());
        assertSame(session, second.session(), "openNext 的目标归属上一扇所在的会话, 不建新会话");
        assertEquals(List.of(root, second), session.chain());
        assertSame(second, session.current());
        assertTrue(session.hasBack());
        assertTrue(second.isOpen());
        assertFalse(root.isOpen(), "当前窗之外的成员保持关闭, 只有当前窗是打开的");
    }

    @Test
    void chainHandoffClosesReplacedWindowWithoutEndingTheSession() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        List<WindowCloseReason> rootCloseReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, rootCloseReasons::add, endReasons::add);
        AbstractWindow<?> second = window(manager, player);
        root.open().join();
        root.navigate(second).join();

        assertEquals(List.of(WindowCloseReason.OPEN_NEW), rootCloseReasons);
        assertEquals(List.of(), endReasons);
        assertTrue(root.session().active());
    }

    @Test
    void stackDoesNotDeduplicateSoTheSameWindowCanBePushedAgain() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> first = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        first.open().join();
        first.navigate(second).join();

        assertSame(first, second.navigate(first).join(), "STACK 不查重, 同一实例可以再压一层");
        WindowSession session = first.session();

        assertEquals(List.of(first, second, first), session.chain(), "环形栈是三层, 不是截断");
        assertTrue(first.isOpen());
    }

    @Test
    void backPopsTheTopAndDropsItsSessionMembership() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        root.open().join();
        root.navigate(second).join();
        WindowSession session = root.session();

        assertSame(root, second.back().join(), "完成时给出的是返回后的新当前窗");
        assertEquals(List.of(root), session.chain());
        assertSame(root, session.current(), "返回复用的是原来的 Window 实例");
        assertTrue(root.isOpen());
        assertFalse(second.isOpen());
        assertNull(second.session(), "弹出的窗不再属于任何会话");
        assertTrue(session.active());
    }

    @Test
    void poppingACircularStackKeepsTheDeeperOccurrenceMembership() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> first = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        first.open().join();
        first.navigate(second).join();
        second.navigate(first).join();
        WindowSession session = first.session();

        assertSame(second, first.back().join(), "弹出顶层的 first, 回到 second");
        assertSame(session, first.session(), "first 在栈的更深处还压着, 仍是会话成员");
        assertEquals(List.of(first, second), session.chain());
        assertSame(first, second.back().join());
        assertNull(second.session(), "second 不再出现在栈中, 引用被丢弃");
        assertEquals(List.of(first), session.chain());
    }

    @Test
    void backAtChainRootDoesNothingAndKeepsTheWindowOpen() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        root.open().join();

        assertNull(root.back().join(), "根窗没有上一扇可回, back 不做任何事");
        assertTrue(root.isOpen());
        assertTrue(root.session().active());
        assertEquals(List.of(), endReasons);
    }

    @Test
    void backOrCloseAtChainRootClosesAndEndsTheSession() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        AbstractWindow<?> top = window(manager, player);
        root.open().join();
        root.navigate(top).join();

        assertSame(root, top.backOrClose().join(), "有上一扇时与 back 等同");
        assertNull(root.backOrClose().join(), "根窗走关闭路径, 以 null 完成");
        assertFalse(root.isOpen(), "根窗没有上一扇可回, 等同关闭");
        assertNull(root.session());
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons);
    }

    @Test
    void backOnAPoppedWindowDoesNothing() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        root.open().join();
        root.navigate(second).join();
        second.back().join();

        assertNull(second.back().join(), "弹出的窗不属于任何会话, back 不做任何事");
        assertFalse(second.isOpen());
        assertTrue(root.isOpen(), "当前当前窗不受影响");
    }

    @Test
    void openingAWindowOutsideTheSessionEndsItWithOpenNew() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> inSession = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        AbstractWindow<?> outside = window(manager, player);
        inSession.open().join();
        WindowSession session = inSession.session();

        assertEquals(Window.OpenResult.OPENED, outside.open().join());
        assertFalse(session.active());
        assertEquals(List.of(WindowCloseReason.OPEN_NEW), endReasons);
        assertEquals(List.of(), session.chain());
        assertNull(session.current());
        assertNull(inSession.session(), "被顶替后不再属于任何会话");
        assertTrue(outside.isOpen());
        assertNotNull(outside.session(), "会话外窗口自己成为新根窗");
    }

    @Test
    void endClosesTheCurrentWindowAndFiresEndHandlersExactlyOnce() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        List<WindowCloseReason> windowCloseReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, windowCloseReasons::add, endReasons::add);
        root.open().join();
        WindowSession session = root.session();

        assertEquals(WindowSession.EndResult.ENDED, session.end().join());
        assertFalse(session.active());
        assertFalse(root.isOpen());
        assertNull(root.session());
        assertEquals(List.of(WindowCloseReason.PLUGIN), windowCloseReasons);
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons);
        assertNull(manager.current(player));
        assertEquals(WindowSession.EndResult.ALREADY_ENDED, session.end().join());
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons, "结束处理器恰好触发一次");
    }

    @Test
    void endedWindowsStartFreshChainsWithFreshSessions() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        root.open().join();
        WindowSession first = root.session();
        first.end().join();

        assertEquals(Window.OpenResult.OPENED, root.open().join());
        WindowSession second = root.session();

        assertNotNull(second);
        assertTrue(second.active());
        assertFalse(first.active(), "旧会话不复活, 重开是新会话");
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons, "旧会话的处理器不再触发");
    }

    @Test
    void failedOpenCreatesNoSession() {
        WindowManager manager = manager();
        Player player = unavailablePlayer();
        AbstractWindow<?> root = window(manager, player);

        assertEquals(Window.OpenResult.VIEWER_UNAVAILABLE, root.open().join());
        assertNull(root.session(), "打开失败不留下任何会话");
    }

    @Test
    void openNextRejectsAWindowBelongingToAnotherViewer() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        Player otherPlayer = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> otherWindow = window(manager, otherPlayer);
        root.open().join();

        assertThrows(IllegalArgumentException.class, () -> root.navigate(otherWindow));
    }

    @Test
    void shutdownEndsActiveSessions() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        root.open().join();
        WindowSession session = root.session();
        manager.shutdown();

        assertFalse(session.active());
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons);
        assertFalse(root.isOpen());
    }

    @Test
    void playerCloseReturnsToTheSourceWhenTheWindowAsksForIt() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        List<WindowCloseReason> topCloseReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        AbstractWindow<?> top = window(manager, player, topCloseReasons::add);
        top.backOnPlayerClose(true);
        root.open().join();
        root.navigate(top).join();
        WindowSession session = root.session();
        manager.closeNow(top, WindowCloseReason.PLAYER);

        assertTrue(session.active(), "返回上一扇不是离开, 会话继续");
        assertEquals(List.of(), endReasons);
        assertEquals(List.of(root), session.chain());
        assertTrue(root.isOpen(), "上一扇以原实例重新打开");
        assertNull(top.session(), "被弹出的当前窗不再属于任何会话");
        assertEquals(List.of(WindowCloseReason.PLAYER), topCloseReasons, "窗口自己的关闭处理器照常触发");
    }

    @Test
    void playerCloseEndsTheSessionWhenTheWindowDoesNotAskForBack() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        AbstractWindow<?> top = window(manager, player);
        root.open().join();
        root.navigate(top).join();
        WindowSession session = root.session();
        manager.closeNow(top, WindowCloseReason.PLAYER);

        assertFalse(session.active(), "默认不返回, 玩家主动关闭就是离开整段交互");
        assertEquals(List.of(WindowCloseReason.PLAYER), endReasons);
        assertEquals(List.of(), session.chain());
        assertNull(root.session());
        assertFalse(root.isOpen());
    }

    @Test
    void playerCloseAtChainRootEndsTheSessionEvenWithBackEnabled() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        root.backOnPlayerClose(true);
        root.open().join();
        manager.closeNow(root, WindowCloseReason.PLAYER);

        assertFalse(root.session() != null && root.session().active(), "根窗没有上一扇可回, 开关也救不了");
        assertEquals(List.of(WindowCloseReason.PLAYER), endReasons);
    }

    @Test
    void programmaticCloseEndsTheSessionRegardlessOfTheBackSwitch() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        AbstractWindow<?> top = window(manager, player);
        top.backOnPlayerClose(true);
        root.open().join();
        root.navigate(top).join();
        WindowSession session = root.session();

        assertEquals(Window.CloseResult.CLOSED, top.close().join());
        assertFalse(session.active(), "开关只作用于玩家主动关闭, 插件关闭一律结束会话");
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons);
        assertEquals(List.of(), session.chain());
    }

    @Test
    void disconnectEndsTheSession() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        root.backOnPlayerClose(true);
        root.open().join();
        WindowSession session = root.session();
        invokeEventHandler(
                manager,
                "handleQuit",
                PlayerQuitEvent.class,
                new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED)
        );

        assertFalse(session.active());
        assertEquals(List.of(WindowCloseReason.DISCONNECT), endReasons);
        assertEquals(List.of(), session.chain());
        assertNull(root.session());
    }

    @Test
    void aClosedSessionDoesNotEndAgainWhenItsWindowsAreClosed() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, endReasons::add);
        root.open().join();
        WindowSession session = root.session();
        manager.closeNow(root, WindowCloseReason.PLAYER);

        assertEquals(List.of(WindowCloseReason.PLAYER), endReasons);
        root.close().join();
        session.end().join();

        assertEquals(List.of(WindowCloseReason.PLAYER), endReasons, "结束处理器恰好触发一次");
    }

    @Test
    void endHandlersCanStartAnotherSession() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> nextWindow = window(manager, player);
        AbstractWindow<?> root = rootWindow(manager, player, ignoredReason -> {}, ignoredReason -> nextWindow.open());
        root.open().join();
        WindowSession session = root.session();
        manager.closeNow(root, WindowCloseReason.PLAYER);

        assertFalse(session.active());
        assertTrue(nextWindow.isOpen(), "结束处理器里开启新会话是合法的");
        assertNotNull(nextWindow.session());
        assertTrue(nextWindow.session().active());
    }

    @Test
    void rootDeclarationsSelectTheSessionKind() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> treeRoot = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        treeRoot.open().join();

        assertEquals(WindowSession.Kind.TREE, treeRoot.session().kind(), "会话类型取自根窗声明");
    }

    @Test
    void declarationsOnANonRootWindowDoNotApply() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> secondDeclared = new ArrayList<>();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = rootWindow(manager, player, WindowSession.Kind.TREE, secondDeclared::add);
        root.open().join();
        root.navigate(second).join();
        WindowSession session = root.session();

        assertEquals(WindowSession.Kind.STACK, session.kind(), "接进既有会话, 自己的 kind 声明不生效");
        session.end().join();

        assertEquals(List.of(), secondDeclared, "非根窗的结束处理器声明不生效");
    }

    @Test
    void retainedStackPopsLikeAStackAndPoppedWindowsCanBePushedAgain() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.RETAINED_STACK, ignoredReason -> {});
        AbstractWindow<?> second = window(manager, player);
        root.open().join();
        WindowSession session = root.session();

        assertEquals(WindowSession.Kind.RETAINED_STACK, session.kind());
        root.navigate(second).join();

        assertSame(root, second.back().join());
        assertEquals(List.of(root), session.chain(), "弹出行为与 STACK 一致");
        assertNull(second.session(), "弹出的窗不在会话中, 归属解除");
        assertFalse(second.isOpen());
        assertSame(second, root.navigate(second).join(), "保留区的窗照常可以再压入");
        assertEquals(List.of(root, second), session.chain());
        assertSame(session, second.session());
    }

    @Test
    void treeReusesAnExistingMemberByMovingTheCursor() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        AbstractWindow<?> detail = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(detail).join();
        detail.back().join();

        assertSame(detail, root.navigate(detail).join(), "再次步入同一实例是复用, 不是新孩子");
        assertEquals(List.of(root, detail), session.chain(), "树中成员唯一, 不像环形栈那样再长一层");
        assertTrue(detail.isOpen());
    }

    @Test
    void treeStepIntoTheRootMovesTheCursorHome() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        AbstractWindow<?> child = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(child).join();

        assertSame(root, child.navigate(root).join(), "步入根是回到主菜单, 不是把根收成新成员");
        assertEquals(List.of(root), session.chain(), "根重新成为当前位置, 它上面仍然没有一扇");
        assertFalse(session.hasBack());
        assertSame(session, child.session(), "离开的子仍留在树上");
        assertTrue(root.isOpen());
    }

    @Test
    void treeBackKeepsMembersInTheSession() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        AbstractWindow<?> child = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(child).join();
        child.back().join();

        assertEquals(List.of(root), session.chain(), "chain 只含当前路径");
        assertSame(session, child.session(), "离开的子树留在树上, 仍是会话成员");
        assertFalse(child.isOpen());
    }

    @Test
    void treeBranchesAndCrossBranchStepMovesToTheExistingNode() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        AbstractWindow<?> categoryA = window(manager, player);
        AbstractWindow<?> detailX = window(manager, player);
        AbstractWindow<?> categoryB = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(categoryA).join();
        categoryA.navigate(detailX).join();
        detailX.back().join();
        categoryA.back().join();
        root.navigate(categoryB).join();

        assertEquals(List.of(root, categoryB), session.chain(), "分叉后 chain 只含当前路径, 别枝不出现");
        assertSame(session, categoryA.session());
        assertSame(session, detailX.session());
        assertSame(detailX, categoryB.navigate(detailX).join());
        assertEquals(List.of(root, categoryA, detailX), session.chain(), "detailX 的父仍是 categoryA");
        assertTrue(detailX.isOpen());
        assertSame(categoryA, detailX.back().join(), "back 沿原父链回退");
    }

    @Test
    void treeEscBackFollowsTheParentPath() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, endReasons::add);
        AbstractWindow<?> child = window(manager, player);
        child.backOnPlayerClose(true);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(child).join();
        manager.closeNow(child, WindowCloseReason.PLAYER);

        assertTrue(session.active(), "ESC 返回父节点, 会话继续");
        assertEquals(List.of(), endReasons);
        assertEquals(List.of(root), session.chain());
        assertTrue(root.isOpen());
        assertSame(session, child.session(), "TREE 的 ESC 返回不丢成员");
    }

    @Test
    void treeEndReleasesTheWholeTreeIncludingOtherBranches() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        List<WindowCloseReason> endReasons = new ArrayList<>();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, endReasons::add);
        AbstractWindow<?> branchA = window(manager, player);
        AbstractWindow<?> branchB = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(branchA).join();
        branchA.back().join();
        root.navigate(branchB).join();
        session.end().join();

        assertFalse(session.active());
        assertEquals(List.of(WindowCloseReason.PLUGIN), endReasons);
        assertNull(root.session());
        assertNull(branchA.session(), "别枝成员随会话结束一起释放");
        assertNull(branchB.session());
        assertEquals(List.of(), session.chain());
    }

    @Test
    void treeKeepsParentChainsAndReleasesEveryMemberOnALargeTree() {
        int depth = 512;
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        List<AbstractWindow<?>> spine = new ArrayList<>();
        root.open().join();
        WindowSession session = root.session();
        AbstractWindow<?> current = root;
        for (int index = 0; index < depth; index++) {
            AbstractWindow<?> next = window(manager, player);
            current.navigate(next).join();
            spine.add(next);
            current = next;
        }
        for (int index = 0; index < depth; index++) {
            current = (AbstractWindow<?>) current.back().join();
        }

        assertSame(root, current, "一路回退回到根");
        AbstractWindow<?> branch = window(manager, player);
        root.navigate(branch).join();
        branch.navigate(spine.get(depth - 1)).join();
        List<Window> expected = new ArrayList<>();
        expected.add(root);
        expected.addAll(spine);

        assertEquals(expected, session.chain());
        session.end().join();

        assertNull(root.session());
        assertNull(branch.session());
        for (int index = 0; index < spine.size(); index++) {
            assertNull(spine.get(index).session(), "整棵树的成员都随会话结束释放");
        }
    }

    @Test
    void hasBackTracksNavigationSoAButtonCanChooseItsFace() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        root.open().join();
        WindowSession session = root.session();

        assertFalse(session.hasBack(), "根上没有上一扇, 按钮应显示关闭");
        root.navigate(second).join();

        assertTrue(session.hasBack(), "有上一扇可回, 按钮应显示返回");
        second.back().join();

        assertFalse(session.hasBack());
    }

    @Test
    void hasBackInACircularStackCountsPositionsNotMembers() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> first = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        first.open().join();
        first.navigate(second).join();
        second.navigate(first).join();
        WindowSession session = first.session();

        assertTrue(session.hasBack(), "环形栈按位置算: [first, second, first] 有两扇可回");
        first.back().join();

        assertTrue(session.hasBack());
        second.back().join();

        assertFalse(session.hasBack(), "回到根, 尽管 first 出现过两次");
    }

    @Test
    void treeHasBackFollowsTheCursorPosition() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = rootWindow(manager, player, WindowSession.Kind.TREE, ignoredReason -> {});
        AbstractWindow<?> child = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(child).join();

        assertTrue(session.hasBack());
        child.back().join();

        assertFalse(session.hasBack(), "位置回到根就没有上一扇, 别枝上的成员不算");
        assertSame(session, child.session(), "尽管 child 仍留在树上");
    }

    @Test
    void openHandlerSeesTheSessionAlreadyPointingAtTheOpenedWindow() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> child = window(manager, player);
        List<String> rootView = new ArrayList<>();
        List<String> childView = new ArrayList<>();
        root.addOpenHandler(() -> recordSessionView(rootView, root));
        child.addOpenHandler(() -> recordSessionView(childView, child));
        root.open().join();

        assertEquals(List.of("session=own", "current=self", "chain=1"), rootView, "根窗打开时会话已经就位");
        root.navigate(child).join();

        assertEquals(List.of("session=own", "current=self", "chain=2"), childView, "会话内打开子窗时同样就位");
    }

    @Test
    void openHandlerOnBackSeesThePathAlreadyBackAtTheSourceWindow() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> child = window(manager, player);
        List<String> rootView = new ArrayList<>();
        root.addOpenHandler(() -> recordSessionView(rootView, root));
        root.open().join();
        root.navigate(child).join();
        rootView.clear();
        child.back().join();

        assertEquals(List.of("session=own", "current=self", "chain=1"), rootView, "回退重开上一扇时路径已经退回来了");
    }

    private static void recordSessionView(List<String> view, AbstractWindow<?> window) {
        WindowSession session = window.session();
        view.add("session=" + (session == null ? "null" : "own"));
        view.add("current=" + (session == null ? "null" : session.current() == window ? "self" : "other"));
        view.add("chain=" + (session == null ? "null" : String.valueOf(session.chain().size())));
    }

    @Test
    void bindFiresTheCallbackWithTheSessionOnEveryDirtyMark() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        MutableSignal<Integer> signal = Signal.of(0);
        List<WindowSession> callbacks = new ArrayList<>();
        Subscription binding = session.bind(signal, callbacks::add);

        assertFalse(binding.isClosed());
        assertEquals(List.of(), callbacks, "绑定不补发当前值");
        signal.set(1);

        assertEquals(List.of(session), callbacks, "回调收到的是会话自己");
        signal.set(2);

        assertEquals(List.of(session, session), callbacks);
    }

    @Test
    void closingABindingStopsTheCallback() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        MutableSignal<Integer> signal = Signal.of(0);
        List<WindowSession> callbacks = new ArrayList<>();
        Subscription binding = session.bind(signal, callbacks::add);
        signal.set(1);
        binding.close();
        signal.set(2);

        assertTrue(binding.isClosed());
        assertEquals(List.of(session), callbacks, "解绑之后不再回调");
    }

    @Test
    void endingTheSessionDetachesItsBindingsAtOnce() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        MutableSignal<Integer> signal = Signal.of(0);
        List<WindowSession> callbacks = new ArrayList<>();
        session.bind(signal, callbacks::add);

        assertEquals(1, TickingTestSupport.entryCountOf(signal));
        session.end().join();

        assertEquals(0, TickingTestSupport.entryCountOf(signal), "结束后不该继续占着上游的订阅表");
        signal.set(1);

        assertEquals(List.of(), callbacks, "结束后不再回调");
        session.bind(signal, callbacks::add);
        signal.set(2);

        assertEquals(0, TickingTestSupport.entryCountOf(signal));
        assertEquals(List.of(), callbacks);
    }

    @Test
    void openNextAcceptsAWindowThatIsStillBeingBuilt() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> source = window(manager, player);
        CompletableFuture<AbstractWindow<?>> building = new CompletableFuture<>();

        assertEquals(Window.OpenResult.OPENED, source.open().join());
        CompletableFuture<Window> opening = source.navigate(building);

        assertFalse(opening.isDone(), "构建还没完成, 打开也就还没发生");
        AbstractWindow<?> next = window(manager, player);
        building.complete(next);

        assertSame(next, opening.join());
        assertTrue(next.isOpen());
        assertSame(source, next.back().join());
        assertTrue(source.isOpen(), "异步构建的窗口同样能回到上一扇");
    }

    @Test
    void openNextDropsTheResultWhenTheSourceWasClosedWhileBuilding() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> source = window(manager, player);
        AbstractWindow<?> next = window(manager, player);
        CompletableFuture<AbstractWindow<?>> building = new CompletableFuture<>();
        source.open().join();
        WindowSession session = source.session();
        CompletableFuture<Window> opening = source.navigate(building);
        source.close().join();
        building.complete(next);

        assertNull(opening.join(), "出发窗已经关闭, 迟到的构建结果不再打开");
        assertFalse(next.isOpen());
        assertFalse(source.isOpen(), "已经关掉的菜单不会被重新拉起来");
        assertFalse(session.active());
        assertNull(manager.current(player));
    }

    @Test
    void openNextDropsTheResultWhenTheSourceWasReplacedWhileBuilding() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> source = window(manager, player);
        AbstractWindow<?> external = window(manager, player);
        AbstractWindow<?> late = window(manager, player);
        CompletableFuture<AbstractWindow<?>> building = new CompletableFuture<>();
        source.open().join();
        WindowSession session = source.session();
        CompletableFuture<Window> opening = source.navigate(building);
        external.open().join();
        building.complete(late);

        assertNull(opening.join(), "出发窗已被会话外 Window 顶替, 迟到的构建结果不再打开");
        assertFalse(late.isOpen());
        assertSame(external, manager.current(player), "玩家看到的仍是顶替进来的那扇");
        assertFalse(session.active());
        assertNull(source.session());
    }

    @Test
    void navigateFromAMemberThatLeftTheCurrentPositionDoesNothing() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> deeper = window(manager, player);
        AbstractWindow<?> late = window(manager, player);
        root.open().join();
        WindowSession session = root.session();
        root.navigate(deeper).join();

        assertNull(root.navigate(late).join(), "位置已经不在 root 上, 它不能再从自己出发导航");
        assertFalse(late.isOpen());
        assertSame(deeper, manager.current(player), "玩家看到的仍是当前窗");
        assertSame(session, root.session(), "出发窗的归属没有被第二段会话覆盖");
        assertEquals(List.of(root, deeper), session.chain());
        assertTrue(session.active());
    }

    @Test
    void aFailedCandidateOpenLeavesTheSessionUntouched() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> second = window(manager, player);
        root.open().join();
        root.navigate(second).join();
        WindowSession session = root.session();

        assertNull(second.navigate(second).join());
        assertEquals(List.of(root, second), session.chain());
        assertSame(second, session.current());
        assertSame(session, second.session());
        assertSame(session, root.session());
        assertTrue(session.active());
        assertSame(second, manager.current(player));
    }

    @Test
    void openNextDropsTheResultWhenTheSourceLeftTheCurrentPosition() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> root = window(manager, player);
        AbstractWindow<?> deeper = window(manager, player);
        AbstractWindow<?> late = window(manager, player);
        CompletableFuture<AbstractWindow<?>> building = new CompletableFuture<>();
        root.open().join();
        WindowSession session = root.session();
        CompletableFuture<Window> opening = root.navigate(building);
        root.navigate(deeper).join();
        building.complete(late);

        assertNull(opening.join(), "位置已经不在出发窗上, 迟到的构建结果不再打开");
        assertFalse(late.isOpen());
        assertSame(deeper, manager.current(player), "玩家看到的仍是后来打开的那扇");
        assertSame(session, root.session(), "出发窗仍归原会话所有");
        assertEquals(List.of(root, deeper), session.chain());
    }

    @Test
    void openNextCompletesWithNullWhenTheWindowCannotOpen() {
        WindowManager manager = manager();
        Player player = unavailablePlayer();
        AbstractWindow<?> source = window(manager, player);
        AbstractWindow<?> next = window(manager, player);

        assertNull(source.navigate(next).join(), "玩家不可用时以 null 完成");
        assertFalse(next.isOpen());
        assertNull(next.session());
    }

    @Test
    void dataIsCarriedFromBuilderToWindow() {
        manager();
        Player player = availablePlayer();
        Object menu = new Object();
        Window window = Window.builder(Pane.empty(9, 1))
                .setLowerPane(Pane.empty(9, 4))
                .setData(menu)
                .build(player);

        assertSame(menu, window.data());
        assertSame(menu, window.data(Object.class), "type.cast 便捷读给出同一引用");
    }

    @Test
    void dataDefaultsToNullAndMismatchedTypeThrows() {
        manager();
        Player player = availablePlayer();
        Window plain = Window.builder(Pane.empty(9, 1)).setLowerPane(Pane.empty(9, 4)).build(player);

        assertNull(plain.data());
        assertNull(plain.data(String.class), "未携带时 type 读同样给 null");
        Window carrying = Window.builder(Pane.empty(9, 1))
                .setLowerPane(Pane.empty(9, 4))
                .setData("menu")
                .build(player);

        assertEquals("menu", carrying.data(String.class));
        assertThrows(ClassCastException.class, () -> carrying.data(Integer.class), "挂错类型在读取处显现");
    }

    @Test
    void sessionIsNullBeforeTheWindowOpens() {
        WindowManager manager = manager();
        Player player = availablePlayer();
        AbstractWindow<?> window = window(manager, player);

        assertNull(window.session(), "build 后未打开的窗不属于任何会话");
    }

    @Test
    void builderCarriesSessionRootDeclarationsIntoSettings() {
        manager();
        Player player = availablePlayer();
        Consumer<WindowCloseReason> handler = ignoredReason -> {};
        AbstractWindow<?> declared = (AbstractWindow<?>) Window.builder(Pane.empty(9, 1))
                .setLowerPane(Pane.empty(9, 4))
                .setSessionKind(WindowSession.Kind.TREE)
                .addSessionEndHandler(handler)
                .build(player);

        assertEquals(WindowSession.Kind.TREE, declared.rootSessionKind());
        assertEquals(List.of(handler), declared.rootSessionEndHandlers());
        AbstractWindow<?> defaults = (AbstractWindow<?>) Window.builder(Pane.empty(9, 1))
                .setLowerPane(Pane.empty(9, 4))
                .build(player);

        assertEquals(WindowSession.Kind.STACK, defaults.rootSessionKind(), "默认型是 STACK");
        assertEquals(List.of(), defaults.rootSessionEndHandlers());
    }

    private static <E> void invokeEventHandler(WindowManager manager, String methodName, Class<E> eventType, E event) {
        try {
            Method method = WindowManager.class.getDeclaredMethod(methodName, eventType);
            method.setAccessible(true);
            method.invoke(manager, event);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("WindowManager event handler failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to invoke WindowManager event handler", exception);
        }
    }

    private static WindowManager manager() {
        SparrowUiTestRuntime.installOwnership(() -> true);
        WindowManager manager = new WindowManager(SparrowUiTestRuntime.plugin(), new SessionMenuFactory(), new FoliaExecutor(SparrowUiTestRuntime.plugin()));
        SparrowUiTestRuntime.install(manager);
        return manager;
    }

    private static AbstractWindow<?> window(WindowManager manager, Player player) {
        return window(manager, player, ignoredReason -> {});
    }

    private static AbstractWindow<?> window(WindowManager manager, Player player, Consumer<WindowCloseReason> closeHandler) {
        return window(manager, player, closeHandler, WindowSession.Kind.STACK, List.of());
    }

    private static AbstractWindow<?> rootWindow(
            WindowManager manager,
            Player player,
            Consumer<WindowCloseReason> closeHandler,
            Consumer<WindowCloseReason> sessionEndHandler
    ) {
        return window(manager, player, closeHandler, WindowSession.Kind.STACK, List.of(sessionEndHandler));
    }

    private static AbstractWindow<?> rootWindow(
            WindowManager manager,
            Player player,
            WindowSession.Kind kind,
            Consumer<WindowCloseReason> sessionEndHandler
    ) {
        return window(manager, player, ignoredReason -> {}, kind, List.of(sessionEndHandler));
    }

    private static AbstractWindow<?> window(
            WindowManager manager,
            Player player,
            Consumer<WindowCloseReason> closeHandler,
            WindowSession.Kind sessionKind,
            List<Consumer<WindowCloseReason>> sessionEndHandlers
    ) {
        return new NormalWindowImpl(
                manager,
                player,
                WindowLayout.split(Pane.empty(9, 1), Pane.empty(9, 4)),
                new AbstractWindow.Settings(
                        Component::empty,
                        true,
                        List.of(),
                        List.of(closeHandler),
                        List.of(),
                        false,
                        null,
                        sessionKind,
                        sessionEndHandlers,
                        0,
                        List.of(),
                        VisualLayer.NONE,
                        VisualLayer.NONE
                )
        );
    }

    private static Player availablePlayer() {
        return player(true);
    }

    private static Player unavailablePlayer() {
        return player(false);
    }

    private static Player player(boolean available) {
        UUID playerId = UUID.randomUUID();
        EntityScheduler scheduler = new InlineEntityScheduler();
        return (Player) Proxy.newProxyInstance(
                WindowSessionTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getScheduler" -> scheduler;
                    case "isValid", "isConnected" -> available;
                    case "isSleeping" -> false;
                    case "hashCode" -> playerId.hashCode();
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "SessionPlayerStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static final class InlineEntityScheduler implements EntityScheduler {
        @Override
        public boolean execute(@NonNull Plugin plugin, @NonNull Runnable task, Runnable retired, long delay) {
            task.run();
            return true;
        }
        @Override
        public ScheduledTask run(@NonNull Plugin plugin, @NonNull Consumer<ScheduledTask> task, Runnable retired) {
            task.accept(null);
            return scheduledTask();
        }
        @Override
        public ScheduledTask runDelayed(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                Runnable retired,
                long delay
        ) {
            throw new UnsupportedOperationException("runDelayed");
        }
        @Override
        public ScheduledTask runAtFixedRate(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                Runnable retired,
                long initialDelay,
                long period
        ) {
            return scheduledTask();
        }
    }

    private static ScheduledTask scheduledTask() {
        return (ScheduledTask) Proxy.newProxyInstance(
                WindowSessionTest.class.getClassLoader(),
                new Class<?>[]{ScheduledTask.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isCancelled" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "SessionTestTask";
                    default -> null;
                }
        );
    }

    private static final class SessionMenuFactory implements MenuFactory {
        @Override
        @NonNull
        public MenuHandle normal(@NonNull Player viewer, int rows, long generation) {
            return menuHandle();
        }
        @Override
        @NonNull
        public MenuHandle hopper(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public AnvilMenuHandle anvil(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public MenuHandle dispenser(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public MenuHandle dropper(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public MenuHandle grindstone(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public MenuHandle smithing(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public BrewingMenuHandle brewing(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public CartographyMenuHandle cartography(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public CrafterMenuHandle crafter(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public RecipeBookMenuHandle crafting(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public FurnaceMenuHandle furnace(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public FurnaceMenuHandle smoker(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public FurnaceMenuHandle blastFurnace(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public EnchantmentMenuHandle enchantment(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public StonecutterMenuHandle stonecutter(@NonNull Player viewer, long generation) {
            throw new AssertionError("session test only opens normal Windows");
        }
        @Override
        @NonNull
        public MerchantMenuHandle merchant(
                @NonNull Player viewer,
                long generation,
                @NonNull MerchantWindow window,
                @NonNull BiConsumer<? super String, ? super Throwable> reporter
        ) {
            throw new AssertionError("session test only opens normal Windows");
        }
    }

    private static MenuHandle menuHandle() {
        return (MenuHandle) Proxy.newProxyInstance(
                WindowSessionTest.class.getClassLoader(),
                new Class<?>[]{MenuHandle.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "cursor" -> arguments == null || arguments.length == 0 ? ItemStack.empty() : null;
                    case "drainInputs" -> List.of();
                    case "accepts", "hasInputOverflowed" -> false;
                    case "containerId", "stateId" -> 0;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "SessionTestMenu";
                    default -> null;
                }
        );
    }
}
