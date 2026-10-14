package com.aiciv.gametest;

import com.aiciv.autopilot.personality.Personalities;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import com.aiciv.bot.BotException;
import com.aiciv.panel.PanelSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

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
}
