package com.aiciv.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builders for OpenAI function-calling JSON, shared by the core catalog and every module. */
public final class Tools {
    private Tools() {}

    /** A function tool; {@code properties} may be null for a function without arguments. */
    public static JsonObject fn(String name, String description, JsonObject properties, String... required) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        if (properties != null) {
            JsonObject params = new JsonObject();
            params.addProperty("type", "object");
            params.add("properties", properties);
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            params.add("required", req);
            function.add("parameters", params);
        }
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    /** A properties object holding one property; add more with {@code add(name, prop(...))}. */
    public static JsonObject props(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.add(name, prop(type, description));
        return p;
    }

    public static JsonObject prop(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    public static JsonObject stringArray(String description) {
        JsonObject p = prop("array", description);
        p.add("items", type("string"));
        return p;
    }

    public static JsonObject arrayOfStringArrays(String description) {
        JsonObject inner = type("array");
        inner.add("items", type("string"));
        JsonObject p = prop("array", description);
        p.add("items", inner);
        return p;
    }

    /** {"type":"object","additionalProperties":{"type":"string"}}. */
    public static JsonObject stringMap(String description) {
        JsonObject p = prop("object", description);
        p.add("additionalProperties", type("string"));
        return p;
    }

    public static JsonObject objectArray(JsonObject itemProperties, String description) {
        JsonObject item = type("object");
        item.add("properties", itemProperties);
        JsonObject p = prop("array", description);
        p.add("items", item);
        return p;
    }

    private static JsonObject type(String type) {
        JsonObject t = new JsonObject();
        t.addProperty("type", type);
        return t;
    }
}
