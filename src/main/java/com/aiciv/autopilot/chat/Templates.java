package com.aiciv.autopilot.chat;

import com.aiciv.actions.SayAction;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills {@code {name}} placeholders in a chat line. */
public final class Templates {
    /** Every placeholder a shipped or user line may use. */
    public static final Set<String> ALLOWED = Set.of("me", "other", "otherModel", "speaker", "item", "count", "goal", "step",
            "attacker", "dead", "message", "personality");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");

    private Templates() {}

    /** The line with every placeholder filled; empty when one has no non-blank value or nothing is left to say. */
    public static Optional<String> render(String template, Map<String, String> vars) {
        if (template == null) return Optional.empty();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null || value.isBlank()) return Optional.empty();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        String line = SayAction.sanitize(out.toString());
        return line.isEmpty() ? Optional.empty() : Optional.of(line);
    }

    /** The placeholder names the template uses, in order of first use. */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
