package com.aiciv.gametest;

import com.aiciv.bot.Bot;
import com.aiciv.bot.BotException;
import com.aiciv.bot.BotManager;
import com.aiciv.llm.ScriptedBackend;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Shared arena and bot helpers for the milestone-3 game tests. */
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

    /**
     * Entities (projectiles, boats, mobs) freeze in a chunk that is loaded but not entity-ticking, and an arena wider
     * than the test's own chunks reaches into such chunks. Forces every chunk under relative x0..x1, z0..z1; run the
     * returned release when the test ends.
     */
    public static Runnable keepEntitiesTicking(GameTestHelper helper, int x0, int z0, int x1, int z1) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(x0, 0, z0)), b = helper.absolutePos(new BlockPos(x1, 0, z1));
        int cx0 = Math.min(a.getX(), b.getX()) >> 4, cx1 = Math.max(a.getX(), b.getX()) >> 4;
        int cz0 = Math.min(a.getZ(), b.getZ()) >> 4, cz1 = Math.max(a.getZ(), b.getZ()) >> 4;
        List<int[]> forced = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new int[] {cx, cz});
            }
        }
        return () -> {
            for (int[] c : forced) level.setChunkForced(c[0], c[1], false);
        };
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
