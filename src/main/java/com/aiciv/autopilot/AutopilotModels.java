package com.aiciv.autopilot;

import com.aiciv.config.AiCivConfig;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a model key into an autopilot spec. Brigadier words cannot hold ':', so commands use autopilot-<id>. */
public final class AutopilotModels {
    public static final Pattern IMPLICIT = Pattern.compile("autopilot[-:]([a-z0-9_]{1,24})");

    private AutopilotModels() {}

    /** A configured model with type "autopilot", else autopilot-<id>/autopilot:<id> (key lower-cased) for a known personality. */
    public static Optional<AutopilotSpec> resolve(String modelKey, AiCivConfig config, Set<String> personalityIds) {
        if (modelKey == null) return Optional.empty();
        int defaultThink = config.autopilot == null ? 5 : config.autopilot.thinkSeconds;
        AiCivConfig.ModelConfig m = config.models.get(modelKey);
        if (m != null) {
            if (!m.isAutopilot() || m.personality == null || !personalityIds.contains(m.personality)) return Optional.empty();
            int think = m.thinkSeconds != null ? m.thinkSeconds : defaultThink;
            return Optional.of(new AutopilotSpec(modelKey, m.personality, m.chatModel, Math.max(1, think)));
        }
        String key = modelKey.toLowerCase(Locale.ROOT);
        Matcher match = IMPLICIT.matcher(key);
        if (!match.matches() || !personalityIds.contains(match.group(1))) return Optional.empty();
        return Optional.of(new AutopilotSpec(key, match.group(1), null, Math.max(1, defaultThink)));
    }

    /** "autopilot-<id>" for every personality, sorted. */
    public static List<String> implicitKeys(Set<String> personalityIds) {
        return personalityIds.stream().map(id -> "autopilot-" + id).sorted().toList();
    }
}
