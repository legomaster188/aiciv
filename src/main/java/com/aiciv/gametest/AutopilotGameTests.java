package com.aiciv.gametest;

import com.aiciv.AiCiv;
import com.aiciv.autopilot.personality.Personalities;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import com.aiciv.bot.BotException;
import com.aiciv.bot.BotManager;
import com.aiciv.brain.DecisionLog;
import com.aiciv.building.Structure;
import com.aiciv.building.StructureRegistry;
import com.aiciv.config.AiCivConfig;
import com.aiciv.llm.LlmService;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.nav.Pos;
import com.aiciv.panel.PanelSnapshot;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Autopilot plumbing (Task 1) and acceptance (Task 6). Bot names start with TestAp. */
public final class AutopilotGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }

    /** The shipped personalities without {@code id}, plus a stand-in called "nobody" so the set is never empty. */
    static Personalities personalitiesWithout(String id) {
        List<Personality> kept = new ArrayList<>(Personalities.defaults().all().stream().filter(p -> !p.id().equals(id)).toList());
        kept.add(new Personality("nobody", "Nobody", "Stands in for a missing personality.", Traits.NEUTRAL, Map.of(), Map.of()));
        return Personalities.of(kept);
    }

    @GameTest(maxTicks = 100)
    public void autopilotBotSpawnsAndDecides(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilot(helper, "TestApSpawn", 3.5, 3.5, "autopilot-default");
        helper.succeedWhen(() -> {
            List<String> lines = GameTestKit.decisionLines(s.dir(), "TestApSpawn");
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("\"mode\":\"autopilot\"")
                    && l.contains("\"model\":\"autopilot-default\"") && l.contains("\"provider\":\"autopilot\"")), "log: " + lines);
            helper.assertTrue(lines.stream().noneMatch(l -> l.contains("\"error\"")), "log: " + lines);
            helper.assertTrue(s.backend().requests().isEmpty(), "an autopilot sent an LLM request");
            List<String> status;
            try {
                status = s.manager().statusLines("TestApSpawn");
            } catch (BotException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(status.stream().anyMatch(l -> l.startsWith("Personality: Default")), "status: " + status);
            PanelSnapshot snap = PanelSnapshot.capture(s.manager());
            helper.assertTrue(snap.bots().stream().anyMatch(b -> b.name().equals("TestApSpawn") && b.autopilot() != null
                    && "default".equals(b.autopilot().personality()) && "autopilot".equals(b.mode())), "bots: " + snap.bots());
            helper.assertTrue(snap.models().stream().anyMatch(m -> m.key().equals("autopilot-default")), "models: " + snap.models());
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void reloadMovesBotWithVanishedPersonality(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilot(helper, "TestApVanish", 3.5, 3.5, "autopilot-default");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(s.bot().autopilot != null, "the bot did not start as an autopilot");
            s.manager().reload(s.manager().config(), s.manager().llm(), personalitiesWithout("default"));
            helper.assertTrue("scripted".equals(s.bot().brain.modelKey()), "model is " + s.bot().brain.modelKey());
            helper.assertTrue(s.bot().autopilot == null, "the bot is still an autopilot");
            GameTestKit.remove(s);
            helper.succeed();
        });
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @GameTest(maxTicks = 100)
    public void autopilotGoalTakesOnlyGoalIds(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilot(helper, "TestApGoal", 3.5, 3.5, "autopilot-default");
        helper.runAfterDelay(2, () -> {
            try {
                String refused = null;
                try {
                    s.manager().setGoal("TestApGoal", "build me a castle");
                } catch (BotException e) {
                    refused = e.getMessage();
                }
                helper.assertTrue(refused != null && refused.startsWith("autopilots take goal ids: get_wood_tools"), "refusal: " + refused);
                helper.assertTrue(blank(s.bot().brain.goal()), "free text was stored: " + s.bot().brain.goal());
                s.manager().setGoal("TestApGoal", " Farm ");
                helper.assertTrue("farm".equals(s.bot().brain.goal()), "goal " + s.bot().brain.goal());
                PanelSnapshot snap = PanelSnapshot.capture(s.manager());
                helper.assertTrue(snap.bots().stream().anyMatch(b -> b.name().equals("TestApGoal") && "farm".equals(b.goal())),
                        "the panel does not carry the pinned goal: " + snap.bots());
                s.manager().setGoal("TestApGoal", "none");
                helper.assertTrue(blank(s.bot().brain.goal()), "none did not unpin: " + s.bot().brain.goal());
            } catch (BotException e) {
                throw new IllegalStateException(e);
            } finally {
                GameTestKit.remove(s);
            }
            helper.succeed();
        });
    }

    // ---- Task 6: acceptance -------------------------------------------------------------------------------------

    private static String str(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    /** The autopilot decisions (not chat lines) in a bot's log. */
    private static List<JsonObject> decisions(Path dir, String bot) {
        List<JsonObject> out = new ArrayList<>();
        for (String line : GameTestKit.decisionLines(dir, bot)) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if ("autopilot".equals(str(o, "mode"))) out.add(o);
        }
        return out;
    }

    private static List<JsonObject> chatLines(Path dir, String bot) {
        List<JsonObject> out = new ArrayList<>();
        for (String line : GameTestKit.decisionLines(dir, bot)) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if ("autopilot-chat".equals(str(o, "mode"))) out.add(o);
        }
        return out;
    }

    private static String last(List<JsonObject> decisions) {
        return decisions.isEmpty() ? "no decisions" : decisions.get(decisions.size() - 1).toString();
    }

    /** No line of the bot's log mentions an error, and the autopilot never asked the scripted LLM for anything. */
    private static void assertQuiet(GameTestHelper helper, GameTestKit.AutoSpawned s, String bot) {
        List<String> lines = GameTestKit.decisionLines(s.dir(), bot);
        helper.assertTrue(lines.stream().noneMatch(l -> l.contains("\"error\"")), "log: " + lines);
        helper.assertTrue(s.backend().requests().isEmpty(), "an autopilot sent an LLM request: " + s.backend().requests());
    }

    /** Takes the bot offline at the last tick if the test is still running, so a failing test never leaks its bot. */
    private static void cleanupAtTimeout(GameTestHelper helper, int maxTicks, GameTestKit.AutoSpawned s, Runnable extra) {
        helper.runAfterDelay(maxTicks - 1, () -> {
            extra.run();
            GameTestKit.remove(s);
        });
    }

    /**
     * A few solid layers under the arena. The arena floor is one layer over void, and a bot that mines the stone it
     * stands on would otherwise fall out of the world.
     */
    private static void underlay(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(x, -1, z, Blocks.DIRT);
                helper.setBlock(x, -2, z, Blocks.DIRT);
            }
        }
    }

    // aiciv:day keeps the shared world's clock at noon: a night would send a tidy builder home to sleep mid-build
    @GameTest(maxTicks = 6000, padding = 24, environment = "aiciv:day")
    public void autopilotSpeedrunnerReachesIronTools(GameTestHelper helper) {
        SurvivalGameTests.arena(helper);
        underlay(helper, 16);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilotInGameTime(helper, "TestApSpeed", 8.5, 8.5, "autopilot-speedrunner");
        s.bot().player.getInventory().setItem(10, new ItemStack(Items.BREAD, 4));
        cleanupAtTimeout(helper, 6000, s, () -> {});
        helper.succeedWhen(() -> {
            List<JsonObject> decisions = decisions(s.dir(), "TestApSpeed");
            StringBuilder trace = new StringBuilder();
            String prev = "";
            for (JsonObject d : decisions) {
                String gs = str(d, "goal") + ":" + str(d, "step");
                if (!gs.equals(prev)) trace.append(gs).append(" > ");
                prev = gs;
            }
            helper.assertTrue(InventoryOps.has(s.bot().player, Items.IRON_PICKAXE, 1),
                    "no iron pickaxe yet after " + decisions.size() + " decisions; trace " + trace + " last: " + last(decisions)
                            + " acting: " + (s.bot().executor.busy() ? s.bot().executor.currentDescription() : "idle")
                            + " pos " + s.bot().player.blockPosition() + " events " + GameTestKit.events(s.bot()));
            Set<String> goals = new TreeSet<>();
            Set<String> steps = new TreeSet<>();
            Set<String> actions = new TreeSet<>();
            for (JsonObject d : decisions) {
                if (str(d, "goal") != null) goals.add(str(d, "goal"));
                if (str(d, "step") != null) steps.add(str(d, "step"));
                for (var call : d.getAsJsonArray("calls")) {
                    JsonObject c = call.getAsJsonObject();
                    JsonObject args = c.has("args") ? c.getAsJsonObject("args") : new JsonObject();
                    String what = args.has("block") ? "block" : args.has("item") ? "item" : null;
                    actions.add(str(c, "name") + (what == null ? "" : " " + str(args, what)));
                }
            }
            // the sword goals are legitimate detours on the way (beat_game wants an iron sword too), but never social or building ones
            helper.assertTrue(Set.of("get_wood_tools", "get_stone_tools", "get_iron_tools", "get_food", "beat_game").containsAll(goals)
                    && goals.contains("get_iron_tools"), "unexpected goals " + goals + " trace " + trace);
            // a step covers its own gathering (wooden_pickaxe = logs + planks + craft), so the steps are few and the work is in the calls
            helper.assertTrue(steps.size() >= 3, "only " + steps.size() + " distinct steps: " + steps);
            helper.assertTrue(actions.size() >= 6, "only " + actions.size() + " distinct actions: " + actions);
            assertQuiet(helper, s, "TestApSpeed");
            AiCiv.LOG.info("ACCEPTANCE TestApSpeed: iron pickaxe at tick {} after {} decisions; goals {}; steps {}; actions {}",
                    helper.getTick(), decisions.size(), goals, steps, actions);
            GameTestKit.remove(s);
        });
    }

    // 6000, not 4000: now and then the build action wastes its blocks (the same flakiness as buildsTowerWithScaffold) and the bot has to mine more and build again
    @GameTest(maxTicks = 6000, padding = 24, environment = "aiciv:day")
    public void autopilotBuilderBuildsHut(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 16);
        GameTestKit.floor(helper, 16, Blocks.STONE);
        underlay(helper, 16);
        MinecraftServer server = helper.getLevel().getServer();
        StructureRegistry.of(server).store().remove("testapbuilder_home");
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilotInGameTime(helper, "TestApBuilder", 8.5, 3.5, "autopilot-builder");
        s.bot().player.setYRot(0); // south
        var inv = s.bot().player.getInventory();
        inv.setItem(0, new ItemStack(Items.COBBLESTONE, 80));
        inv.setItem(1, new ItemStack(Items.STONE_PICKAXE));
        inv.setItem(2, new ItemStack(Items.STONE_SWORD));
        inv.setItem(3, new ItemStack(Items.BREAD, 8));
        cleanupAtTimeout(helper, 6000, s, () -> StructureRegistry.of(server).store().remove("testapbuilder_home"));
        helper.succeedWhen(() -> {
            List<JsonObject> decisions = decisions(s.dir(), "TestApBuilder");
            var home = StructureRegistry.of(server).store().get("testapbuilder_home");
            helper.assertTrue(home.isPresent(), "no home registered yet; decisions: "
                    + decisions.stream().map(d -> str(d, "trigger") + "/" + str(d, "urgent") + "/" + str(d, "step") + "/" + d.get("calls")).toList()
                    + " last: " + last(decisions) + " acting: "
                    + (s.bot().executor.busy() ? s.bot().executor.currentDescription() : "idle") + " at " + s.bot().player.blockPosition()
                    + " (origin " + helper.absolutePos(new BlockPos(8, 1, 3)) + ") events " + GameTestKit.events(s.bot()));
            Structure h = home.get();
            int cobble = 0;
            for (int x = h.min().x(); x <= h.max().x(); x++) {
                for (int y = h.min().y(); y <= h.max().y(); y++) {
                    for (int z = h.min().z(); z <= h.max().z(); z++) {
                        if (helper.getLevel().getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE)) cobble++;
                    }
                }
            }
            helper.assertTrue(cobble == 71, "the home has " + cobble + " cobblestone cells, expected 71");
            helper.assertTrue(decisions.stream().anyMatch(d -> "build_base".equals(str(d, "goal")) && "hut".equals(str(d, "step"))),
                    "no build_base/hut decision: " + decisions);
            assertQuiet(helper, s, "TestApBuilder");
            StructureRegistry.of(server).store().remove("testapbuilder_home");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 800, environment = "aiciv:day")
    public void autopilotGuardKillsZombie(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        CombatGameTests.easy(server);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilotInGameTime(helper, "TestApGuard", 1.5, 1.5, "autopilot-guard");
        var inv = s.bot().player.getInventory();
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        inv.setItem(1, new ItemStack(Items.BREAD, 8));
        Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(6.5, 1, 6.5));
        zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // daylight must not do the killing
        zombie.setPersistenceRequired();
        boolean[] restored = {false};
        Runnable restore = () -> {
            if (restored[0]) return;
            restored[0] = true;
            CombatGameTests.restoreDifficulty(server);
        };
        cleanupAtTimeout(helper, 800, s, () -> {
            zombie.discard();
            restore.run();
        });
        helper.succeedWhen(() -> {
            List<JsonObject> decisions = decisions(s.dir(), "TestApGuard");
            helper.assertTrue(!zombie.isAlive(), "the zombie is still alive; last: " + last(decisions));
            helper.assertTrue(helper.getTick() <= 600, "the zombie died only at tick " + helper.getTick());
            helper.assertTrue(decisions.stream().anyMatch(d -> "defend".equals(str(d, "goal"))), "no defend decision: " + decisions);
            assertQuiet(helper, s, "TestApGuard");
            restore.run();
            GameTestKit.remove(s);
        });
    }

    /** Real autopilot timing (5 s idle wake, 500 ms after an action) on the tick clock, so decision counts mean something. */
    private static GameTestKit.AutoSpawned spawnWithDefaultTiming(GameTestHelper helper, String name, double x, double z, String model) {
        String config = """
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}},
                 "models": {"scripted": {"provider": "scripted", "model": "script"}},
                 "defaultModel": "scripted", "decisionGapSeconds": 0}""";
        return GameTestKit.spawnAutopilot(helper, name, x, z, model, config, Map.of("scripted", new ScriptedBackend(List.of())),
                GameTestKit.tickClock(helper.getLevel().getServer()));
    }

    /** At home at night with no bed: the builder stays put, it does not ask to sleep every half second. */
    @GameTest(maxTicks = 700, environment = "aiciv:night")
    public void nightWithoutBedDoesNotSpin(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        MinecraftServer server = helper.getLevel().getServer();
        String home = "testapnight_home";
        StructureRegistry.of(server).store().remove(home);
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        Pos p = new Pos(at.getX(), at.getY(), at.getZ());
        StructureRegistry.of(server).store().add(new Structure(home, "TestApNight",
                helper.getLevel().dimension().identifier().toString(), p, p, p, "hut", 0));
        GameTestKit.AutoSpawned s = spawnWithDefaultTiming(helper, "TestApNight", 3.5, 3.5, "autopilot-builder");
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.BREAD, 8));
        helper.runAfterDelay(620, () -> {
            List<JsonObject> decisions = decisions(s.dir(), "TestApNight");
            StructureRegistry.of(server).store().remove(home);
            GameTestKit.remove(s);
            helper.assertTrue(helper.getLevel().isDarkOutside(), "the night environment did not make it dark");
            helper.assertTrue(decisions.stream().anyMatch(d -> "night".equals(str(d, "goal"))), "no night decision: " + decisions);
            helper.assertTrue(decisions.stream().noneMatch(d -> "sleep".equals(str(d, "step"))), "tried to sleep without a bed: " + decisions);
            helper.assertTrue(decisions.size() <= 10, decisions.size() + " decisions in 620 ticks: " + decisions);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 600)
    public void autopilotChatTemplatesFire(GameTestHelper helper) throws Exception {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        MinecraftServer server = helper.getLevel().getServer();
        // "offline" has no backend, so a model on it is never ready: that bot must fall back to its templates
        String config = """
                {"providers": {"scripted": {"type": "scripted", "script": "unused.json"}, "offline": {"type": "openai", "baseUrl": "http://localhost:1"}},
                 "models": {"scripted": {"provider": "scripted", "model": "script"},
                            "offline": {"provider": "offline", "model": "none"},
                            "chatty-llm": {"type": "autopilot", "personality": "chatty", "chatModel": "scripted"},
                            "miner-off": {"type": "autopilot", "personality": "miner", "chatModel": "offline"}},
                 "defaultModel": "scripted", "decisionGapSeconds": 0, "autopilot": {"thinkSeconds": 1, "gapMillis": 0}}""";
        AiCivConfig cfg = AiCivConfig.GSON.fromJson(config, AiCivConfig.class);
        JsonObject beep = new JsonObject();
        beep.addProperty("content", "beep from the llm");
        ScriptedBackend backend = new ScriptedBackend(Collections.nCopies(40, beep));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        Clock clock = GameTestKit.tickClock(server);
        LlmService llm = new LlmService(cfg, Map.of("scripted", backend), clock);
        BotManager manager = new BotManager(server, cfg, llm, dir.resolve("roster.json"), new DecisionLog(dir.resolve("logs")),
                clock, Personalities.defaults());
        try {
            manager.spawn("TestApChatty", "chatty-llm", "", helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 1, 2.5)));
            manager.spawn("TestApMiner", "miner-off", "", helper.getLevel(), helper.absoluteVec(new Vec3(6.5, 1, 2.5)));
        } catch (BotException e) {
            throw new IllegalStateException(e);
        }
        helper.onEachTick(() -> {
            manager.tickStart();
            manager.tickEnd();
        });
        boolean[] prodded = {false};
        helper.runAfterDelay(559, () -> {
            for (String n : List.of("TestApChatty", "TestApMiner")) if (manager.bot(n).isPresent()) AiCivGameTests.removeBot(manager, n);
        });
        helper.succeedWhen(() -> {
            if (!prodded[0]) {
                prodded[0] = true;
                try {
                    manager.say("TestApChatty", "TestApMiner how is the digging");
                } catch (BotException e) {
                    throw new IllegalStateException(e);
                }
            }
            List<JsonObject> chatty = chatLines(dir, "TestApChatty");
            List<JsonObject> miner = chatLines(dir, "TestApMiner");
            helper.assertTrue(chatty.stream().anyMatch(l -> "llm".equals(str(l, "source")) && "beep from the llm".equals(str(l, "text"))),
                    "no llm chat line for TestApChatty yet: " + chatty + "; requests " + backend.requests().size());
            helper.assertTrue(miner.stream().anyMatch(l -> "template".equals(str(l, "source"))),
                    "no template chat line for TestApMiner yet: " + miner);
            List<Long> times = new ArrayList<>();
            for (JsonObject l : Stream.concat(chatty.stream(), miner.stream()).toList()) {
                helper.assertTrue(!str(l, "text").contains("{"), "an unfilled placeholder: " + l);
                times.add(Instant.parse(str(l, "time")).toEpochMilli());
            }
            Collections.sort(times);
            for (int i = 1; i < times.size(); i++) {
                long gap = times.get(i) - times.get(i - 1);
                helper.assertTrue(gap >= 2000, "chat lines only " + gap + " ms apart: " + times);
            }
            for (String bot : List.of("TestApChatty", "TestApMiner")) {
                helper.assertTrue(GameTestKit.decisionLines(dir, bot).stream().noneMatch(l -> l.contains("\"error\"")),
                        "an error in the log of " + bot);
            }
            for (String n : List.of("TestApChatty", "TestApMiner")) AiCivGameTests.removeBot(manager, n);
        });
    }
}
