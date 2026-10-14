package com.aiciv.stations;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Smithing, stonecutting, grindstones, looms, cartography and books. Filled in by its own task. */
public final class StationsModule implements ToolModule {
    @Override
    public String name() {
        return "stations";
    }

    @Override
    public List<JsonObject> tools() {
        return List.of();
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        return null;
    }
}
