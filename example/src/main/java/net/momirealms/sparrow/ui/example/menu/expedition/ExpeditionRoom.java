package net.momirealms.sparrow.ui.example.menu.expedition;

import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Member;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Phase;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Role;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Route;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 四人共享准备室. 房间只保存 UUID 和不可变快照, 玩家区域线程上的操作在这里串行提交.
 */
final class ExpeditionRoom implements AutoCloseable {
    private final MutableSignal<ExpeditionState> state = Signal.of(ExpeditionState.waiting(List.of(), "点击空位加入, 一起决定下一站。"));
    private final Function<Runnable, SchedulerTask> clock;
    @Nullable private SchedulerTask countdown;
    private int generation;
    private boolean closed;

    ExpeditionRoom(@NotNull Function<Runnable, SchedulerTask> clock) {
        this.clock = clock;
    }

    @NotNull
    Signal<ExpeditionState> state() {
        return this.state;
    }

    // 返回 null 表示操作成功, 非 null 是可直接显示给操作者的拒绝原因.
    @Nullable
    synchronized String join(@NotNull UUID player, @NotNull String name) {
        ExpeditionState current = this.state.get();
        if (this.closed) return "准备室已经关闭。";
        if (current.member(player) != null) return null;
        if (current.phase() == Phase.DEPARTED) return "小队已经出发, 请等待队长返回营地。";
        if (current.members().size() == ExpeditionState.CAPACITY) return "小队已满, 可以先旁观准备过程。";
        ArrayList<Member> members = new ArrayList<>(current.members());
        members.add(new Member(player, name, Role.UNSELECTED, null, false));
        this.waiting(members, true, name + " 加入了小队, 请全员重新准备。");
        return null;
    }

    @Nullable
    synchronized String leave(@NotNull UUID player) {
        ExpeditionState current = this.state.get();
        Member leaving = current.member(player);
        if (leaving == null) return "你还没有加入小队。";
        ArrayList<Member> members = new ArrayList<>(current.members());
        members.remove(leaving);
        String event = leaving.name() + " 离开了小队。";
        if (current.captain(player) && !members.isEmpty()) {
            event += "新队长为 " + members.getFirst().name() + "。";
        }
        this.waiting(members, true, event);
        return null;
    }

    @Nullable
    synchronized String chooseRole(@NotNull UUID player, @NotNull Role role) {
        ExpeditionState current = this.state.get();
        String problem = this.editProblem(current, player);
        if (problem != null) return problem;
        Member member = current.member(player);
        if (member.role() == role) return null;
        ArrayList<Member> members = new ArrayList<>(current.members());
        members.set(members.indexOf(member), new Member(player, member.name(), role, member.vote(), false));
        this.waiting(members, false, member.name() + " 选择了" + role.title + ", 需要重新准备。");
        return null;
    }

    @Nullable
    synchronized String vote(@NotNull UUID player, @NotNull Route route) {
        ExpeditionState current = this.state.get();
        String problem = this.editProblem(current, player);
        if (problem != null) return problem;
        Member member = current.member(player);
        if (member.vote() == route) return null;
        ArrayList<Member> members = new ArrayList<>(current.members());
        members.set(members.indexOf(member), new Member(player, member.name(), member.role(), route, false));
        this.waiting(members, true, member.name() + " 改投" + route.title + ", 请全员重新准备。");
        return null;
    }

    @Nullable
    synchronized String toggleReady(@NotNull UUID player) {
        ExpeditionState current = this.state.get();
        String problem = this.editProblem(current, player);
        if (problem != null) return problem;
        Member member = current.member(player);
        if (member.role() == Role.UNSELECTED) return "先选择下方的一项职责。";
        if (member.vote() == null) return "先为一个远征目的地投票。";
        ArrayList<Member> members = new ArrayList<>(current.members());
        members.set(members.indexOf(member), member.withReady(!member.ready()));
        this.waiting(members, false, member.name() + (member.ready() ? " 取消了准备。" : " 已经准备就绪。"));
        return null;
    }

    @Nullable
    synchronized String transferCaptain(@NotNull UUID player, @NotNull UUID target) {
        ExpeditionState current = this.state.get();
        String problem = this.editProblem(current, player);
        if (problem != null) return problem;
        if (!current.captain(player)) return "只有队长可以移交队长。";
        Member member = current.member(target);
        if (member == null) return "这名成员已经离开了小队。";
        if (player.equals(target)) return null;
        ArrayList<Member> members = new ArrayList<>(current.members());
        members.remove(member);
        members.addFirst(member);
        this.waiting(members, true, member.name() + " 接任队长, 请全员重新准备。");
        return null;
    }

    // 队长按钮依次承担出征、取消倒计时、返回营地三种操作.
    @Nullable
    synchronized String launch(@NotNull UUID player) {
        ExpeditionState current = this.state.get();
        if (this.closed) return "准备室已经关闭。";
        if (!current.captain(player)) return "只有队长能操作出征。";
        if (current.phase() != Phase.WAITING) {
            this.waiting(current.members(), true, current.phase() == Phase.COUNTDOWN ? "队长取消了出征, 请重新准备。" : "小队回到营地, 可以准备下一次远征。");
            return null;
        }
        String problem = current.launchProblem(player);
        if (problem != null) return problem;
        int ticket = ++this.generation;
        this.state.set(new ExpeditionState(current.members(), Phase.COUNTDOWN, 5, current.destination(), "全员就绪, 远征即将开始！"));
        this.countdown = this.clock.apply(() -> this.tick(ticket));
        return null;
    }

    private synchronized void tick(int ticket) {
        if (this.closed || ticket != this.generation) return;
        ExpeditionState current = this.state.get();
        if (current.seconds() == 1) {
            this.cancelCountdown();
            this.state.set(new ExpeditionState(current.members(), Phase.DEPARTED, 0, current.destination(), "出征成功！目的地为" + current.destination().title + "。"));
        } else {
            this.state.set(new ExpeditionState(current.members(), Phase.COUNTDOWN, current.seconds() - 1, current.destination(), current.event()));
        }
    }

    @Nullable
    private String editProblem(@NotNull ExpeditionState current, @NotNull UUID player) {
        if (this.closed) return "准备室已经关闭。";
        if (current.member(player) == null) return "请先点击空位或绿色按钮加入小队。";
        if (current.phase() == Phase.DEPARTED) return "本次远征已出发, 请等待队长返回营地。";
        return null;
    }

    private void waiting(@NotNull List<Member> members, boolean resetReady, @NotNull String event) {
        this.cancelCountdown();
        if (resetReady) {
            ArrayList<Member> reset = new ArrayList<>(members.size());
            for (int i = 0; i < members.size(); i++) {
                reset.add(members.get(i).withReady(false));
            }
            members = reset;
        }
        this.state.set(ExpeditionState.waiting(members, event));
    }

    private void cancelCountdown() {
        this.generation++;
        if (this.countdown != null) {
            this.countdown.cancel();
            this.countdown = null;
        }
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.cancelCountdown();
        this.state.set(ExpeditionState.waiting(List.of(), "准备室已经关闭。"));
    }
}
