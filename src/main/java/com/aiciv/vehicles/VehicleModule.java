package com.aiciv.vehicles;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Boats, minecarts and mounts. Filled in by its own task. */
public final class VehicleModule implements ToolModule {
    @Override
    public String name() {
        return "vehicles";
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
