package com.aiciv.autopilot.personality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiciv.autopilot.AutopilotEngine;
import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Engines;
import com.aiciv.autopilot.Situation;
import org.junit.jupiter.api.Test;

class EngineSpeedTest {
    @Test
    void twoThousandDecisionsTakeUnderASecond() {
        AutopilotEngine engine = Engines.create();
        Personality p = Personalities.defaults().get("miner").orElseThrow();
        AutopilotMemory mem = new AutopilotMemory();
        Situation s = new PSit().inv("oak_planks", 12).inv("cobblestone", 40).inv("stone_pickaxe", 1).inv("iron_ingot", 5)
                .inv("bread", 3).actions("goto", "explore", "mine", "craft", "smelt", "collect", "attack", "eat", "place", "sleep")
                .hostile("zombie", 12, "N").build();
        for (int i = 0; i < 200; i++) engine.decide(s, mem, p, "IDLE", 1000 + i); // warm up
        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) engine.decide(s, mem, p, "IDLE", 2000 + i);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 1000, "2000 decisions took " + ms + " ms");
    }
}
