package com.aiciv.nether;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** The nether: portals, fortresses and blazes. Filled in by its own task. */
public final class NetherModule implements ToolModule {
    @Override
    public String name() {
        return "nether";
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
