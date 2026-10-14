package com.aiciv.autopilot;

import com.aiciv.llm.ToolCall;
import com.google.gson.JsonObject;

/** Builds the same {@link ToolCall}s an LLM would send. */
public final class Calls {
    private Calls() {}

    /** key, value, key, value...; values are strings or numbers (anything else becomes its string). */
    public static ToolCall of(String name, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
            else args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return ToolCall.of(name, args);
    }
}
