package com.aiciv.trade;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradeModuleTest {
    private final TradeModule module = new TradeModule();

    private static ActionFactory.Result call(String name, String... kv) {
        JsonObject a = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1].matches("-?\\d+")) a.addProperty(kv[i], Integer.parseInt(kv[i + 1]));
            else a.addProperty(kv[i], kv[i + 1]);
        }
        return ActionFactory.create(ToolCall.of(name, a));
    }

    private static String invalid(ActionFactory.Result r) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, r).reason();
    }

    @Test
    void tradeNeedsAWantOrAnOffer() {
        String expected = "trade needs \"want\" (an item) or \"offer\" (a number from trade_list)";
        assertEquals(expected, invalid(call("trade")));
        assertEquals(expected, invalid(call("trade", "villager", "farmer")));
    }

    @Test
    void tradeRefusesBothWantAndOffer() {
        assertEquals("use either \"want\" or \"offer\", not both", invalid(call("trade", "want", "bread", "offer", "1")));
    }

    @Test
    void tradeRefusesABadOffer() {
        assertTrue(invalid(call("trade", "offer", "-1")).contains("\"offer\""));
        assertTrue(invalid(call("trade", "offer", "soon")).contains("\"offer\""));
    }

    @Test
    void validCallsBuildActions() {
        assertInstanceOf(ActionFactory.Result.Ok.class, call("trade", "want", "bread"));
        assertInstanceOf(ActionFactory.Result.Ok.class, call("trade", "villager", "farmer", "offer", "0", "times", "99"));
        assertInstanceOf(ActionFactory.Result.Ok.class, call("trade_list"));
        assertInstanceOf(ActionFactory.Result.Ok.class, call("tradeList", "villager", "farmer"));
    }

    @Test
    void theModuleRegistersBothTools() {
        List<String> names = module.tools().stream().map(t -> t.getAsJsonObject("function").get("name").getAsString()).toList();
        assertEquals(List.of("trade_list", "trade"), names);
        assertEquals(1, module.guideLines().size());
    }

    private static SnapshotData withTags(String... tags) {
        return Samples.snapshot(List.of(), List.of(new SnapshotSection("Villagers", List.of("farmer 6m N"), null, List.of(tags))));
    }

    private List<MenuOption> options(SnapshotData snap, Map<String, Integer> inventory) {
        return module.menuOptions(snap, Samples.context(inventory));
    }

    @Test
    void tradeOptionOnlyWhenTheBotHoldsTheCost() {
        SnapshotData snap = withTags("villager:farmer", "offer:farmer:1:bread:emerald:1");
        assertTrue(options(snap, Map.of("emerald", 1)).stream().anyMatch(o -> o.id().equals("trade_farmer_1")));
        assertTrue(options(snap, Map.of()).stream().noneMatch(o -> o.id().equals("trade_farmer_1")));
        MenuOption o = options(snap, Map.of("emerald", 3)).stream().filter(x -> x.id().equals("trade_farmer_1")).findFirst().orElseThrow();
        assertEquals("trade 1 emerald for bread", o.description());
        assertEquals("trade", o.calls().get(0).name());
        assertEquals("farmer", o.calls().get(0).args().get("villager").getAsString());
        assertEquals(1, o.calls().get(0).args().get("offer").getAsInt());
    }

    @Test
    void tradeOptionNeedsEnoughOfTheCost() {
        SnapshotData snap = withTags("villager:farmer", "offer:farmer:0:emerald:wheat:20");
        assertTrue(options(snap, Map.of("wheat", 19)).stream().noneMatch(o -> o.id().equals("trade_farmer_0")));
        assertTrue(options(snap, Map.of("wheat", 20)).stream().anyMatch(o -> o.id().equals("trade_farmer_0")));
    }

    @Test
    void listOptionOnlyForVillagersWithoutRememberedOffers() {
        SnapshotData snap = withTags("villager:farmer", "villager:librarian", "offer:farmer:1:bread:emerald:1");
        List<String> ids = options(snap, Map.of()).stream().map(MenuOption::id).toList();
        assertTrue(ids.contains("trade_list_librarian"));
        assertFalse(ids.contains("trade_list_farmer"));
    }

    @Test
    void atMostThreeOfEachKind() {
        SnapshotData snap = withTags("villager:a", "villager:b", "villager:c", "villager:d",
                "offer:x:0:r:emerald:1", "offer:x:1:r:emerald:1", "offer:x:2:r:emerald:1", "offer:x:3:r:emerald:1");
        List<String> ids = options(snap, Map.of("emerald", 5)).stream().map(MenuOption::id).toList();
        assertEquals(3, ids.stream().filter(i -> i.startsWith("trade_list_")).count());
        assertEquals(3, ids.stream().filter(i -> i.startsWith("trade_x_")).count());
    }
}
