package net.momirealms.sparrow.ui.example.menu.expedition;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.command.PlayerTargets;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.data.SinglePlayerSelector;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public final class ExpeditionCommand {
    private static final String NAME = "多人远征准备室";
    private static final String TARGET_ARGUMENT = "target";

    private ExpeditionCommand() {
    }

    /**
     * 为所有查看者注册同一个准备室, 同时接入离线和插件关闭清理.
     */
    public static void register(@NotNull CommandManager<CommandSender> manager) {
        SparrowExample plugin = SparrowExample.INSTANCE;
        ExpeditionRoom room = new ExpeditionRoom(task -> SparrowUI.getInstance().scheduler().platform().runRepeating(task, 20, 20));
        plugin.getServer().getPluginManager().registerEvents(new ExpeditionListener(room, plugin), plugin);
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
                    SparrowUI.getInstance().scheduler().platform().run(() -> open(target, room), () -> { }, target);
                }));
    }

    private static void open(@NotNull Player target, @NotNull ExpeditionRoom room) {
        String targetName = target.getName();
        new ExpeditionMenu(room).open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable != null) {
                SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the expedition room for " + targetName, throwable);
            }
        });
    }
}
