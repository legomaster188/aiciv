package com.aiciv.panel;

import com.aiciv.bot.BotManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Validation of POST bodies. Every method returns a problem for the 400 response, or null when the input is fine. */
public final class PanelInput {
    static final int MAX_PERSONA = 2000;
    static final int MAX_GOAL = 2000;
    static final int MAX_SAY = 256;

    private PanelInput() {}

    /** The field as a string, or null when it is missing or not a string. */
    public static String string(JsonObject body, String field) {
        JsonElement e = body.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    public static String checkName(String name) {
        if (name == null) return "name is required";
        return BotManager.isValidName(name) ? null : "name must be 3 to 16 letters, digits or _";
    }

    /** A bot name, or "all" where the action covers everyone. */
    public static String checkTarget(String name) {
        return "all".equalsIgnoreCase(name) ? null : checkName(name);
    }

    public static String checkModel(String model, Set<String> known) {
        if (model == null || model.isBlank()) return "model is required";
        return known.contains(model) ? null : "unknown model '" + model + "'";
    }

    public static String checkText(String field, String text, int max, boolean required) {
        if (text == null || text.isBlank()) return required ? field + " is required" : null;
        return text.length() > max ? field + " is longer than " + max + " characters" : null;
    }
}
