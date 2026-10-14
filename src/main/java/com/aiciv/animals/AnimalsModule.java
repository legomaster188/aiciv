package com.aiciv.animals;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Taming and pets. Filled in by its own task. */
public final class AnimalsModule implements ToolModule {
    @Override
    public String name() {
        return "animals";
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
