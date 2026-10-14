package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MilestonesTest {
    @Test
    void reachedListsMilestonesInOrder() {
        Situation s = new TestSituation().inv("stone_pickaxe", 1).inv("diamond", 2)
                .structure("ada_home by Ada at 0 64 0 (0m N)").build();
        assertEquals(List.of("wooden_pickaxe", "stone_pickaxe", "diamond", "home"), Milestones.reached(s));
    }

    @Test
    void armorDimensionsAndEyes() {
        TestSituation t = new TestSituation().wear("iron_chestplate").inv("ender_eye", 1);
        t.dimension = "the_end";
        assertEquals(List.of("iron_armor", "ender_eye", "end"), Milestones.reached(t.build()));
        assertEquals(10, Milestones.ALL.size());
        assertTrue(Milestones.reached(new TestSituation().build()).isEmpty());
    }
}
