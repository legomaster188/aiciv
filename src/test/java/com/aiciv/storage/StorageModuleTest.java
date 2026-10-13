package com.aiciv.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageModuleTest {
    private final StorageModule module = new StorageModule();

    private static ActionFactory.Result create(String name, String json) {
        return ActionFactory.create(ToolCall.of(name, JsonParser.parseString(json).getAsJsonObject()));
    }

    private static String invalid(String name, String json) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, create(name, json), name + " " + json).reason();
    }

    @Test
    void depositWithGarbageItemsNamesTheArgument() {
        assertTrue(invalid("deposit", "{\"items\":\"???\"}").contains("\"items\""));
        assertTrue(invalid("deposit", "{}").contains("\"items\""));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("deposit", "{\"items\":\"all_except_tools\"}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("deposit", "{\"items\":\"cobblestone x20, oak_log\",\"x\":1,\"y\":64,\"z\":2}"));
    }

    @Test
    void missingArgumentsAreNamedInQuotes() {
        assertTrue(invalid("withdraw", "{}").contains("\"item\""));
        assertTrue(invalid("give", "{}").contains("\"name\""));
        assertTrue(invalid("give", "{\"name\":\"Bob\"}").contains("\"item\""));
        assertTrue(invalid("drop", "{}").contains("\"item\""));
        assertTrue(invalid("rename", "{}").contains("\"item\""));
        assertTrue(invalid("rename", "{\"item\":\"name_tag\"}").contains("\"name\""));
        assertTrue(invalid("name_mob", "{}").contains("\"target\""));
        assertTrue(invalid("name_mob", "{\"target\":\"cow\"}").contains("\"name\""));
        assertTrue(invalid("sign", "{}").contains("\"text\""));
    }

    @Test
    void lengthsAndPositionsAreChecked() {
        assertTrue(invalid("rename", "{\"item\":\"name_tag\",\"name\":\"" + "a".repeat(51) + "\"}").contains("\"name\""));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("rename", "{\"item\":\"name_tag\",\"name\":\"" + "a".repeat(50) + "\"}"));
        assertTrue(invalid("sign", "{\"text\":\"" + "a".repeat(61) + "\"}").contains("\"text\""));
        assertTrue(invalid("look_in_chest", "{\"x\":1}").contains("x, y and z"));
        assertTrue(invalid("withdraw", "{\"item\":\"dirt\",\"x\":1,\"y\":2}").contains("x, y and z"));
    }

    @Test
    void keysAcceptTheNormalisedSpellings() {
        for (String name : new String[] {"place_chest", "placeChest", "look-in-chest", "lookinchest", "name_mob", "NameMob"}) {
            assertFalse(create(name, "{\"target\":\"cow\",\"name\":\"Bessie\"}").toString().contains("unknown action"), name);
        }
    }

    @Test
    void sampleMenuOffersChestOptions() {
        List<String> ids = module.menuOptions(module.sampleSnapshot(), module.sampleContext()).stream().map(MenuOption::id).toList();
        assertEquals(List.of("look_in_chest", "deposit_all_except_tools", "withdraw_cobblestone"), ids);
    }

    @Test
    void placeChestIsOfferedOnlyWithAChestInHandAndNoneInSight() {
        SnapshotData none = Samples.snapshot(List.of(), List.of());
        assertEquals(List.of("place_chest"), module.menuOptions(none, Samples.context(Map.of("chest", 1))).stream().map(MenuOption::id).toList());
        assertTrue(module.menuOptions(none, Samples.context(Map.of("dirt", 1))).isEmpty());
        SnapshotData far = Samples.snapshot(List.of(new Sighting("chest", 1, 30, "N")), List.of());
        assertEquals(List.of("place_chest"), module.menuOptions(far, Samples.context(Map.of("chest", 1))).stream().map(MenuOption::id).toList());
    }

    @Test
    void depositNeedsABusyInventory() {
        SnapshotData near = Samples.snapshot(List.of(new Sighting("barrel", 1, 3, "N")), List.of());
        List<String> ids = module.menuOptions(near, Samples.context(Map.of("dirt", 1))).stream().map(MenuOption::id).toList();
        assertEquals(List.of("look_in_chest"), ids);
    }

    @Test
    void atMostThreeWithdrawOptions() {
        SnapshotSection chest = new SnapshotSection("Chests", List.of("chest at 1 2 3"), null,
                List.of("chest_item:a", "chest_item:b", "chest_item:c", "chest_item:d"));
        List<String> ids = module.menuOptions(Samples.snapshot(List.of(), List.of(chest)), Samples.context(Map.of())).stream().map(MenuOption::id).toList();
        assertEquals(List.of("withdraw_a", "withdraw_b", "withdraw_c"), ids);
    }

    @Test
    void toolsCarryTheDescribedParameters() {
        JsonObject give = module.tools().stream().map(t -> t.getAsJsonObject("function")).filter(f -> f.get("name").getAsString().equals("give")).findFirst().orElseThrow();
        assertEquals(List.of("name", "item", "count"), List.copyOf(give.getAsJsonObject("parameters").getAsJsonObject("properties").keySet()));
    }

    @Test
    void withdrawOptionNamesTheRememberedChest() {
        SnapshotSection chest = new SnapshotSection("Chests", List.of("chest at 12 64 -7"), null, List.of("chest_item:dirt", "chest_at:12:64:-7"));
        MenuOption o = module.menuOptions(Samples.snapshot(List.of(), List.of(chest)), Samples.context(Map.of())).stream()
                .filter(x -> x.id().equals("withdraw_dirt")).findFirst().orElseThrow();
        JsonObject args = o.calls().get(0).args();
        assertEquals(12, args.get("x").getAsInt());
        assertEquals(64, args.get("y").getAsInt());
        assertEquals(-7, args.get("z").getAsInt());
        assertEquals("dirt", args.get("item").getAsString());
    }
}
