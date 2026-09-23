package net.momirealms.sparrow.ui.example.menu.music;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.command.PlayerTargets;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.data.SinglePlayerSelector;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public final class MusicCommand {
    private static final String NAME = "音符工作台";
    private static final String TARGET_ARGUMENT = "target";

    private MusicCommand() {
    }

    /**
     * 注册本菜单的中文入口和单玩家选择器.
     */
    public static void register(@NotNull CommandManager<CommandSender> manager) {
        NotePattern demo = DemoSong.load();
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
                    SparrowUI.getInstance().scheduler().platform().run(() -> open(target, demo), () -> { }, target);
                }));
    }

    private static void open(@NotNull Player target, @NotNull NotePattern demo) {
        String targetName = target.getName();
        new MusicMenu(demo).open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable != null) {
                SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the music workbench menu for " + targetName, throwable);
            }
        });
    }
}
