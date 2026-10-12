package com.aiciv.nav;

/** Which tools-free-of-charge abilities the path may rely on: digging through blocks, placing blocks. */
public record SearchOptions(boolean canBreak, boolean canPlace) {
    public static final SearchOptions WALK_ONLY = new SearchOptions(false, false);
}
