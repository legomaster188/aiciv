package com.aiciv.autopilot.personality;

import com.aiciv.autopilot.AutopilotEngine;
import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.ChatCue;
import com.aiciv.autopilot.Decision;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.PlanResult;
import com.aiciv.autopilot.goal.StepPlanner;
import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Chooses what an autopilot does next: survive first, then eat, then go home at night, then the goal its personality
 * wants most, and wander when nothing else applies. Pure: reads only the {@link Situation} and memory.
 */
public final class PersonalityEngine implements AutopilotEngine {
    /** Ticks a blocked goal is left alone. */
    static final int BLOCK_TICKS = 1200;
    private static final int HOSTILE_RANGE = 8;
    private static final int HOME_RADIUS = 6;
    private static final long SLEEP_GAP_TICKS = 12000;
    private static final String[] COMPASS = {"N", "E", "S", "W"};
    private static final Map<String, String> OPPOSITE = Map.of("N", "S", "NE", "SW", "E", "W", "SE", "NW",
            "S", "N", "SW", "NE", "W", "E", "NW", "SE");

    private final Supplier<List<Goal>> goals;
    private final StepPlanner planner;

    public PersonalityEngine(Supplier<List<Goal>> goals, StepPlanner planner) {
        this.goals = goals;
        this.planner = planner;
    }

    @Override
    public Tuning tuning(Personality p) {
        return Behaviour.tuning(p.traits());
    }

    @Override
    public Decision decide(Situation s, AutopilotMemory mem, Personality p, String trigger, long tick) {
        Tuning t = tuning(p);
        List<Goal> all = goals.get();

        Optional<Sighting> hostile = s.nearestHostile(HOSTILE_RANGE);
        // retreat, the night routine and wandering bypass the planner, so they honour its cooldowns themselves:
        // without that a failing goTo/explore/sleep would be retried every half second
        if (hostile.isPresent() && shouldRetreat(s, p, t) && !mem.coolingDown("survive", "retreat", tick)) {
            return retreat(s, hostile.get());
        }

        if (s.food() <= 6 && s.foodItems() == 0) {
            for (Goal g : all) {
                if (g.id().equals("get_food") && planner.next(g, s, mem, t) instanceof PlanResult.Next next) {
                    return Decision.of(next, String.format(Locale.ROOT, "goal %s (hungry): step %s: %s", g.id(),
                            next.step().id(), next.move().why()));
                }
            }
        }

        if (s.night() && t.homeAtNight() && hostile.isEmpty()) {
            Optional<int[]> home = s.home();
            if (home.isPresent()) return night(s, mem, t, home.get(), tick);
        }

        List<ChatCue> cues = new ArrayList<>();
        for (GoalChooser.Scored scored : GoalChooser.rank(all, p, s, mem, t, tick)) {
            Goal g = scored.goal();
            PlanResult r = planner.next(g, s, mem, t);
            if (r instanceof PlanResult.Next next) {
                Decision d = Decision.of(next, String.format(Locale.ROOT, "goal %s (%.2f): step %s: %s", g.id(),
                        scored.utility(), next.step().id(), next.move().why()));
                return withCues(d, cues);
            }
            if (r instanceof PlanResult.Blocked b) {
                mem.block(g.id(), tick + BLOCK_TICKS);
                cues.add(helpCue(g, b));
            }
        }

        if (s.hasAction("explore") && !mem.coolingDown("explore", "wander", tick)) {
            String direction = COMPASS[(int) ((tick / 2400) % 4)];
            return withCues(new Decision(List.of(Calls.of("explore", "direction", direction, "distance", t.exploreDistance())),
                    "nothing better to do: wander " + direction, "explore", "wander", null, List.of(), false), cues);
        }
        return withCues(Decision.waiting("nothing to do"), cues);
    }

    // ---------------------------------------------------------------- survival

    private static boolean shouldRetreat(Situation s, Personality p, Tuning t) {
        if (s.health() > t.retreatHealth()) return false;
        boolean standsAndFights = p.traits().brave() >= 0.5 && s.toolTier("sword") >= 2 && s.health() > 6;
        return !standsAndFights;
    }

    private static Decision retreat(Situation s, Sighting mob) {
        String thought = "survive: retreat from " + mob.label() + " (health " + s.health() + ")";
        Optional<int[]> home = s.home();
        if (home.isPresent() && s.distanceTo(home.get()) > 8) {
            return new Decision(List.of(goTo(home.get())), thought, "survive", "retreat", null, List.of(), false);
        }
        String away = OPPOSITE.getOrDefault(mob.direction() == null ? "" : mob.direction().toUpperCase(Locale.ROOT), "S");
        return new Decision(List.of(Calls.of("explore", "direction", away, "distance", 24)), thought, "survive", "retreat",
                null, List.of(), false);
    }

    /**
     * Home, then bed, then staying put. A step that failed is not tried again while it cools down; a failed sleep counts
     * as this night's sleep (dusk, rain, monsters: it would fail again), and with no bed carried or in sight there is
     * nothing to sleep in.
     */
    private static Decision night(Situation s, AutopilotMemory mem, Tuning t, int[] home, long tick) {
        if (s.distanceTo(home) > HOME_RADIUS) {
            if (mem.finishesWithoutDone("night", "go_home") >= 3) { // the walk keeps ending short of home
                mem.resetFinishes("night", "go_home");
                mem.stall("night", "go_home", tick, t.maxRetries());
            }
            if (!mem.coolingDown("night", "go_home", tick)) {
                return new Decision(List.of(goTo(home)), "night: go home", "night", "go_home", null, List.of(), false);
            }
            return new Decision(List.of(), "night: can't get home, staying put", "night", "stay", null, List.of(), false);
        }
        mem.resetFinishes("night", "go_home");
        if (s.hasAction("sleep") && s.bedKnown() && !s.doneWithin("night/sleep", SLEEP_GAP_TICKS)
                && !mem.failedWithin("night", "sleep", tick, SLEEP_GAP_TICKS) && !mem.coolingDown("night", "sleep", tick)) {
            return new Decision(List.of(Calls.of("sleep")), "night: sleep at home", "night", "sleep", null, List.of(), false);
        }
        return new Decision(List.of(), "night: staying home", "night", "stay", null, List.of(), false);
    }

    private static com.aiciv.llm.ToolCall goTo(int[] p) {
        return Calls.of("goTo", "x", p[0], "y", p[1], "z", p[2]);
    }

    // ---------------------------------------------------------------- cues

    private static ChatCue helpCue(Goal g, PlanResult.Blocked b) {
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("goal", g.id());
        vars.put("step", b.step().id());
        if (isItem(b.step().id())) vars.put("item", b.step().id());
        return new ChatCue("ask_help", vars, false);
    }

    static boolean isItem(String id) {
        try {
            Identifier key = Identifier.tryParse(id);
            return key != null && BuiltInRegistries.ITEM.containsKey(key);
        } catch (Throwable e) { // registries not bootstrapped (unit tests)
            return false;
        }
    }

    private static Decision withCues(Decision d, List<ChatCue> extra) {
        if (extra.isEmpty()) return d;
        List<ChatCue> all = new ArrayList<>(d.cues());
        all.addAll(extra);
        return new Decision(d.calls(), d.thought(), d.goal(), d.step(), d.flag(), all, d.keepCurrent());
    }
}
