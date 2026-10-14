package com.aiciv.stronghold;

import java.util.Set;

/** The blocks a stronghold is made of, by id without namespace. Doors are left out: villages and mansions have them. */
public final class StrongholdBlocks {
    private static final Set<String> IDS = Set.of("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
            "chiseled_stone_bricks", "infested_stone_bricks", "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
            "infested_chiseled_stone_bricks", "iron_bars", "end_portal_frame");

    private StrongholdBlocks() {}

    public static boolean is(String id) {
        return IDS.contains(id);
    }
}
