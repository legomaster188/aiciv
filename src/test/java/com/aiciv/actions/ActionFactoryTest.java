package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.llm.ToolCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionFactoryTest {
    static ToolCall call(String name, String argsJson) {
        return ToolCall.of(name, JsonParser.parseString(argsJson).getAsJsonObject());
    }

    static Action ok(ToolCall c) {
        return assertInstanceOf(ActionFactory.Result.Ok.class, ActionFactory.create(c)).action();
    }

    static String invalid(ToolCall c) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, ActionFactory.create(c)).reason();
    }

    @Test
    void coordinatesMayBeStringsOrFloats() {
        assertEquals("goTo 12 64 -4", ok(call("goTo", "{\"x\": \"12\", \"y\": 64.0, \"z\": -3.2}")).describe());
        assertEquals("goTo 12 64 3", ok(call("goTo", "{\"x\": 12.7, \"y\": \" 64 \", \"z\": 3}")).describe());
    }

    @Test
    void namesAreForgiving() {
        assertInstanceOf(GoToAction.class, ok(call("go_to", "{\"x\":1,\"y\":64,\"z\":1}")));
        assertInstanceOf(GoToAction.class, ok(call("GoTo", "{\"x\":1,\"y\":64,\"z\":1}")));
        assertInstanceOf(GoToPlayerAction.class, ok(call("go_to_player", "{\"name\":\"Steve\"}")));
        assertInstanceOf(StopAction.class, ok(call("STOP", "{}")));
    }

    @Test
    void unknownActionListsTheRealOnes() {
        String r = invalid(call("fly", "{}"));
        assertTrue(r.contains("unknown action 'fly'"), r);
        assertTrue(r.contains("say, goTo, goToPlayer, follow, explore, mine, collect, craft, smelt, place, eat, equip, attack, stop"), r);
    }

    @Test
    void badArgumentsAreReported() {
        assertTrue(invalid(call("goTo", "{\"x\": \"twelve\", \"y\": 64, \"z\": 0}")).contains("whole numbers"));
        assertTrue(invalid(call("goTo", "{\"x\": 0, \"y\": 999, \"z\": 0}")).contains("between -64 and 320"));
        assertTrue(invalid(call("goTo", "{\"x\": 40000000, \"y\": 64, \"z\": 0}")).contains("world border"));
        assertTrue(invalid(call("goTo", "{\"x\": -3000000000, \"y\": 64, \"z\": 0}")).contains("world border"));
        assertTrue(invalid(call("say", "{\"text\": \"\\n \\t\"}")).contains("non-empty"));
        assertTrue(invalid(call("goToPlayer", "{}")).contains("name"));
        assertTrue(invalid(new ToolCall("goTo", new JsonObject(), "arguments for goTo were not a valid JSON object")).contains("not a valid JSON"));
    }

    @Test
    void sayIsCleanedAndCapped() {
        SayAction a = (SayAction) ok(call("say", "{\"text\": \"hi\\nthere\\u00a7c  friend\"}"));
        assertEquals("hi there c friend", a.text());
        String longText = "x".repeat(400);
        assertEquals(256, ((SayAction) ok(call("say", "{\"text\": \"" + longText + "\"}"))).text().length());
    }

    @Test
    void followDefaultsAndClamps() {
        assertEquals("follow Steve 60s", ok(call("follow", "{\"name\": \"Steve\"}")).describe());
        assertEquals("follow Steve 300s", ok(call("follow", "{\"name\": \"Steve\", \"seconds\": 999}")).describe());
        assertEquals("follow Steve 1s", ok(call("follow", "{\"name\": \"Steve\", \"seconds\": -5}")).describe());
    }

    @Test
    void catalogNamesMatchTheFactory() {
        var tools = ToolCatalog.tools();
        assertEquals(14 + com.aiciv.modules.ToolModules.tools().size(), tools.size());
        Set<String> catalog = new HashSet<>();
        for (var t : tools) {
            String name = t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
            catalog.add(key(name));
            assertFalse(ActionFactory.create(ToolCall.of(name, new JsonObject())).toString().contains("unknown action"), name);
            assertFalse(t.getAsJsonObject().getAsJsonObject("function").get("description").getAsString().isBlank(), name);
        }
        assertEquals(ActionFactory.knownKeys(), catalog);
        for (String name : ActionFactory.available().split(", ")) assertTrue(catalog.contains(key(name)), name);
        assertEquals(tools.size(), ActionFactory.available().split(", ").length);
    }

    private static String key(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
    }

    @Test
    void exploreDescriptionDoesNotPromiseToAvoidWaterAndCliffs() {
        for (var t : ToolCatalog.tools()) {
            var fn = t.getAsJsonObject().getAsJsonObject("function");
            if (!fn.get("name").getAsString().equals("explore")) continue;
            String text = fn.get("description").getAsString();
            assertFalse(text.contains("cliffs"), text);
            assertTrue(text.contains("swim") && text.contains("drop up to 3 blocks"), text);
            return;
        }
        fail("no explore tool");
    }

    @Test
    void guideExplainsChaining() {
        assertTrue(ToolCatalog.guide().contains("chain up to 6 actions"));
    }

    @Test
    void guideListsEveryAvailableAction() {
        assertTrue(ToolCatalog.guide().startsWith("Actions: " + ActionFactory.available() + ". Each one ends as done or failed"));
        for (String line : com.aiciv.modules.ToolModules.guideLines()) assertTrue(ToolCatalog.guide().contains(line), line);
    }

    @Test
    void craftSmeltAndMineDefaultAndClampCounts() {
        assertEquals(CraftAction.class, ok(call("craft", "{\"item\": \"wooden_pickaxe\"}")).getClass());
        assertTrue(ok(call("craft", "{\"item\": \"stick\"}")).describe().contains("1"));
        assertTrue(ok(call("craft", "{\"item\": \"stick\", \"count\": 500}")).describe().contains("64"));
        assertTrue(ok(call("craft", "{\"item\": \"stick\", \"count\": -3}")).describe().contains("1"));
        assertEquals(SmeltAction.class, ok(call("smelt", "{\"item\": \"raw_iron\", \"count\": 99}")).getClass());
        assertTrue(ok(call("smelt", "{\"item\": \"raw_iron\", \"count\": 99}")).describe().contains("64"));
        assertTrue(ok(call("mine", "{\"block\": \"stone\"}")).describe().contains("1"));
        assertTrue(invalid(call("craft", "{}")).contains("item"));
        assertTrue(invalid(call("smelt", "{\"item\": \" \"}")).contains("item"));
    }

    @Test
    void placeEatEquipAndAttackAreBuilt() {
        assertEquals(PlaceAction.class, ok(call("place", "{\"block\": \"torch\", \"x\": 1, \"y\": 64, \"z\": 2}")).getClass());
        assertTrue(invalid(call("place", "{\"block\": \"torch\", \"x\": 1, \"y\": 64}")).contains("x, y and z"));
        assertTrue(invalid(call("place", "{\"x\": 1, \"y\": 64, \"z\": 2}")).contains("block"));
        assertTrue(invalid(call("place", "{\"block\": \"torch\", \"x\": 1, \"y\": 999, \"z\": 2}")).contains("between -64 and 320"));
        assertInstanceOf(EatAction.class, ok(call("eat", "{}")));
        assertInstanceOf(EquipAction.class, ok(call("equip", "{\"item\": \"iron_chestplate\"}")));
        assertTrue(invalid(call("equip", "{}")).contains("item"));
        assertInstanceOf(AttackAction.class, ok(call("attack", "{\"target\": \"zombie\"}")));
        assertTrue(invalid(call("attack", "{}")).contains("target"));
    }

    @Test
    void exploreMineAndCollectAreBuiltWithClampedNumbers() {
        assertEquals(ExploreAction.class, ok(call("explore", "{\"direction\": \"N\", \"distance\": 999}")).getClass());
        assertTrue(ok(call("explore", "{\"direction\": \"N\"}")).describe().contains("64"));
        assertTrue(ok(call("explore", "{\"direction\": \"N\", \"distance\": 999}")).describe().contains("128"));
        assertTrue(invalid(call("explore", "{\"direction\": \"up\"}")).contains("direction"));
        assertTrue(ok(call("mine", "{\"block\": \"oak_log\", \"count\": 500}")).describe().contains("64"));
        assertTrue(invalid(call("mine", "{}")).contains("block"));
        assertInstanceOf(CollectAction.class, ok(call("collect", "{\"item\": \"oak_log\"}")));
        assertTrue(invalid(call("collect", "{}")).contains("item"));
    }
}
