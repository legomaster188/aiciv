package com.aiciv.flight;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Elytra flight and ender pearls. Filled in by its own task. */
public final class FlightModule implements ToolModule {
    @Override
    public String name() {
        return "flight";
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
