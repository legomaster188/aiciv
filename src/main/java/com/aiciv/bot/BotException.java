package com.aiciv.bot;

/** A command could not be carried out; the message is shown to the player who ran it. */
public final class BotException extends Exception {
    public BotException(String message) {
        super(message);
    }
}
