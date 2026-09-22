package net.momirealms.sparrow.ui.example.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.command.VanillaCommandWrapper;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.SinglePlayerSelector;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public final class PlayerTargets implements ArgumentParser<CommandSender, SinglePlayerSelector>, SuggestionProvider<CommandSender> {
    private static final EntityArgument ARGUMENT = EntityArgument.player();
    private static final ParserDescriptor<CommandSender, SinglePlayerSelector> PARSER = ParserDescriptor.of(new PlayerTargets(), SinglePlayerSelector.class);

    private PlayerTargets() {
    }

    @NotNull
    public static ParserDescriptor<CommandSender, SinglePlayerSelector> parser() {
        return PARSER;
    }

    @Override
    @NotNull
    public ArgumentParseResult<SinglePlayerSelector> parse(@NotNull CommandContext<CommandSender> context, @NotNull CommandInput input) {
        String remaining = input.remainingInput();
        StringReader reader = new StringReader(remaining);
        try {
            // Spigot 26.2 的选择器权限在 Bukkit 边界检查, NMS 只负责单玩家语法和解析.
            EntitySelector selector = ARGUMENT.parse(reader, context.sender().hasPermission("minecraft.command.selector"), true);
            Player player = selector.findSinglePlayer(VanillaCommandWrapper.getListener(context.sender())).getBukkitEntity();
            input.moveCursor(reader.getCursor());
            return ArgumentParseResult.success(new Target(remaining.substring(0, reader.getCursor()), player));
        } catch (CommandSyntaxException exception) {
            return ArgumentParseResult.failure(exception);
        }
    }

    @Override
    @NotNull
    public SuggestionProvider<CommandSender> suggestionProvider() {
        return this;
    }

    @Override
    @NotNull
    public CompletableFuture<? extends Iterable<? extends Suggestion>> suggestionsFuture(@NotNull CommandContext<CommandSender> context, @NotNull CommandInput input) {
        String remaining = input.remainingInput();
        EntitySelectorParser parser = new EntitySelectorParser(new StringReader(remaining), context.sender().hasPermission("minecraft.command.selector"));
        try {
            parser.parse(true);
        } catch (CommandSyntaxException ignored) {
            // 不完整的输入会抛语法异常, 解析器仍保留当前可补全的位置.
        }
        return parser.fillSuggestions(new SuggestionsBuilder(remaining, 0), builder ->
                        SharedSuggestionProvider.suggest(VanillaCommandWrapper.getListener(context.sender()).getOnlinePlayerNames(), builder))
                .thenApply(suggestions -> suggestions.getList().stream()
                        .map(suggestion -> Suggestion.suggestion(suggestion.apply(remaining)))
                        .toList());
    }

    private record Target(@NotNull String inputString, @NotNull Player single) implements SinglePlayerSelector {
    }
}
