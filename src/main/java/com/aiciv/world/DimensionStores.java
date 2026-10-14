package com.aiciv.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One store per dimension for a bot's memories (blocks, chests, villagers): positions only mean something in their own
 * dimension, but a bot that comes back should still know them. Keeps the {@link #MAX_DIMENSIONS} most recently entered.
 */
public final class DimensionStores<T> {
    public static final int MAX_DIMENSIONS = 3;

    private final Supplier<T> create;
    /** Access order: the least recently entered dimension comes first. */
    private final LinkedHashMap<String, T> stores = new LinkedHashMap<>(4, 0.75f, true);
    private String dimension;
    private T active;

    public DimensionStores(Supplier<T> create) {
        this.create = create;
        this.active = create.get();
    }

    /** Switches to {@code key}'s store. What was remembered before the first call belongs to the first dimension entered. */
    public T enter(String key) {
        if (key.equals(dimension)) return active;
        if (dimension == null) {
            stores.put(key, active);
        } else {
            active = stores.get(key);
            if (active == null) {
                active = create.get();
                stores.put(key, active);
            }
        }
        dimension = key;
        while (stores.size() > MAX_DIMENSIONS) {
            Map.Entry<String, T> eldest = stores.entrySet().iterator().next();
            stores.remove(eldest.getKey());
        }
        return active;
    }

    public T active() {
        return active;
    }
}
