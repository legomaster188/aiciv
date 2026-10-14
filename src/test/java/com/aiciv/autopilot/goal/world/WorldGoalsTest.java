package com.aiciv.autopilot.goal.world;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.GoalIds;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.PlanResult;
import com.aiciv.autopilot.goal.StepPlanner;
import com.aiciv.autopilot.goal.WorldGoals;
import com.aiciv.llm.ToolCall;
import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldGoalsTest {
    /** A small Situation builder for these goals: an empty overworld bot called Ada at 0 64 0. */
    private static final class B {
        long tick = 1000;
        int freeSlots = 30;
        String holding;
        Tuning tuning = Tuning.DEFAULT;
        final Map<String, Integer> inv = new LinkedHashMap<>();
        final List<Sighting> mobs = new ArrayList<>();
        final List<Sighting> players = new ArrayList<>();
        final List<String> hostiles = new ArrayList<>();
        final List<String> structures = new ArrayList<>();
        final Set<String> tags = new HashSet<>();
        final Set<String> flags = new HashSet<>();
        final List<AutopilotMemory.Request> requests = new ArrayList<>();

        B inv(String id, int n) { inv.put(id, n); return this; }
        B hostile(String label, int distance) { mobs.add(new Sighting(label, 1, distance, "N")); hostiles.add(label); return this; }
        B mob(String label, int count, int distance) { mobs.add(new Sighting(label, count, distance, "N")); return this; }
        B human(String name, int distance) { players.add(new Sighting(name + " [human]", 1, distance, "E")); return this; }
        B tag(String t) { tags.add(t); return this; }
        B flag(String f) { flags.add(f); return this; }
        B home() { structures.add(Situation.homeName("Ada") + " by Ada at 10 64 10 size 5"); return this; }
        B request(String kind, String from, String item, int count) {
            requests.add(new AutopilotMemory.Request(kind, from, item, count, 900));
            return this;
        }

        Situation build() {
            return new Situation("Ada", "overworld", 0, 64, 0, 20, 20, false, tick, new LinkedHashMap<>(inv), Set.of(), holding,
                    freeSlots, List.of(), List.copyOf(players), List.copyOf(mobs), List.copyOf(hostiles), List.of(),
                    new HashSet<>(tags), List.copyOf(structures), new HashSet<>(ActionFactory.knownKeys()), null, false,
                    new HashSet<>(flags), new HashMap<>(), List.copyOf(requests), tuning, null);
        }
    }

    private static Goal goal(String id) {
        return WorldGoals.list().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static PlanResult.Next next(String id, Situation s) {
        return assertInstanceOf(PlanResult.Next.class, new StepPlanner().next(goal(id), s, new AutopilotMemory(), s.tuning()));
    }

    private static ToolCall call(PlanResult.Next n, int i) {
        return n.move().calls().get(i);
    }

    private static String arg(ToolCall c, String key) {
        return c.args().get(key).getAsString();
    }

    private static Tuning tuning(int keepCount, int depositWhenFree) {
        Tuning d = Tuning.DEFAULT;
        return new Tuning(d.maxRetries(), d.exploreDistance(), d.retreatHealth(), d.hysteresis(), d.homeAtNight(), keepCount,
                d.helpsWhenAsked(), d.chatCooldownFactor(), depositWhenFree);
    }

    @Test
    void theSevenWorldGoalsComeInOrder() {
        assertEquals(List.of("defend", "social", "build_base", "store_items", "farm", "trade_emeralds", "explore"),
                WorldGoals.list().stream().map(Goal::id).toList());
        assertTrue(GoalIds.ALL.containsAll(WorldGoals.list().stream().map(Goal::id).toList()));
    }

    // ---------------------------------------------------------------- defend

    @Test
    void defendFightsTheNearestHostile() {
        Situation s = new B().hostile("zombie", 5).hostile("skeleton", 12).build();
        PlanResult.Next n = next("defend", s);
        assertEquals("fight", n.step().id());
        assertEquals("attack", call(n, 0).name());
        assertEquals("zombie", arg(call(n, 0), "target"));
        assertEquals(1.0, goal("defend").need(s));
    }

    @Test
    void defendEquipsTheBestWeaponFirst() {
        Situation s = new B().hostile("zombie", 5).inv("wooden_sword", 1).inv("iron_sword", 1).inv("stone_axe", 1).build();
        PlanResult.Next n = next("defend", s);
        assertEquals("arm", n.step().id());
        assertEquals("equip", call(n, 0).name());
        assertEquals("iron_sword", arg(call(n, 0), "item"));
        B armed = new B().hostile("zombie", 5).inv("iron_sword", 1);
        armed.holding = "iron_sword";
        assertEquals("fight", next("defend", armed.build()).step().id());
    }

    @Test
    void defendGuardsAHumanWhenNothingElseIsNear() {
        Situation s = new B().human("Steve", 20).human("Alex", 28).build();
        PlanResult.Next n = next("defend", s);
        assertEquals("guard", n.step().id());
        assertEquals("follow", call(n, 0).name());
        assertEquals("Steve", arg(call(n, 0), "name"));
        assertEquals(60, call(n, 0).args().get("seconds").getAsInt());
        assertEquals(0.4, goal("defend").need(s));
    }

    @Test
    void defendWantsNothingWithNoOneAround() {
        assertEquals(0.0, goal("defend").need(new B().human("Far", 40).build()));
    }

    // ---------------------------------------------------------------- social

    @Test
    void socialGivesTheSurplus() {
        Situation s = new B().inv("bread", 20).request("GIVE", "Steve", "bread", 3).build();
        PlanResult.Next n = next("social", s);
        assertEquals("request_give", n.step().id());
        assertEquals("give", call(n, 0).name());
        assertEquals("Steve", arg(call(n, 0), "name"));
        assertEquals("bread", arg(call(n, 0), "item"));
        assertEquals(3, call(n, 0).args().get("count").getAsInt());
        assertEquals("give", n.move().cues().get(0).category());
        assertEquals("3", n.move().cues().get(0).vars().get("count"));
        assertEquals(1.0, goal("social").need(s));
    }

    @Test
    void socialRefusesWhenNothingIsSpare() {
        PlanResult.Next n = next("social", new B().inv("bread", 10).request("GIVE", "Steve", "bread", 3).build());
        assertEquals(List.of(), n.move().calls());
        assertEquals("refuse", n.move().cues().get(0).category());
        assertEquals("Steve", n.move().cues().get(0).vars().get("speaker"));
    }

    @Test
    void aGenerousBotGivesWhatItHas() {
        B b = new B().inv("bread", 2).request("GIVE", "Steve", "bread", 3);
        b.tuning = tuning(0, 0);
        PlanResult.Next n = next("social", b.build());
        assertEquals(2, call(n, 0).args().get("count").getAsInt());
    }

    @Test
    void socialMatchesLooseItemNames() {
        B b = new B().inv("cooked_beef", 30).request("GIVE", "Steve", "beef", 2);
        PlanResult.Next n = next("social", b.build());
        assertEquals("cooked_beef", arg(call(n, 0), "item"));
    }

    @Test
    void socialComesAndFollows() {
        PlanResult.Next come = next("social", new B().request("COME", "Steve", "", 1).build());
        assertEquals("request_come", come.step().id());
        assertEquals("goToPlayer", call(come, 0).name());
        assertEquals("Steve", arg(call(come, 0), "name"));
        assertEquals("coming", come.move().cues().get(0).category());
        PlanResult.Next follow = next("social", new B().request("FOLLOW", "Steve", "", 1).build());
        assertEquals("request_follow", follow.step().id());
        assertEquals("follow", call(follow, 0).name());
        assertEquals(120, call(follow, 0).args().get("seconds").getAsInt());
    }

    @Test
    void socialAnswersTheOldestFirstAndIdlesWithoutRequests() {
        Situation s = new B().request("COME", "Steve", "", 1).request("GIVE", "Alex", "bread", 1).build();
        assertEquals("request_come", next("social", s).step().id());
        Situation none = new B().build();
        assertEquals(0.0, goal("social").need(none));
        assertInstanceOf(PlanResult.Achieved.class, new StepPlanner().next(goal("social"), none, new AutopilotMemory(), Tuning.DEFAULT));
    }

    // ---------------------------------------------------------------- build base

    @Test
    void buildBaseBuildsAHutOnceItHasStone() {
        Situation s = new B().inv("stone_pickaxe", 1).inv("cobblestone", 80).build();
        PlanResult.Next n = next("build_base", s);
        assertEquals("hut", n.step().id());
        ToolCall c = call(n, 0);
        assertEquals("build_template", c.name());
        assertEquals("hut", arg(c, "template"));
        assertEquals(5, c.args().get("size").getAsInt());
        assertEquals("cobblestone", arg(c, "material"));
        assertEquals("ada_home", arg(c, "name"));
        assertEquals("home_built", n.step().flag());
        assertEquals(1.0, goal("build_base").need(s));
    }

    @Test
    void buildBaseMinesCobblestoneFirst() {
        PlanResult.Next n = next("build_base", new B().inv("stone_pickaxe", 1).inv("cobblestone", 10).build());
        assertEquals("materials", n.step().id());
        assertEquals("mine", call(n, 0).name());
        assertEquals(61, call(n, 0).args().get("count").getAsInt());
        assertEquals(64, call(next("build_base", new B().inv("stone_pickaxe", 1).build()), 0).args().get("count").getAsInt());
    }

    @Test
    void buildBasePlacesAChestInsideTheHome() {
        Situation withChest = new B().inv("stone_pickaxe", 1).home().inv("chest", 1).build();
        PlanResult.Next n = next("build_base", withChest);
        assertEquals("chest", n.step().id());
        assertEquals(List.of("goTo", "place_chest"), n.move().calls().stream().map(ToolCall::name).toList());
        assertEquals("home_chest", n.step().flag());

        PlanResult.Next craft = next("build_base", new B().inv("stone_pickaxe", 1).home().inv("oak_log", 4).build());
        assertEquals("chest_item", craft.step().id());
        assertEquals("craft", call(craft, 0).name());
        assertEquals("chest", arg(call(craft, 0), "item"));
    }

    @Test
    void buildBaseIsDoneWithHomeAndChest() {
        Situation s = new B().inv("stone_pickaxe", 1).home().flag("home_chest").build();
        assertEquals(0.0, goal("build_base").need(s));
        PlanResult r = new StepPlanner().next(goal("build_base"), s, new AutopilotMemory(), Tuning.DEFAULT);
        // only the optional bed is left, and it needs wool and wood the bot does not have
        assertInstanceOf(PlanResult.Next.class, r);
        assertEquals("bed", ((PlanResult.Next) r).step().id());
    }

    // ---------------------------------------------------------------- store items

    @Test
    void storeItemsWantsToEmptyAFullPack() {
        B b = new B().home().flag("home_chest");
        b.freeSlots = 3;
        b.tuning = tuning(16, 4);
        Situation s = b.build();
        assertEquals(1.0, goal("store_items").need(s));
        PlanResult.Next n = next("store_items", s);
        assertEquals(List.of("goTo", "deposit"), n.move().calls().stream().map(ToolCall::name).toList());
        assertEquals("all_except_tools", arg(call(n, 1), "items"));
        b.freeSlots = 10;
        assertEquals(0.0, goal("store_items").need(b.build()));
        assertEquals(0.0, goal("store_items").need(new B().build()));
    }

    // ---------------------------------------------------------------- farm

    @Test
    void farmHarvestsWhatIsRipe() {
        Situation s = new B().tag("crop_ripe").inv("wooden_hoe", 1).inv("wheat_seeds", 2).flag("planted").build();
        PlanResult.Next n = next("farm", s);
        assertEquals("harvest", n.step().id());
        assertEquals("harvest", call(n, 0).name());
        assertEquals(32, call(n, 0).args().get("count").getAsInt());
        assertEquals(1.0, goal("farm").need(s));
    }

    @Test
    void farmTillsAsManyBlocksAsItHasSeeds() {
        Situation s = new B().inv("wooden_hoe", 1).inv("wheat_seeds", 4).build();
        PlanResult.Next n = next("farm", s);
        assertEquals("till", n.step().id());
        assertEquals(4, call(n, 0).args().get("count").getAsInt());
        assertEquals(0.8, goal("farm").need(s));
    }

    @Test
    void farmPlantsOnFreeFarmland() {
        Situation s = new B().inv("wooden_hoe", 1).inv("wheat_seeds", 4).tag("farmland_free").build();
        PlanResult.Next n = next("farm", s);
        assertEquals("plant", n.step().id());
        assertEquals("wheat_seeds", arg(call(n, 0), "seed"));
        assertEquals(4, call(n, 0).args().get("count").getAsInt());
        assertEquals("planted", n.step().flag());
    }

    @Test
    void farmStartsWithAHoeAndSeeds() {
        assertEquals("wooden_hoe", next("farm", new B().build()).step().id());
        PlanResult.Next seeds = next("farm", new B().inv("wooden_hoe", 1).build());
        assertEquals("seeds", seeds.step().id());
        assertEquals("short_grass", arg(call(seeds, 0), "block"));
    }

    @Test
    void farmNeedFallsOnceThingsAreGrowing() {
        Situation growing = new B().flag("planted").build();
        assertEquals(0.1, goal("farm").need(growing));
        assertEquals(0.7, goal("farm").need(new B().flag("planted").tag("farmland_free").inv("wheat_seeds", 2).build()));
    }

    @Test
    void farmBakesBreadAndBreedsWithTheHarvest() {
        B b = new B().inv("wooden_hoe", 1).inv("wheat_seeds", 1).flag("planted").inv("wheat", 7).mob("cow", 3, 8);
        PlanResult.Next bread = next("farm", b.build());
        assertEquals("bread", bread.step().id());
        assertEquals(2, call(bread, 0).args().get("count").getAsInt());
        B few = new B().inv("wooden_hoe", 1).inv("wheat_seeds", 1).flag("planted").inv("wheat", 2).mob("cow", 3, 8);
        PlanResult.Next breed = next("farm", few.build());
        assertEquals("breed", breed.step().id());
        assertEquals("cow", arg(call(breed, 0), "animal"));
    }

    // ---------------------------------------------------------------- trade

    @Test
    void tradeSellsWhatTheVillagerBuys() {
        Situation s = new B().tag("villager:farmer").tag("offer:farmer:1:emerald:wheat:20").inv("wheat", 45).build();
        PlanResult.Next n = next("trade_emeralds", s);
        assertEquals("sell", n.step().id());
        ToolCall c = call(n, 0);
        assertEquals("trade", c.name());
        assertEquals("farmer", arg(c, "villager"));
        assertEquals(1, c.args().get("offer").getAsInt());
        assertEquals(2, c.args().get("times").getAsInt());
        assertEquals(1.0, goal("trade_emeralds").need(s));
    }

    @Test
    void tradeListsOffersFirstAndWantsLittleWithNothingToSell() {
        Situation s = new B().tag("villager:farmer").build();
        PlanResult.Next n = next("trade_emeralds", s);
        assertEquals("list", n.step().id());
        assertEquals("trade_list", call(n, 0).name());
        assertEquals(0.5, goal("trade_emeralds").need(s));
        assertEquals(0.0, goal("trade_emeralds").need(new B().build()));
    }

    @Test
    void tradeCapsTimesAndBuysFoodWithEmeralds() {
        Situation many = new B().tag("villager:farmer").tag("offer:farmer:1:emerald:wheat:20:5:64:5").inv("wheat", 200).build();
        assertEquals(4, call(next("trade_emeralds", many), 0).args().get("times").getAsInt());

        Situation buy = new B().tag("villager:farmer").tag("offer:farmer:2:bread:emerald:1:3:5:64:5").inv("emerald", 20).build();
        PlanResult.Next n = next("trade_emeralds", buy);
        assertEquals("buy_food", n.step().id());
        assertEquals(2, call(n, 0).args().get("offer").getAsInt());
        assertEquals(1, call(n, 0).args().get("times").getAsInt());
    }

    // ---------------------------------------------------------------- explore

    @Test
    void exploreKeepsItsDirectionForAWhile() {
        String first = arg(call(next("explore", new B().build()), 0), "direction");
        B same = new B();
        same.tick = 1000 + 3000;
        B later = new B();
        later.tick = 1000 + 6000 * 3;
        assertEquals(first, arg(call(next("explore", same.build()), 0), "direction"));
        assertNotEquals(first, arg(call(next("explore", later.build()), 0), "direction"));
        assertEquals(48, call(next("explore", new B().build()), 0).args().get("distance").getAsInt());
        assertEquals(0.6, goal("explore").need(new B().build()));
    }
}
