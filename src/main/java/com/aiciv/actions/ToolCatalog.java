package com.aiciv.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The actions offered to models, in OpenAI function-calling format. */
public final class ToolCatalog {
    private ToolCatalog() {}

    public static JsonArray tools() {
        JsonArray tools = new JsonArray();
        tools.add(fn("say", "Say something in public chat. Players nearby hear it.",
                props("text", "string", "What to say. One short chat message."), "text"));
        JsonObject xyz = new JsonObject();
        xyz.add("x", prop("integer", "Block x (east is positive)."));
        xyz.add("y", prop("integer", "Block y (height)."));
        xyz.add("z", prop("integer", "Block z (south is positive)."));
        tools.add(fn("goTo", "Walk to block coordinates. Finishes within 1.5 blocks of them.", xyz, "x", "y", "z"));
        tools.add(fn("goToPlayer", "Walk to a player until you are within 3 blocks of them.",
                props("name", "string", "The player's exact name."), "name"));
        JsonObject follow = props("name", "string", "The player's exact name.");
        follow.add("seconds", prop("integer", "How long to follow, 1 to 300. Default 60."));
        tools.add(fn("follow", "Follow a player for a while, staying close to them.", follow, "name"));
        tools.add(fn("stop", "Stop what you are doing and stand still.", null));
        return tools;
    }

    public static String guide() {
        return """
                Actions: say, goTo, goToPlayer, follow, stop. Each one ends as done or failed and you will be told which.
                If you cannot call functions, reply with only a JSON array instead, for example:
                [{"action": "say", "args": {"text": "hello"}}, {"action": "goToPlayer", "args": {"name": "Steve"}}]""";
    }

    private static JsonObject fn(String name, String description, JsonObject properties, String... required) {
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

    private static JsonObject props(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.add(name, prop(type, description));
        return p;
    }

    private static JsonObject prop(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }
}
