package com.aiciv.modules;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.ToolCatalog;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The catalog, the factory and the menus must agree for every module, so later modules are checked automatically. */
class ModuleSyncTest {
    private static JsonObject function(JsonElement tool) {
        return tool.getAsJsonObject().getAsJsonObject("function");
    }

    /** Normalised tool name -> its parameter names, over the whole catalog (core and modules). */
    private static Map<String, Set<String>> catalogProperties() {
        Map<String, Set<String>> out = new HashMap<>();
        for (JsonElement t : ToolCatalog.tools()) {
            JsonObject fn = function(t);
            Set<String> names = new HashSet<>();
            if (fn.has("parameters")) names.addAll(fn.getAsJsonObject("parameters").getAsJsonObject("properties").keySet());
            out.put(ToolModules.normalise(fn.get("name").getAsString()), names);
        }
        return out;
    }

    @Test
    void everyCatalogFunctionIsAcceptedAndDescribed() {
        for (JsonElement t : ToolCatalog.tools()) {
            JsonObject fn = function(t);
            String name = fn.get("name").getAsString();
            ActionFactory.Result r = ActionFactory.create(ToolCall.of(name, new JsonObject()));
            assertFalse(r.toString().contains("unknown action"), name + ": " + r);
            assertFalse(fn.get("description").getAsString().isBlank(), name);
        }
    }

    @Test
    void factoryKeysEqualTheCatalogNames() {
        Set<String> catalog = new HashSet<>();
        for (JsonElement t : ToolCatalog.tools()) catalog.add(ToolModules.normalise(function(t).get("name").getAsString()));
        assertEquals(catalog, ActionFactory.knownKeys());
    }

    @Test
    void moduleFunctionsNameTheirFirstMissingRequiredArgument() {
        for (ToolModule m : ToolModules.ALL) {
            for (JsonObject t : m.tools()) {
                JsonObject fn = t.getAsJsonObject("function");
                if (!fn.has("parameters")) continue;
                var required = fn.getAsJsonObject("parameters").getAsJsonArray("required");
                if (required == null || required.isEmpty()) continue;
                String name = fn.get("name").getAsString();
                ActionFactory.Result r = ActionFactory.create(ToolCall.of(name, new JsonObject()));
                ActionFactory.Result.Invalid invalid = assertInstanceOf(ActionFactory.Result.Invalid.class, r, m.name() + "/" + name);
                String first = required.get(0).getAsString();
                assertTrue(invalid.reason().contains("\"" + first + "\""), m.name() + "/" + name + ": " + invalid.reason());
            }
        }
    }

    @Test
    void moduleMenuArgumentsAreCatalogParameters() {
        Map<String, Set<String>> props = catalogProperties();
        for (ToolModule m : ToolModules.ALL) {
            for (MenuOption o : m.menuOptions(m.sampleSnapshot(), m.sampleContext())) {
                for (ToolCall call : o.calls()) {
                    Set<String> allowed = props.get(ToolModules.normalise(call.name()));
                    assertNotNull(allowed, m.name() + "/" + o.id() + " calls unknown tool " + call.name());
                    for (String arg : call.args().keySet()) {
                        assertTrue(allowed.contains(arg), m.name() + "/" + o.id() + ": " + call.name() + " has no parameter " + arg);
                    }
                }
            }
        }
    }

    /** The catalog goes with every tool-mode request, so the milestone-4 modules keep their texts short. */
    @Test
    void milestoneFourDescriptionsStayShort() {
        Set<String> capped = Set.of("dimension", "nether", "stronghold", "dragon", "vehicles", "flight", "items", "animals", "stations");
        Set<String> seen = new HashSet<>();
        for (ToolModule m : ToolModules.ALL) {
            if (!capped.contains(m.name())) continue;
            seen.add(m.name());
            for (JsonObject t : m.tools()) {
                JsonObject fn = t.getAsJsonObject("function");
                String name = fn.get("name").getAsString();
                assertTrue(fn.get("description").getAsString().length() <= 200, m.name() + "/" + name + " description is over 200 characters");
                if (!fn.has("parameters")) continue;
                for (Map.Entry<String, JsonElement> p : fn.getAsJsonObject("parameters").getAsJsonObject("properties").entrySet()) {
                    JsonElement d = p.getValue().getAsJsonObject().get("description");
                    assertTrue(d == null || d.getAsString().length() <= 100, m.name() + "/" + name + "." + p.getKey() + " description is over 100 characters");
                }
            }
        }
        assertEquals(capped, seen, "every milestone-4 module is registered");
    }

    @Test
    void everyModuleWithToolsOffersValidMenuOptionsInItsSample() {
        for (ToolModule m : ToolModules.ALL) {
            if (m.tools().isEmpty()) continue; // a stub not filled in yet
            List<MenuOption> options = m.menuOptions(m.sampleSnapshot(), m.sampleContext());
            assertFalse(options.isEmpty(), m.name() + " offers no menu option in its sample situation");
            for (MenuOption o : options) {
                assertTrue(OptionMenu.ID.matcher(o.id()).matches(), m.name() + ": bad id " + o.id());
                for (ToolCall call : o.calls()) {
                    ActionFactory.Result r = ActionFactory.create(call);
                    assertInstanceOf(ActionFactory.Result.Ok.class, r, m.name() + "/" + o.id() + " -> " + call.name() + " " + call.args() + ": " + r);
                }
            }
        }
    }
}
