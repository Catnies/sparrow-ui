package net.momirealms.sparrow.ui.example.menu.stoneappraisal;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.command.PlayerTargets;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.data.SinglePlayerSelector;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public final class StoneAppraisalCommand {
    private static final String NAME = "石头鉴定";
    private static final String TARGET_ARGUMENT = "target";

    private StoneAppraisalCommand() {
    }

    /**
     * 注册本菜单的中文入口和单玩家选择器.
     */
    public static void register(@NotNull CommandManager<CommandSender> manager) {
        manager.command(manager.commandBuilder("sparrowui")
                .permission("sparrowui.example")
                .literal("open")
                .literal(NAME)
                .required(TARGET_ARGUMENT, PlayerTargets.parser())
                .handler(context -> {
                    SinglePlayerSelector selector = context.get(TARGET_ARGUMENT);
                    if (selector.inputString().startsWith("@") && !context.sender().hasPermission("minecraft.command.selector")) {
                        context.sender().sendMessage("使用选择器需要 minecraft.command.selector 权限。");
                        return;
                    }
                    Player target = selector.single();
                    SparrowUI.getInstance().scheduler().platform().run(() -> open(target), () -> { }, target);
                }));
    }

    private static void open(@NotNull Player target) {
        String targetName = target.getName();
        StoneAppraisalMenu.open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable != null) {
                SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the stone appraisal menu for " + targetName, throwable);
            }
        });
    }
}
