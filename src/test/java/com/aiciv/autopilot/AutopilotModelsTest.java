package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.config.AiCivConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutopilotModelsTest {
    private static final Set<String> IDS = Set.of("miner", "guard", "default");

    private static AiCivConfig config() {
        return AiCivConfig.GSON.fromJson("""
                {"providers": {"p": {"baseUrl": "http://x"}},
                 "models": {"m": {"provider": "p", "model": "x"},
                            "guardbot": {"type": "autopilot", "personality": "guard", "chatModel": "groq-llama", "thinkSeconds": 2},
                            "lost": {"type": "autopilot", "personality": "pirate"}},
                 "defaultModel": "m", "autopilot": {"thinkSeconds": 7}}""", AiCivConfig.class);
    }

    @Test
    void implicitKeysResolveForKnownPersonalities() {
        AutopilotSpec dash = AutopilotModels.resolve("autopilot-miner", config(), IDS).orElseThrow();
        assertEquals("autopilot-miner", dash.modelKey());
        assertEquals("miner", dash.personality());
        assertNull(dash.chatModel());
        assertEquals(7, dash.thinkSeconds());
        AutopilotSpec colon = AutopilotModels.resolve("autopilot:Miner", config(), IDS).orElseThrow();
        assertEquals("miner", colon.personality());
        assertEquals("autopilot:miner", colon.modelKey());
        assertTrue(AutopilotModels.resolve("autopilot-pirate", config(), IDS).isEmpty());
        assertTrue(AutopilotModels.resolve("m", config(), IDS).isEmpty());
        assertTrue(AutopilotModels.resolve("nonsense", config(), IDS).isEmpty());
    }

    @Test
    void configuredAutopilotUsesItsOwnValues() {
        AutopilotSpec s = AutopilotModels.resolve("guardbot", config(), IDS).orElseThrow();
        assertEquals(new AutopilotSpec("guardbot", "guard", "groq-llama", 2), s);
    }

    @Test
    void configuredAutopilotWithUnknownPersonalityResolvesEmpty() {
        assertTrue(AutopilotModels.resolve("lost", config(), IDS).isEmpty());
    }

    @Test
    void thinkSecondsIsAtLeastOne() {
        AiCivConfig c = config();
        c.autopilot.thinkSeconds = 0;
        assertEquals(1, AutopilotModels.resolve("autopilot-miner", c, IDS).orElseThrow().thinkSeconds());
    }

    @Test
    void implicitKeysAreSorted() {
        assertEquals(List.of("autopilot-default", "autopilot-guard", "autopilot-miner"), AutopilotModels.implicitKeys(IDS));
    }
}
