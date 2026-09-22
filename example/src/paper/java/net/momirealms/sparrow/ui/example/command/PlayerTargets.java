package net.momirealms.sparrow.ui.example.command;

import org.bukkit.command.CommandSender;
import org.incendo.cloud.bukkit.data.SinglePlayerSelector;
import org.incendo.cloud.bukkit.parser.selector.SinglePlayerSelectorParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class PlayerTargets {
    private PlayerTargets() {
    }

    @NotNull
    public static ParserDescriptor<CommandSender, SinglePlayerSelector> parser() {
        return SinglePlayerSelectorParser.singlePlayerSelectorParser();
    }
}
