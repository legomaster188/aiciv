package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Situation;
import java.util.function.Predicate;

/** The done-checks of the endgame steps, read from the situation alone. */
public enum EndgameCondition {
    PORTAL_READY(s -> !s.inOverworld() || s.sees("nether_portal", 16) || (rods(s) && pearls(s))),
    IN_NETHER_OR_PAST(s -> s.inNether() || s.inEnd() || (rods(s) && pearls(s))),
    FORTRESS_FOUND(s -> s.sees("nether_bricks", 24) || s.hasTag("fortress_known") || rods(s) || s.inEnd()),
    RODS_READY(EndgameCondition::rods),
    PEARLS_READY(EndgameCondition::pearls),
    EYES_READY(s -> eyes(s) || s.inEnd()),
    IN_OVERWORLD_OR_END(s -> s.inOverworld() || s.inEnd()),
    STRONGHOLD_FOUND(s -> s.sees("end_portal_frame", 24) || s.hasTag("portal_room_known") || s.inEnd()),
    IN_END(Situation::inEnd),
    DRAGON_DEAD(s -> s.flag("dragon_dead")),
    // candidate conditions ("when" in endgame.json); the tags come from Milestone 4's snapshot sections
    OBSIDIAN_10(s -> s.count("obsidian") >= 10),
    NO_OBSIDIAN(s -> s.count("obsidian") == 0),
    STRONGHOLD_KNOWN(s -> s.hasTag("stronghold_known"));

    private final Predicate<Situation> check;

    EndgameCondition(Predicate<Situation> check) {
        this.check = check;
    }

    public boolean test(Situation s) {
        return check.test(s);
    }

    private static boolean eyes(Situation s) {
        return s.count("ender_eye") >= 12;
    }

    private static boolean rods(Situation s) {
        return s.count("blaze_rod") * 2 + s.count("blaze_powder") >= 12 || eyes(s);
    }

    private static boolean pearls(Situation s) {
        return s.count("ender_pearl") >= 12 || eyes(s);
    }
}
