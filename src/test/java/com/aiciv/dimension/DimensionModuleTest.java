package com.aiciv.dimension;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DimensionModuleTest {
    private final DimensionModule module = new DimensionModule();

    private ActionFactory.Result create(String json) {
        return ActionFactory.create(ToolCall.of("enter_portal", JsonParser.parseString(json).getAsJsonObject()));
    }

    @Test
    void enterPortalRejectsUnknownKinds() {
        ActionFactory.Result.Invalid invalid = assertInstanceOf(ActionFactory.Result.Invalid.class, create("{\"kind\": \"lava\"}"));
        assertTrue(invalid.reason().contains("kind must be"), invalid.reason());
    }

    @Test
    void enterPortalDefaultsToAny() {
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("{}"));
        assertEquals("enter any portal", ok.action().describe());
        assertTrue(ok.action().survivesDimensionChange());
        ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("{\"kind\": \"Nether\"}"));
        assertEquals("enter nether portal", ok.action().describe());
    }

    private List<String> ids(Sighting... blocks) {
        return module.menuOptions(Samples.snapshot(List.of(blocks), List.of()), Samples.context(Map.of()))
                .stream().map(MenuOption::id).toList();
    }

    @Test
    void menuOffersTheNetherPortalOnlyWhenOneIsSeenNearby() {
        assertEquals(List.of("enter_nether_portal"), ids(new Sighting("nether_portal", 6, 5, "N")));
        assertEquals(List.of(), ids());
        assertEquals(List.of(), ids(new Sighting("nether_portal", 6, 20, "N")));
        assertEquals(List.of("enter_end_portal"), ids(new Sighting("end_gateway", 1, 9, "E")));
        MenuOption option = module.menuOptions(module.sampleSnapshot(), module.sampleContext()).get(0);
        assertEquals("nether", option.calls().get(0).args().get("kind").getAsString());
    }
}
