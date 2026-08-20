package net.momirealms.sparrow.ui.example.menu.livesearch;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.momirealms.sparrow.ui.example.SparrowExample;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;

public final class LiveSearchCommand {
    private static final String NAME = "livesearch";
    private static final String CHINESE_NAME = "实时搜索";
    private static final String TARGET_ARGUMENT = "target";

    private LiveSearchCommand() {
    }

    @NotNull
    public static List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(node(NAME), node(CHINESE_NAME));
    }

    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> node(@NotNull String name) {
        return Commands.literal(name)
                .then(Commands.argument(TARGET_ARGUMENT, ArgumentTypes.player())
                        .executes(LiveSearchCommand::open));
    }

    private static int open(@NotNull CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = context.getArgument(TARGET_ARGUMENT, PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(context.getSource()).getFirst();
        String targetName = target.getName();
        LiveSearchMenu.open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable != null) {
                SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the search page menu for " + targetName, throwable);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
