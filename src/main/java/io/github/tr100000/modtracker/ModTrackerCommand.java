package io.github.tr100000.modtracker;

import static org.quiltmc.qsl.command.api.client.ClientCommandManager.argument;
import static org.quiltmc.qsl.command.api.client.ClientCommandManager.literal;

import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.command.api.client.QuiltClientCommandSource;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.command.CommandBuildContext;
import net.minecraft.server.command.CommandManager.RegistrationEnvironment;
import net.minecraft.text.Text;

public final class ModTrackerCommand {
    private ModTrackerCommand() {}

    public static void register(CommandDispatcher<QuiltClientCommandSource> dispatcher, CommandBuildContext buildContext, RegistrationEnvironment environment) {
        dispatcher.register(
            literal(ModTracker.MODID)
                .then(literal("dataPath")
                    .executes(ModTrackerCommand::dataPath))
                .then(literal("print")
                    .executes(ModTrackerCommand::print))
                .then(literal("mods")
                    .then(literal("all")
                        .executes(ModTrackerCommand::allMods))
                    .then(literal("filtered")
                        .executes(ModTrackerCommand::filteredMods)))
                .then(literal("test")
                    .then(argument("modid", StringArgumentType.string())
                        .executes(ModTrackerCommand::test)))
        );
    }

    private static int dataPath(CommandContext<QuiltClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal(ModTracker.DATA_PATH.toString()));
        return 0;
    }

    private static int print(CommandContext<QuiltClientCommandSource> context) {
        ModTracker.sendMessageToPlayer(context.getSource().getPlayer());
        return 0;
    }

    private static int allMods(CommandContext<QuiltClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("All loaded mods:"));
        QuiltLoader.getAllMods().forEach(mod -> context.getSource().sendFeedback(Text.literal("  " + mod.metadata().id())));
        return 0;
    }

    private static int filteredMods(CommandContext<QuiltClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("All tracked mods:"));
        ModTracker.current.forEach((id, version) -> context.getSource().sendFeedback(Text.literal("  " + id)));
        return 0;
    }

    private static int test(CommandContext<QuiltClientCommandSource> context) {
        String modid = StringArgumentType.getString(context, "modid");
        try {
            ModFilters.compilePatterns();
            context.getSource().sendFeedback(Text.literal(modid + ": " + (ModFilters.filter(modid) ? "passed" : "failed")));
        }
        finally {
            ModFilters.clearCompiledPatterns();
        }
        return 0;
    }
}
