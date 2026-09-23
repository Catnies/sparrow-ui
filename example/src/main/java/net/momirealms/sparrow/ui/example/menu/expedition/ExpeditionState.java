package net.momirealms.sparrow.ui.example.menu.expedition;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

record ExpeditionState(@NotNull List<Member> members, @NotNull Phase phase, int seconds, @NotNull Route destination, @NotNull String event) {
    static final int CAPACITY = 4;

    ExpeditionState {
        members = List.copyOf(members);
    }

    @NotNull
    static ExpeditionState waiting(@NotNull List<Member> members, @NotNull String event) {
        Route destination = Route.TEMPLE;
        int most = -1;
        Route captainVote = members.isEmpty() ? null : members.getFirst().vote();
        for (Route route : Route.values()) {
            int votes = votes(members, route);
            if (votes > most || votes == most && route == captainVote) {
                destination = route;
                most = votes;
            }
        }
        return new ExpeditionState(members, Phase.WAITING, 0, destination, event);
    }

    @Nullable
    Member member(@NotNull UUID player) {
        for (int i = 0; i < this.members.size(); i++) {
            Member member = this.members.get(i);
            if (member.id().equals(player)) return member;
        }
        return null;
    }

    boolean captain(@NotNull UUID player) {
        return !this.members.isEmpty() && this.members.getFirst().id().equals(player);
    }

    int readyCount() {
        int ready = 0;
        for (int i = 0; i < this.members.size(); i++) {
            if (this.members.get(i).ready()) {
                ready++;
            }
        }
        return ready;
    }

    int votes(@NotNull Route route) {
        return votes(this.members, route);
    }

    @Nullable
    String launchProblem(@NotNull UUID player) {
        if (!this.captain(player)) return "只有队长能操作出征。";
        if (this.members.size() < 2) return "至少需要两名成员才能出征。";
        int missing = this.members.size() - this.readyCount();
        if (missing > 0) return "还有 " + missing + " 名成员尚未准备。";
        return null;
    }

    private static int votes(@NotNull List<Member> members, @NotNull Route route) {
        int votes = 0;
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).vote() == route) {
                votes++;
            }
        }
        return votes;
    }

    record Member(@NotNull UUID id, @NotNull String name, @NotNull Role role, @Nullable Route vote, boolean ready) {
        @NotNull
        Member withReady(boolean ready) {
            return new Member(this.id, this.name, this.role, this.vote, ready);
        }
    }

    enum Phase {
        WAITING, COUNTDOWN, DEPARTED
    }

    enum Role {
        UNSELECTED("未选择职责", Material.LEATHER_HELMET, "先从下方四项职责中选择一项。"),
        GUARD("守卫", Material.SHIELD, "举盾开路, 为小队挡住正面的危险。"),
        RANGER("游侠", Material.BOW, "侦察路线, 从远处掩护队友。"),
        HEALER("医师", Material.GOLDEN_APPLE, "携带补给, 照看队友的状态。"),
        MAGE("术士", Material.BLAZE_ROD, "研究机关, 处理遗迹中的魔法威胁。");

        final String title;
        final Material icon;
        final String description;

        Role(String title, Material icon, String description) {
            this.title = title;
            this.icon = icon;
            this.description = description;
        }
    }

    enum Route {
        TEMPLE("青苔神庙", Material.MOSS_BLOCK, "探索 · 古老机关与失落宝藏"),
        GLACIER("霜岭哨站", Material.PACKED_ICE, "生存 · 风雪中的补给搜寻"),
        RIFT("熔火裂谷", Material.MAGMA_BLOCK, "挑战 · 深入炽热的地下遗迹");

        final String title;
        final Material icon;
        final String description;

        Route(String title, Material icon, String description) {
            this.title = title;
            this.icon = icon;
            this.description = description;
        }
    }
}
