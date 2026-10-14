package com.aiciv.items;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModule;
import com.google.gson.JsonObject;
import java.util.List;

/** Buckets, bone meal, fire, potions, throwables, maps and compasses. Filled in by its own task. */
public final class ItemsModule implements ToolModule {
    @Override
    public String name() {
        return "items";
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
