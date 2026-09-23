package net.momirealms.sparrow.ui.example.menu.cartographygallery;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.command.PlayerTargets;
import net.momirealms.sparrow.ui.example.util.Components;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.data.SinglePlayerSelector;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * 注册制图台画廊示例的 Cloud 子命令.
 */
public final class CartographyGalleryCommand {
    private static final String NAME = "制图台画廊";
    private static final String TARGET_ARGUMENT = "target"; // 单玩家选择器参数名
    private static final Component OPEN_FAILED_MESSAGE = Component.text("制图台画廊菜单打开失败，请查看服务端日志。", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false); // 异步打开失败后发送给仍在线的目标玩家

    private CartographyGalleryCommand() {
    }

    /**
     * 注册制图台画廊的中文命令入口和单玩家选择器.
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
        CartographyGalleryMenu.open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable == null) {
                return;
            }
            SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the cartography carousel menu for " + targetName, throwable);
            SparrowUI.getInstance().scheduler().platform().run(() -> Components.sendMessage(target, OPEN_FAILED_MESSAGE), () -> { }, target);
        });
    }
}
