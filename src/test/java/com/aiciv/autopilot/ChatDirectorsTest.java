package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatDirectorsTest {
    @Test
    void twoBotsOfOnePersonalityRollDifferentDice() {
        Personality miner = new Personality("miner", "Miner", "Digs.", Traits.NEUTRAL, Map.of(), Map.of());
        AutopilotSpec spec = new AutopilotSpec("autopilot-miner", "miner", null, 5);
        assertNotEquals(ChatDirectors.seed("Ada", spec, miner), ChatDirectors.seed("Bob", spec, miner));
        assertEquals(ChatDirectors.seed("Ada", spec, miner), ChatDirectors.seed("ada", spec, miner));
    }
}
