package com.aiciv.building;

import com.aiciv.world.BlockNames;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A legend value: a block id with optional block states, {@code repeater[facing=north,delay=2]}. Pure. */
public record BlockSpec(String id, Map<String, String> props) {
    public static BlockSpec parse(String raw) throws BlueprintException {
        String text = raw == null ? "" : raw.trim();
        int open = text.indexOf('[');
        if (open < 0) return new BlockSpec(BlockNames.normalise(text), Map.of());
        if (!text.endsWith("]")) {
            if (text.indexOf(']') < 0) throw new BlueprintException("legend value \"" + raw + "\" has an unclosed [");
            throw new BlueprintException("legend value \"" + raw + "\" has text after ]");
        }
        String id = BlockNames.normalise(text.substring(0, open));
        String inside = text.substring(open + 1, text.length() - 1);
        Map<String, String> props = new TreeMap<>();
        for (String part : inside.split(",")) {
            if (part.isBlank()) continue;
            int eq = part.indexOf('=');
            String key = (eq < 0 ? part : part.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) throw new BlueprintException("legend value \"" + raw + "\" has a property without a name");
            if (value.isEmpty()) throw new BlueprintException("property \"" + key + "\" needs a value");
            props.put(key, value);
        }
        return new BlockSpec(id, Map.copyOf(props));
    }

    /** Canonical {@code id[k=v,...]} with sorted keys; just the id when there are no properties. */
    public String text() {
        if (props.isEmpty()) return id;
        StringBuilder out = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            if (!first) out.append(',');
            out.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return out.append(']').toString();
    }
}
