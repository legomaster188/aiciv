package com.aiciv.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which inventory items a deposit moves: everything, everything but the gear a bot works with, or a named list. Pure. */
public final class DepositRule {
    public static final String USAGE = "deposit \"items\" must be all, all_except_tools, or a list like \"cobblestone x20, oak_log\"";

    private enum Kind { ALL, ALL_EXCEPT_TOOLS, LIST }

    public static final DepositRule ALL = new DepositRule(Kind.ALL, Map.of());
    public static final DepositRule ALL_EXCEPT_TOOLS = new DepositRule(Kind.ALL_EXCEPT_TOOLS, Map.of());

    private static final Pattern ENTRY = Pattern.compile("([a-z0-9_:]+?)(?:\\s*x\\s*(\\d+)|\\s+(\\d+))?");

    private final Kind kind;
    private final Map<String, Integer> listed;

    private DepositRule(Kind kind, Map<String, Integer> listed) {
        this.kind = kind;
        this.listed = listed;
    }

    public static DepositRule parse(String items) {
        if (items == null) throw new IllegalArgumentException(USAGE);
        String text = items.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        switch (text) {
            case "all", "everything":
                return ALL;
            case "all_except_tools", "all-except-tools", "all except tools", "everything but tools", "everything except tools":
                return ALL_EXCEPT_TOOLS;
            default:
        }
        Map<String, Integer> listed = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            String entry = part.trim();
            Matcher m = ENTRY.matcher(entry);
            if (entry.isEmpty() || !m.matches()) throw new IllegalArgumentException(USAGE);
            String id = m.group(1).replace("minecraft:", "");
            String number = m.group(2) != null ? m.group(2) : m.group(3);
            int limit = Integer.MAX_VALUE;
            if (number != null) {
                try {
                    limit = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(USAGE);
                }
                if (limit < 1) throw new IllegalArgumentException(USAGE);
            }
            if (id.isEmpty()) throw new IllegalArgumentException(USAGE);
            listed.put(id, limit);
        }
        if (listed.isEmpty()) throw new IllegalArgumentException(USAGE);
        return new DepositRule(Kind.LIST, Collections.unmodifiableMap(listed));
    }

    /** True when the item stays in the inventory. {@code keepByNature} is true for tools, weapons, armor and food. */
    public boolean keeps(String itemId, boolean keepByNature) {
        return switch (kind) {
            case ALL -> false;
            case ALL_EXCEPT_TOOLS -> keepByNature;
            case LIST -> !listed.containsKey(itemId);
        };
    }

    /** How many of the item to move; {@link Integer#MAX_VALUE} means all of it, 0 an unlisted item of a list rule. */
    public int limit(String itemId) {
        return kind == Kind.LIST ? listed.getOrDefault(itemId, 0) : Integer.MAX_VALUE;
    }

    public boolean isList() {
        return kind == Kind.LIST;
    }

    /** The named items of a list rule in the order given (name to limit); empty for the other rules. */
    public Map<String, Integer> listed() {
        return listed;
    }
}
