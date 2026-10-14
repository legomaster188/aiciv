package com.aiciv.dragon;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Fighting the ender dragon and leaving the End. Filled in by its own task. */
public final class DragonModule implements ToolModule {
    @Override
    public String name() {
        return "dragon";
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
