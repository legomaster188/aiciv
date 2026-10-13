package com.aiciv.bot;

import com.aiciv.AiCiv;
import com.aiciv.panel.PanelServer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** /ai spawn | remove | list | goal | pause | resume | status | budget | model | panel | reload (needs op level 2). */
public final class AiCommands {
    @FunctionalInterface
    private interface BotOp {
        String apply(BotManager m) throws BotException;
    }

    @FunctionalInterface
    private interface LinesOp {
        List<String> apply(BotManager m) throws BotException;
    }

    private AiCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> spawn(c, null, null))
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .suggests(AiCommands::suggestModels)
                                        .executes(c -> spawn(c, StringArgumentType.getString(c, "model"), null))
                                        .then(Commands.argument("persona", StringArgumentType.greedyString())
                                                .executes(c -> spawn(c, StringArgumentType.getString(c, "model"),
                                                        unquote(StringArgumentType.getString(c, "persona"))))))))
                .then(Commands.literal("remove").then(botName().executes(c -> run(c, m -> {
                    m.remove(name(c));
                    return "Removed " + name(c);
                }))))
                .then(Commands.literal("list").executes(c -> lines(c, BotManager::listLines)))
                .then(Commands.literal("goal").then(botName()
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> run(c, m -> {
                            m.setGoal(name(c), unquote(StringArgumentType.getString(c, "text")));
                            return "Goal set for " + name(c);
                        })))))
                .then(Commands.literal("pause")
                        .executes(c -> pause(c, null, true))
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(AiCommands::suggestBotsAndAll)
                                .executes(c -> pause(c, StringArgumentType.getString(c, "target"), true))))
                .then(Commands.literal("resume")
                        .executes(c -> pause(c, null, false))
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(AiCommands::suggestBotsAndAll)
                                .executes(c -> pause(c, StringArgumentType.getString(c, "target"), false))))
                .then(Commands.literal("status").then(botName().executes(c -> lines(c, m -> m.statusLines(name(c))))))
                .then(Commands.literal("budget").executes(c -> lines(c, BotManager::budgetLines)))
                .then(Commands.literal("model").then(botName()
                        .then(Commands.argument("model", StringArgumentType.word()).suggests(AiCommands::suggestModels)
                                .executes(c -> run(c, m -> {
                                    m.setModel(name(c), StringArgumentType.getString(c, "model"));
                                    return name(c) + " now uses " + StringArgumentType.getString(c, "model");
                                })))))
                .then(Commands.literal("panel").executes(AiCommands::panel))
                .then(Commands.literal("reload").executes(AiCommands::reload)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return Commands.argument("name", StringArgumentType.word()).suggests(AiCommands::suggestBots);
    }

    private static String name(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    private static int spawn(CommandContext<CommandSourceStack> c, String model, String persona) {
        return run(c, m -> {
            CommandSourceStack s = c.getSource();
            Bot b = m.spawn(name(c), model, persona, s.getLevel(), s.getPosition());
            return "Spawned " + b.name + " using " + b.brain.modelKey();
        });
    }

    private static int pause(CommandContext<CommandSourceStack> c, String target, boolean paused) {
        return run(c, m -> (paused ? "Paused " : "Resumed ") + m.setPaused(target, paused) + " AI player(s)");
    }

    /** Shows the signed-in panel link to whoever asked, and nobody else; it carries the panel's access token. */
    private static int panel(CommandContext<CommandSourceStack> c) {
        PanelServer panel = AiCiv.panel();
        if (panel == null) {
            c.getSource().sendFailure(Component.literal("The AiCiv panel is not running (disabled in aiciv.json, or its port is taken; see the server log)"));
            return 0;
        }
        String link = panel.linkWithToken();
        Component clickable = Component.literal(link).withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(link))));
        c.getSource().sendSuccess(() -> Component.literal("AiCiv panel: ").append(clickable), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        try {
            List<String> problems = AiCiv.reload();
            c.getSource().sendSuccess(() -> Component.literal("Reloaded aiciv.json and keys.json"), true);
            for (String p : problems) c.getSource().sendFailure(Component.literal("aiciv.json: " + p));
            return 1;
        } catch (IOException | RuntimeException e) {
            c.getSource().sendFailure(Component.literal("Reload failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int run(CommandContext<CommandSourceStack> c, BotOp op) {
        BotManager m = AiCiv.manager();
        if (m == null) {
            c.getSource().sendFailure(Component.literal("AiCiv is not running; check the server log"));
            return 0;
        }
        try {
            String msg = op.apply(m);
            c.getSource().sendSuccess(() -> Component.literal(msg), true);
            return 1;
        } catch (BotException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int lines(CommandContext<CommandSourceStack> c, LinesOp op) {
        BotManager m = AiCiv.manager();
        if (m == null) {
            c.getSource().sendFailure(Component.literal("AiCiv is not running; check the server log"));
            return 0;
        }
        try {
            for (String line : op.apply(m)) c.getSource().sendSuccess(() -> Component.literal(line), false);
            return 1;
        } catch (BotException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestModels(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        BotManager m = AiCiv.manager();
        return SharedSuggestionProvider.suggest(m == null ? List.of() : m.modelKeys(), b);
    }

    private static CompletableFuture<Suggestions> suggestBots(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        BotManager m = AiCiv.manager();
        return SharedSuggestionProvider.suggest(m == null ? List.of() : m.botNames(), b);
    }

    private static CompletableFuture<Suggestions> suggestBotsAndAll(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        BotManager m = AiCiv.manager();
        List<String> options = new ArrayList<>();
        options.add("all");
        if (m != null) options.addAll(m.botNames());
        return SharedSuggestionProvider.suggest(options, b);
    }

    static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t;
    }
}
