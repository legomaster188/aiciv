package com.aiciv.stronghold;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Eyes of ender, finding the stronghold and opening the End portal. Filled in by its own task. */
public final class StrongholdModule implements ToolModule {
    @Override
    public String name() {
        return "stronghold";
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
