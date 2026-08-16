package net.momirealms.sparrow.ui.example.menu.cartographycarousel;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.SparrowExample;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * 注册制图台轮播图示例的 Paper Brigadier 子命令.
 */
public final class CartographyCarouselCommand {
    private static final String TARGET_ARGUMENT = "target"; // 单玩家选择器参数名
    private static final Component OPEN_FAILED_MESSAGE = Component.text("制图台轮播图菜单打开失败，请查看服务端日志。", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false); // 异步打开失败后发送给仍在线的目标玩家

    private CartographyCarouselCommand() {
    }

    /**
     * 创建 {@code /sparrowui open cartographycarousel <target>} 命令节点.
     *
     * @return 可挂载到 open 节点下的命令
     */
    @NotNull
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("cartographycarousel")
                .then(Commands.argument(TARGET_ARGUMENT, ArgumentTypes.player())
                        .executes(CartographyCarouselCommand::open));
    }

    /**
     * 解析单个目标玩家并异步打开菜单. 打开失败时记录完整异常, 再回到目标玩家的实体线程发送提示.
     *
     * @param context Paper 命令上下文
     * @return Brigadier 单次执行成功值
     * @throws CommandSyntaxException 目标选择器无法解析时抛出
     */
    private static int open(@NotNull CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = context.getArgument(TARGET_ARGUMENT, PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(context.getSource()).getFirst();
        String targetName = target.getName();
        CartographyCarouselMenu.open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable == null) {
                return;
            }
            SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the cartography carousel menu for " + targetName, throwable);
            target.getScheduler().run(
                    SparrowExample.INSTANCE,
                    ignoredTask -> target.sendMessage(OPEN_FAILED_MESSAGE),
                    () -> { }
            );
        });
        return Command.SINGLE_SUCCESS;
    }
}
