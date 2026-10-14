package com.aiciv.autopilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads requests ("give me 3 bread", "come here", "follow me") out of chat. The requester is filled in by the caller. */
public final class Requests {
    public static final String GIVE = "GIVE";
    public static final String COME = "COME";
    public static final String FOLLOW = "FOLLOW";

    private static final Pattern GIVE_ME = Pattern.compile(
            "^(?:can you |could you |please )?(?:give|throw|toss|pass)(?: me)?(?: (\\d+))? (?:a |an |some )?([a-z_ ]+?)(?: please)?$");
    private static final Pattern WANT = Pattern.compile(
            "^(?:can i have|i need|anyone got|anyone have|got any|do you have)(?: some| any)?(?: (\\d+))? (?:a |an |some )?([a-z_ ]+?)$");
    private static final Pattern COME_HERE = Pattern.compile("^(?:come|come here|over here|help|help me|to me)$");
    private static final Pattern FOLLOW_ME = Pattern.compile("^follow me$");

    private Requests() {}

    /** The request in {@code text}, with {@code from} empty; none when it is not one addressed to this bot. */
    public static Optional<AutopilotMemory.Request> parse(String text, String botName, boolean mentioned, long tick) {
        if (text == null) return Optional.empty();
        String t = text.toLowerCase(Locale.ROOT);
        if (botName != null && !botName.isBlank()) {
            // a whole word only: a bot called "Al" must not cut "all" or "salmon" apart
            t = t.replaceAll("(?<![a-z0-9_])" + Pattern.quote(botName.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])", " ");
        }
        t = t.replaceAll("[@,.!?]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return Optional.empty();

        Matcher m = GIVE_ME.matcher(t);
        if (m.matches()) return mentioned ? give(m, tick) : Optional.empty();
        m = WANT.matcher(t);
        if (m.matches()) {
            boolean open = t.startsWith("anyone") || t.startsWith("got any");
            return mentioned || open ? give(m, tick) : Optional.empty();
        }
        if (COME_HERE.matcher(t).matches()) return mentioned ? Optional.of(new AutopilotMemory.Request(COME, "", "", 1, tick)) : Optional.empty();
        if (FOLLOW_ME.matcher(t).matches()) return mentioned ? Optional.of(new AutopilotMemory.Request(FOLLOW, "", "", 1, tick)) : Optional.empty();
        return Optional.empty();
    }

    /** Words that say how much or which one, not what: "a spare apple" is an apple. */
    private static final Set<String> FILLERS = Set.of("spare", "some", "any", "a", "an", "more", "extra", "few");

    /** None when nothing is left of the item once the filler words are gone. */
    private static Optional<AutopilotMemory.Request> give(Matcher m, long tick) {
        List<String> words = new ArrayList<>();
        for (String w : m.group(2).trim().split(" +")) if (!FILLERS.contains(w)) words.add(w);
        if (words.isEmpty()) return Optional.empty();
        int count = 1;
        if (m.group(1) != null) {
            try {
                count = Math.max(1, Math.min(16, Integer.parseInt(m.group(1))));
            } catch (NumberFormatException e) {
                count = 16; // more digits than an int
            }
        }
        return Optional.of(new AutopilotMemory.Request(GIVE, "", String.join("_", words), count, tick));
    }
}
