package net.momirealms.sparrow.ui.example.menu.searchcapture;

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

import java.util.logging.Level;

public final class SearchCaptureCommand {
    private static final String TARGET_ARGUMENT = "target";

    private SearchCaptureCommand() {
    }

    @NotNull
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("searchcapture")
                .then(Commands.argument(TARGET_ARGUMENT, ArgumentTypes.player())
                        .executes(SearchCaptureCommand::open));
    }

    private static int open(@NotNull CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = context.getArgument(TARGET_ARGUMENT, PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(context.getSource()).getFirst();
        String targetName = target.getName();
        SearchCaptureMenu.open(target).whenComplete((ignoredResult, throwable) -> {
            if (throwable != null) {
                SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the search capture menu for " + targetName, throwable);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
