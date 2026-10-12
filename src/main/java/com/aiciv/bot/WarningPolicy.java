package com.aiciv.bot;

/** Decides when a waiting bot is worth an ops chat line; shorter waits only reach the server log. */
final class WarningPolicy {
    static final long CHAT_AFTER_MILLIS = 60_000;

    private WarningPolicy() {}

    static boolean shouldChat(boolean primaryAuthDisabled, long waitingMillis) {
        return primaryAuthDisabled || waitingMillis >= CHAT_AFTER_MILLIS;
    }
}
