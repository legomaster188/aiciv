package com.aiciv.panel;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.llm.LlmService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class PanelSnapshotTest {
    @Test
    void serialisesTheShapeThePageReads() {
        JsonObject decision = JsonParser.parseString("{\"trigger\": \"IDLE\", \"option\": \"mine_log\", \"probabilities\": {\"mine_log\": 0.8}}").getAsJsonObject();
        PanelSnapshot.BotView bot = new PanelSnapshot.BotView("Ada", "jev", "typesafe", "menu", false, false, 18.5, 17,
                1.5, 64, -3.5, "minecraft:overworld", "3 oak_log", "mine oak_log", null, decision, "build a house", "cheerful");
        PanelSnapshot s = new PanelSnapshot("day 2, 07:30", 1500, List.of(bot),
                List.of(new PanelSnapshot.ModelView("jev", "typesafe", "systemone", "menu")),
                List.of(new LlmService.ProviderInfo("typesafe", 10, 250, 4, true, false, "ok")));

        JsonObject json = new Gson().toJsonTree(s).getAsJsonObject();
        assertEquals("day 2, 07:30", json.get("time").getAsString());
        assertEquals(1500, json.get("dayTime").getAsLong());
        JsonObject b = json.getAsJsonArray("bots").get(0).getAsJsonObject();
        for (String field : List.of("name", "model", "provider", "mode", "paused", "dead", "health", "food", "x", "y", "z",
                "dimension", "holding", "action", "lastDecision", "goal", "persona")) {
            assertTrue(b.has(field), "bot is missing " + field);
        }
        assertFalse(b.has("reflex"), "an inactive reflex is left out");
        assertEquals("mine_log", b.getAsJsonObject("lastDecision").get("option").getAsString());
        JsonArray models = json.getAsJsonArray("models");
        assertEquals("jev", models.get(0).getAsJsonObject().get("key").getAsString());
        JsonObject provider = json.getAsJsonArray("providers").get(0).getAsJsonObject();
        assertEquals(250, provider.get("rpd").getAsInt());
        assertEquals(4, provider.get("usedToday").getAsInt());
        assertEquals("ok", provider.get("status").getAsString());
    }

    @Test
    void providerInfoNeverCarriesAKeyField() {
        for (var f : LlmService.ProviderInfo.class.getRecordComponents()) {
            assertFalse(f.getName().toLowerCase().contains("key"), f.getName());
        }
    }
}
