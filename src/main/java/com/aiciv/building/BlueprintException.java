package com.aiciv.building;

/** A blueprint a model sent cannot be used; the message is the reason shown to the model. */
public final class BlueprintException extends Exception {
    public BlueprintException(String message) {
        super(message);
    }
}
