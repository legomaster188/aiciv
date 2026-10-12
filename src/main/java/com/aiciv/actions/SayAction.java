package com.aiciv.actions;

public final class SayAction implements Action {
    public static final int MAX_LENGTH = 256;
    private final String text;

    public SayAction(String text) {
        this.text = sanitize(text);
    }

    /** Removes formatting codes and control characters, collapses spaces, caps the length. */
    public static String sanitize(String raw) {
        String s = raw.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }

    public String text() {
        return text;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (text.isEmpty()) return ActionStatus.failed("nothing to say");
        ctx.say(text);
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return "say \"" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "\"";
    }

    @Override
    public int timeoutTicks() {
        return 20;
    }
}
