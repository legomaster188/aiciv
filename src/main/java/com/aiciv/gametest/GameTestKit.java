package com.aiciv.gametest;

import com.aiciv.autopilot.personality.Personalities;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotException;
import com.aiciv.bot.BotManager;
import com.aiciv.brain.DecisionLog;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.ChatBackend;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ScriptedBackend;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Shared arena and bot helpers for the milestone-3 and later game tests. */
public final class GameTestKit {
    private GameTestKit() {}

    /**
     * Removes the barrier outline of the 8x8 test box from relative -1..size x 0..8 x -1..size. Inside a larger arena
     * it would otherwise block sight and movement.
     */
    public static void clearBarriers(GameTestHelper helper, int size) {
        for (int x = -1; x <= size; x++) {
            for (int y = 0; y <= 8; y++) {
                for (int z = -1; z <= size; z++) {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) helper.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
    }

    /** A size x size floor of {@code block} at relative y 0. */
    public static void floor(GameTestHelper helper, int size, Block block) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) helper.setBlock(x, 0, z, block);
        }
    }

    public record Spawned(BotManager manager, Bot bot) {}

    /** A bot at relative (x, 1, z) run by a scripted manager with an empty script; only actions tick (no thinking). */
    public static Spawned spawn(GameTestHelper helper, String name, double x, double z) {
        Spawned s = create(helper, name, x, z, new ScriptedBackend(List.of()));
        helper.onEachTick(s.manager()::tickStart);
        return s;
    }

    /**
     * Like {@link #spawn}, with {@code afterTick} run right after the bot's actions tick. Separate {@code onEachTick}
     * runnables run in no fixed order within a tick (they sit in a hash map), so a check that must see the state an
     * action left after its tick has to run here.
     */
    public static Spawned spawn(GameTestHelper helper, String name, double x, double z, Runnable afterTick) {
        Spawned s = create(helper, name, x, z, new ScriptedBackend(List.of()));
        helper.onEachTick(() -> {
            s.manager().tickStart();
            afterTick.run();
        });
        return s;
    }

    /** A bot whose decisions come from {@code backend}; the manager ticks fully, so it thinks and acts. */
    public static Spawned spawnScripted(GameTestHelper helper, String name, double x, double z, ScriptedBackend backend) {
        Spawned s = create(helper, name, x, z, backend);
        helper.onEachTick(() -> {
            s.manager().tickStart();
            s.manager().tickEnd();
        });
        return s;
    }

    private static Spawned create(GameTestHelper helper, String name, double x, double z, ScriptedBackend backend) {
        try {
            BotManager manager = AiCivGameTests.scriptedManager(helper, backend, Files.createTempDirectory("aiciv-gametest"));
            Bot bot = manager.spawn(name, "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(x, 1, z)));
            return new Spawned(manager, bot);
        } catch (Exception e) {
            throw new IllegalStateException("could not spawn " + name, e);
        }
    }

    public record AutoSpawned(BotManager manager, Bot bot, Path dir, ScriptedBackend backend) {}

    /**
     * The scripted provider and "scripted" model, any {@code extraModelsJson} entries (e.g. {@code "a": {...}, "b": {...}}),
     * defaultModel "scripted" and fast autopilot timing.
     */
    public static String autopilotConfig(String extraModelsJson) {
        String extra = extraModelsJson == null || extraModelsJson.isBlank() ? "" : ", " + extraModelsJson;
        return """
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"scripted": {"provider": "scripted", "model": "script"}%s},
                 "defaultModel": "scripted", "decisionGapSeconds": 0, "autopilot": {"thinkSeconds": 1, "gapMillis": 0}}"""
                .formatted(extra);
    }

    /** An autopilot bot at relative (x, 1, z) on {@code model}, with an empty scripted backend; the manager ticks fully. */
    public static AutoSpawned spawnAutopilot(GameTestHelper helper, String name, double x, double z, String model) {
        ScriptedBackend backend = new ScriptedBackend(List.of());
        return spawnAutopilot(helper, name, x, z, model, autopilotConfig(""), Map.of("scripted", backend));
    }

    /**
     * As above with an explicit config and LLM backends by provider. {@code backend} in the result is the "scripted"
     * provider's backend when it is a {@link ScriptedBackend}, else null.
     */
    public static AutoSpawned spawnAutopilot(GameTestHelper helper, String name, double x, double z, String model,
                                             String configJson, Map<String, ChatBackend> backends) {
        try {
            Path dir = Files.createTempDirectory("aiciv-gametest");
            AiCivConfig config = AiCivConfig.GSON.fromJson(configJson, AiCivConfig.class);
            LlmService llm = new LlmService(config, backends, Clock.systemUTC());
            BotManager manager = new BotManager(helper.getLevel().getServer(), config, llm, dir.resolve("roster.json"),
                    new DecisionLog(dir.resolve("logs")), Clock.systemUTC(), Personalities.defaults());
            Bot bot = manager.spawn(name, model, "", helper.getLevel(), helper.absoluteVec(new Vec3(x, 1, z)));
            helper.onEachTick(() -> {
                manager.tickStart();
                manager.tickEnd();
            });
            ScriptedBackend backend = backends.get("scripted") instanceof ScriptedBackend s ? s : null;
            return new AutoSpawned(manager, bot, dir, backend);
        } catch (Exception e) {
            throw new IllegalStateException("could not spawn " + name, e);
        }
    }

    /** Removes the autopilot bot if it is still online. */
    public static void remove(AutoSpawned s) {
        remove(new Spawned(s.manager(), s.bot()));
    }

    /** Removes the bot if it is still online. */
    public static void remove(Spawned s) {
        if (s.manager().bot(s.bot().name).isEmpty()) return;
        try {
            s.manager().remove(s.bot().name);
        } catch (BotException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String events(Bot bot) {
        return String.join("\n", bot.brain.events().lines());
    }

    /** One assistant message that chains the given tool calls; each is {name, jsonArguments}. */
    public static JsonObject turn(String... nameAndArgs) {
        JsonArray calls = new JsonArray();
        for (int i = 0; i < nameAndArgs.length; i += 2) {
            JsonObject function = new JsonObject();
            function.addProperty("name", nameAndArgs[i]);
            function.addProperty("arguments", nameAndArgs[i + 1]);
            JsonObject call = new JsonObject();
            call.addProperty("id", String.valueOf(i / 2));
            call.addProperty("type", "function");
            call.add("function", function);
            calls.add(call);
        }
        JsonObject message = new JsonObject();
        message.addProperty("content", "");
        message.add("tool_calls", calls);
        return message;
    }

    /** The bot's decision-log lines under {@code dir/logs}; empty when it has logged none. */
    public static List<String> decisionLines(Path dir, String bot) {
        try {
            Path file = dir.resolve("logs").resolve(bot + ".jsonl");
            return Files.exists(file) ? Files.readAllLines(file) : new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
