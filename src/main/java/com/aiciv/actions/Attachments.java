package com.aiciv.actions;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Per-bot memory that modules keep between actions (e.g. remembered chests), one instance per type. Server thread. */
public final class Attachments {
    private final Map<Class<?>, Object> values = new HashMap<>();

    public <T> T get(Class<T> type, Supplier<T> create) {
        Object v = values.get(type);
        if (v == null) {
            v = create.get();
            values.put(type, v);
        }
        return type.cast(v);
    }

    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(values.get(type)));
    }
}
