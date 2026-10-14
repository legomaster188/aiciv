package com.aiciv.modules;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.StopAction;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolModulesTest {
    /** A tiny module offering the given tool names; {@code create} answers its own keys with {@code result}. */
    static ToolModule module(String name, ActionFactory.Result result, String... tools) {
        return new ToolModule() {
            @Override public String name() { return name; }

            @Override public List<JsonObject> tools() {
                return java.util.Arrays.stream(tools).map(t -> Tools.fn(t, "does " + t, null)).toList();
            }

            @Override public ActionFactory.Result create(String key, JsonObject args) {
                for (String t : tools) if (ToolModules.normalise(t).equals(key)) return result;
                return null;
            }
        };
    }

    @Test
    void createFromReturnsTheFirstModulesResult() {
        ActionFactory.Result first = new ActionFactory.Result.Ok(new StopAction());
        ActionFactory.Result second = new ActionFactory.Result.Invalid("second");
        List<ToolModule> modules = List.of(module("a", first, "place_chest"), module("b", second, "place_chest", "dig"));
        assertSame(first, ToolModules.createFrom(modules, "placechest", new JsonObject()));
        assertSame(second, ToolModules.createFrom(modules, "dig", new JsonObject()));
        assertNull(ToolModules.createFrom(modules, "fly", new JsonObject()));
    }

    @Test
    void normaliseMatchesTheFactoryRule() {
        assertEquals("placechest", ToolModules.normalise("Place_Chest"));
        assertEquals("lookinchest", ToolModules.normalise("look-in_chest"));
    }

    @Test
    void checkUniqueRejectsTwoModulesWithTheSameTool() {
        List<ToolModule> modules = List.of(module("storage", null, "place_chest"), module("building", null, "placeChest"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ToolModules.checkUnique(modules, Set.of("say")));
        assertTrue(e.getMessage().contains("storage") && e.getMessage().contains("building"), e.getMessage());
        assertTrue(e.getMessage().contains("placeChest") || e.getMessage().contains("place_chest"), e.getMessage());
    }

    @Test
    void checkUniqueRejectsACoreName() {
        List<ToolModule> modules = List.of(module("building", null, "place"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ToolModules.checkUnique(modules, Set.of("say", "place")));
        assertTrue(e.getMessage().contains("'place'") && e.getMessage().contains("building") && e.getMessage().contains("core"), e.getMessage());
    }

    @Test
    void checkUniqueAcceptsDistinctTools() {
        ToolModules.checkUnique(List.of(module("a", null, "dig"), module("b", null, "build")), Set.of("say"));
    }

    @Test
    void aModuleWhoseMenuThrowsContributesNothing() {
        ToolModule broken = new ToolModule() {
            @Override public String name() { return "broken"; }
            @Override public List<JsonObject> tools() { return List.of(); }
            @Override public ActionFactory.Result create(String key, JsonObject args) { return null; }
            @Override public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        ToolModule fine = new ToolModule() {
            @Override public String name() { return "fine"; }
            @Override public List<JsonObject> tools() { return List.of(); }
            @Override public ActionFactory.Result create(String key, JsonObject args) { return null; }
            @Override public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
                return List.of(new MenuOption("fine_one", "a fine option", List.of(ToolCall.of("stop", new JsonObject()))));
            }
        };
        List<MenuOption> options = ToolModules.menuOptionsFrom(List.of(broken, fine),
                Samples.snapshot(List.of(), List.of()), Samples.context(Map.of()));
        assertEquals(List.of("fine_one"), options.stream().map(MenuOption::id).toList());
    }

    @Test
    void theRegistryHoldsTheFeatureModules() {
        assertEquals(List.of("building", "storage", "combat", "trade", "farm", "utility", "gear", "dimension", "nether",
                        "stronghold", "dragon", "vehicles", "flight", "items", "animals", "stations"),
                ToolModules.ALL.stream().map(ToolModule::name).toList());
    }
}
