package com.aiciv.autopilot.chat;

import com.aiciv.actions.SayAction;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.personality.Personality;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Prompt building and reply cleaning for the optional LLM chat line writer. */
public final class LlmChat {
    /** Categories worth a model call; the rest are always templates. */
    public static final Set<String> CATEGORIES = Set.of("greet", "brag", "mention", "banter", "died", "other_died");
    static final int ASK_LENGTH = 100;
    static final int KEEP_LENGTH = 120;

    private LlmChat() {}

    public static String system(String me, Personality p) {
        return "You are " + me + ", a player in a Minecraft survival world. Personality: " + p.name() + ": " + p.description().strip().replaceAll("\\.+$", "")
                + ". Write exactly one short in-character chat message (at most " + ASK_LENGTH
                + " characters). No quotes, no emojis, no actions in asterisks.";
    }

    /** {@code vars} are the cue's values (plus {@code text}, what was said to the bot); {@code s} may be null. */
    public static String user(String category, Map<String, String> vars, Situation s, List<String> recentChat, String template) {
        StringBuilder b = new StringBuilder();
        b.append("What just happened: ").append(eventText(category, vars)).append(".\n");
        b.append("You: health ").append(s == null ? 20 : s.health()).append("/20, food ").append(s == null ? 20 : s.food())
                .append("/20, ").append(s != null && s.night() ? "night" : "day").append(", doing: ")
                .append(s == null || s.currentAction() == null || s.currentAction().isBlank() ? "nothing" : s.currentAction())
                .append(".\n");
        if (recentChat.isEmpty()) {
            b.append("Recent chat: none\n");
        } else {
            b.append("Recent chat:\n");
            for (String line : recentChat) b.append("- ").append(line).append('\n');
        }
        b.append("A line you might say: \"").append(template).append("\"\n");
        b.append("Reply with the chat message only.");
        return b.toString();
    }

    static String eventText(String category, Map<String, String> v) {
        return switch (category) {
            case "greet" -> v.containsKey("other") ? v.get("other") + " joined the game" : "you just arrived";
            case "brag" -> "you just got " + v.get("item");
            case "mention" -> v.get("speaker") + " spoke to you: \"" + v.getOrDefault("text", "") + "\"";
            case "banter" -> v.get("other") + " (who runs on " + v.get("otherModel") + ") is nearby";
            case "died" -> "you died: " + v.get("message");
            case "other_died" -> v.get("dead") + " died: " + v.get("message");
            default -> category;
        };
    }

    /** The first non-blank line of the raw reply made fit for chat, or empty. */
    public static Optional<String> clean(String raw, String me) {
        if (raw == null) return Optional.empty();
        String line = null;
        for (String l : raw.split("\\R")) {
            if (!l.isBlank()) {
                line = l.trim();
                break;
            }
        }
        if (line == null) return Optional.empty();
        line = stripWrapping(line);
        String quoted = Pattern.quote(me);
        line = line.replaceFirst("(?i)^(?:" + quoted + "\\s*:|<" + quoted + ">\\s*:?)\\s*", "");
        line = stripWrapping(line);
        line = line.replaceFirst("^/+", "");
        line = SayAction.sanitize(line);
        if (line.length() > KEEP_LENGTH) {
            int cut = line.charAt(KEEP_LENGTH) == ' ' ? KEEP_LENGTH : line.lastIndexOf(' ', KEEP_LENGTH);
            line = line.substring(0, cut > 0 ? cut : KEEP_LENGTH).trim();
        }
        return line.isEmpty() ? Optional.empty() : Optional.of(line);
    }

    private static String stripWrapping(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isWrap(s.charAt(start))) start++;
        while (end > start && isWrap(s.charAt(end - 1))) end--;
        return s.substring(start, end).trim();
    }

    private static boolean isWrap(char c) {
        return c == '"' || c == '\'' || c == '*';
    }
}
