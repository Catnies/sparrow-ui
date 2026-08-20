package net.momirealms.sparrow.ui.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.menu.animationpresets.AnimationPresetsCommand;
import net.momirealms.sparrow.ui.example.menu.anvilprompt.AnvilPromptCommand;
import net.momirealms.sparrow.ui.example.menu.cartographygallery.CartographyGalleryCommand;
import net.momirealms.sparrow.ui.example.menu.customframes.CustomFramesCommand;
import net.momirealms.sparrow.ui.example.menu.livesearch.LiveSearchCommand;
import net.momirealms.sparrow.ui.example.menu.skilltree.SkillTreeCommand;
import net.momirealms.sparrow.ui.example.menu.stoneappraisal.StoneAppraisalCommand;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class SparrowCommand {
    private static final String PERMISSION = "sparrowui.example";

    private SparrowCommand() {
    }

    public static void register() {
        SparrowExample.INSTANCE.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(buildTree(), "Opens a SparrowUI example menu.");
        });
    }

    /**
     * 每个菜单都挂中英两个名字, 因此 {@code open} 后面用哪种语言打字都能补全.
     *
     * @return 命令树
     */
    @NotNull
    private static LiteralCommandNode<CommandSourceStack> buildTree() {
        LiteralArgumentBuilder<CommandSourceStack> open = Commands.literal("open");
        addMenu(open, LiveSearchCommand.nodes());
        addMenu(open, AnvilPromptCommand.nodes());
        addMenu(open, SkillTreeCommand.nodes());
        addMenu(open, CartographyGalleryCommand.nodes());
        addMenu(open, AnimationPresetsCommand.nodes());
        addMenu(open, StoneAppraisalCommand.nodes());
        addMenu(open, CustomFramesCommand.nodes());
        return Commands.literal("sparrowui")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(open)
                .build();
    }

    /**
     * 把一个菜单的全部名字挂到 {@code open} 下面.
     *
     * @param open open 子命令
     * @param nodes 这个菜单的中英文名字节点
     */
    private static void addMenu(
            @NotNull LiteralArgumentBuilder<CommandSourceStack> open,
            @NotNull List<LiteralArgumentBuilder<CommandSourceStack>> nodes
    ) {
        for (int index = 0; index < nodes.size(); index++) {
            open.then(nodes.get(index));
        }
    }
}
