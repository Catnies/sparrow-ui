package net.momirealms.sparrow.ui.example.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.menu.searchpage.SearchPageCommand;
import org.jetbrains.annotations.NotNull;

public final class SparrowCommand {
    private static final String PERMISSION = "sparrowui.example";

    private SparrowCommand() {
    }

    public static void register() {
        SparrowExample.INSTANCE.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(buildTree(), "Opens a SparrowUI example menu.");
        });
    }

    @NotNull
    private static LiteralCommandNode<CommandSourceStack> buildTree() {
        return Commands.literal("sparrowui")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("open")
                        .then(SearchPageCommand.node()))
                .build();
    }
}
