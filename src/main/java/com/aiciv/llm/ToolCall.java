package com.aiciv.llm;

import com.google.gson.JsonObject;

/** One action the model asked for. argsError is non-null when its arguments could not be read. */
public record ToolCall(String name, JsonObject args, String argsError) {
    public static ToolCall of(String name, JsonObject args) {
        return new ToolCall(name, args, null);
    }
}
