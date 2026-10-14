package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import com.aiciv.perception.Sighting;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fight hostile mobs close by, and stay near a human player when there is nothing to fight. */
public final class Defend implements Goal {
    private static final int HOSTILE_RANGE = 16;
    private static final int HUMAN_RANGE = 32;
    private static final String HUMAN_TAG = " [human]";

    private static final Step ARM = Steps.of("arm", s -> bestWeapon(s).map(w -> w.equals(s.holding())).orElse(true),
            s -> Move.act("equip " + bestWeapon(s).orElse("a weapon"), Calls.of("equip", "item", bestWeapon(s).orElse(""))),
            Set.of("equip"), true, null);

    private static final Step FIGHT = Steps.of("fight", s -> s.nearestHostile(HOSTILE_RANGE).isEmpty(),
            s -> s.nearestHostile(HOSTILE_RANGE)
                    .map(m -> Move.act("fight the " + m.label(), Calls.of("attack", "target", m.label())))
                    .orElseGet(() -> Move.blocked("nothing to fight")),
            Set.of("attack"), false, null);

    private static final Step GUARD = Steps.of("guard",
            s -> nearestHuman(s).isEmpty() || s.nearestHostile(HOSTILE_RANGE).isPresent(),
            s -> nearestHuman(s)
                    .map(h -> Move.act("stay with " + h, Calls.of("follow", "name", h, "seconds", 60)))
                    .orElseGet(() -> Move.blocked("nobody to guard")),
            Set.of("follow"), true, null);

    private static final List<Step> STEPS = List.of(ARM, FIGHT, GUARD);

    @Override public String id() { return "defend"; }
    @Override public String summary() { return "defend"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        if (s.nearestHostile(HOSTILE_RANGE).isPresent()) return 1.0;
        return nearestHuman(s).isPresent() ? 0.4 : 0;
    }

    /** The best sword or axe carried or held (a sword beats an axe of the same material), or empty when there is none. */
    static Optional<String> bestWeapon(Situation s) {
        String best = null;
        int bestScore = 0;
        for (String id : s.inventory().keySet()) {
            int score = score(id);
            if (score > bestScore || (score == bestScore && score > 0 && id.compareTo(best) < 0)) {
                best = id;
                bestScore = score;
            }
        }
        if (s.holding() != null && score(s.holding()) > bestScore) best = s.holding();
        return Optional.ofNullable(best);
    }

    private static int score(String id) {
        boolean sword = id.endsWith("_sword");
        if (!sword && !id.endsWith("_axe")) return 0;
        String material = id.substring(0, id.length() - (sword ? "_sword".length() : "_axe".length()));
        return Math.max(1, Situation.toolTierOf(material)) * 2 + (sword ? 1 : 0);
    }

    /** The nearest human player within range, by name. */
    static Optional<String> nearestHuman(Situation s) {
        Sighting best = null;
        for (Sighting p : s.players()) {
            if (p.distance() > HUMAN_RANGE || !p.label().endsWith(HUMAN_TAG)) continue;
            if (best == null || p.distance() < best.distance()) best = p;
        }
        return Optional.ofNullable(best).map(p -> p.label().substring(0, p.label().length() - HUMAN_TAG.length()));
    }
}
