package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.GoalIds;
import com.aiciv.autopilot.Sit;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.llm.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreGoalsTest {
    private static Goal goal(String id) {
        return CoreGoals.list().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static PlanResult.Next next(String goalId, Situation s) {
        return assertInstanceOf(PlanResult.Next.class, new StepPlanner().next(goal(goalId), s, new AutopilotMemory(), Tuning.DEFAULT));
    }

    private static ToolCall call(PlanResult.Next n, int i) {
        return n.move().calls().get(i);
    }

    @Test
    void theSevenCoreGoalsComeInOrderWithTheirSummaries() {
        assertEquals(List.of("get_wood_tools", "get_stone_tools", "get_iron_tools", "get_iron_armor", "get_food", "mine_diamonds", "beat_game"),
                CoreGoals.list().stream().map(Goal::id).toList());
        assertTrue(GoalIds.ALL.containsAll(CoreGoals.list().stream().map(Goal::id).toList()));
        assertEquals("get iron tools", goal("get_iron_tools").summary());
        assertEquals("beat the game", goal("beat_game").summary());
        assertEquals(List.of(), GoalLibrary.all().stream().map(Goal::id).filter(i -> !GoalIds.ALL.contains(i)).toList());
    }

    @Test
    void emptyInventoryStartsWithLogs() {
        PlanResult.Next n = next("get_iron_tools", sit().build());
        assertEquals("wooden_pickaxe", n.step().id());
        assertEquals("mine", call(n, 0).name());
        assertEquals("log", call(n, 0).args().get("block").getAsString());
        assertEquals(3, call(n, 0).args().get("count").getAsInt());
    }

    @Test
    void enoughLogsCraftThePickaxe() {
        PlanResult.Next n = next("get_wood_tools", sit().inv("oak_log", 3).build());
        assertEquals("craft", call(n, 0).name());
        assertEquals("wooden_pickaxe", call(n, 0).args().get("item").getAsString());
    }

    @Test
    void ironPickaxeStepSmeltsWhenReady() {
        Situation s = sit().inv("stone_pickaxe", 1).inv("wooden_pickaxe", 1).inv("raw_iron", 3).inv("coal", 1).inv("oak_log", 2)
                .block("furnace", 4).build();
        PlanResult.Next n = next("get_iron_tools", s);
        assertEquals("iron_pickaxe", n.step().id());
        assertEquals("smelt", call(n, 0).name());
        assertEquals(3, call(n, 0).args().get("count").getAsInt());
    }

    @Test
    void ironPickaxeHeldMovesOnToTheSword() {
        PlanResult.Next n = next("get_iron_tools", sit().inv("iron_pickaxe", 1).build());
        assertEquals("iron_sword", n.step().id());
    }

    @Test
    void toolsOfAHigherTierSatisfyLowerSteps() {
        assertEquals("stone_sword", next("get_stone_tools", sit().inv("iron_pickaxe", 1).build()).step().id());
        assertInstanceOf(PlanResult.Achieved.class, new StepPlanner().next(goal("get_wood_tools"),
                sit().inv("stone_pickaxe", 1).inv("iron_sword", 1).build(), new AutopilotMemory(), Tuning.DEFAULT));
    }

    @Test
    void foodHuntsWhenTwoAnimalsAreNear() {
        PlanResult.Next n = next("get_food", sit().mob("cow", 3, 10, "N").build());
        assertEquals("attack", call(n, 0).name());
        assertEquals("cow", call(n, 0).args().get("target").getAsString());
        assertEquals("collect", call(n, 1).name());
        assertEquals("beef", call(n, 1).args().get("item").getAsString());
    }

    @Test
    void foodNeverHuntsTheLastAnimal() {
        PlanResult.Next n = next("get_food", sit().mob("cow", 1, 10, "N").build());
        assertEquals(Move.Kind.GATHER, n.move().kind());
        assertEquals("explore", call(n, 0).name());
        assertEquals(Tuning.DEFAULT.exploreDistance(), call(n, 0).args().get("distance").getAsInt());
    }

    @Test
    void foodSmeltsRawMeatWithFuel() {
        PlanResult.Next n = next("get_food", sit().inv("beef", 4).inv("coal", 1).build());
        assertEquals("smelt", call(n, 0).name());
        assertEquals("beef", call(n, 0).args().get("item").getAsString());
        assertEquals(4, call(n, 0).args().get("count").getAsInt());
        // without fuel the raw meat is not smelted
        assertNotEquals("smelt", call(next("get_food", sit().inv("beef", 4).build()), 0).name());
    }

    @Test
    void foodHarvestsRipeCrops() {
        PlanResult.Next n = next("get_food", sit().tag("crop_ripe").build());
        assertEquals("harvest", call(n, 0).name());
        assertEquals(32, call(n, 0).args().get("count").getAsInt());
    }

    @Test
    void foodFishesBakesAndStopsAtEight() {
        PlanResult.Next fish = next("get_food", sit().inv("fishing_rod", 1).block("water", 8).build());
        assertEquals("fish", call(fish, 0).name());
        PlanResult.Next bread = next("get_food", sit().inv("wheat", 7).build());
        assertEquals("craft", call(bread, 0).name());
        assertEquals(2, call(bread, 0).args().get("count").getAsInt());
        assertInstanceOf(PlanResult.Achieved.class, new StepPlanner().next(goal("get_food"), sit().inv("bread", 8).build(),
                new AutopilotMemory(), Tuning.DEFAULT));
    }

    @Test
    void foodNeedValues() {
        Goal food = goal("get_food");
        assertEquals(0, food.need(sit().inv("bread", 8).build()));
        assertEquals(1.0, food.need(sit().food(6).build()), 1e-9);
        assertEquals(1.0, food.need(sit().food(14).build()), 1e-9);
        assertEquals(0.5, food.need(sit().food(20).build()), 1e-9);
        assertEquals(0.5, food.need(sit().inv("bread", 4).food(14).build()), 1e-9);
        assertEquals(0.25, food.need(sit().inv("bread", 4).food(20).build()), 1e-9);
        assertEquals(0.875, food.need(sit().inv("bread", 1).food(6).build()), 1e-9);
    }

    @Test
    void ironArmorPutsOnWhatIsReadyAndGathersTheRest() {
        Situation ready = sit().inv("stone_pickaxe", 1).inv("iron_ingot", 8).wear("iron_helmet").build();
        PlanResult.Next n = next("get_iron_armor", ready);
        assertEquals("iron_chestplate", n.step().id());
        assertEquals("gear_up", call(n, 0).name());
        assertEquals("iron", call(n, 0).args().get("material").getAsString());
        assertEquals("false", call(n, 0).args().get("shield").getAsString());

        Situation short_ = sit().inv("stone_pickaxe", 1).inv("iron_ingot", 3).build();
        PlanResult.Next m = next("get_iron_armor", short_);
        assertEquals("iron_chestplate", m.step().id());
        assertEquals("mine", call(m, 0).name());
        assertEquals("iron_ore", call(m, 0).args().get("block").getAsString());
        assertEquals(5, call(m, 0).args().get("count").getAsInt());
    }

    @Test
    void ironArmorNeedFallsAsPiecesAreWorn() {
        Goal armor = goal("get_iron_armor");
        assertEquals(1.0, armor.need(sit().build()));
        assertEquals(0.5, armor.need(sit().wear("iron_helmet").wear("iron_boots").build()));
        assertEquals(0.0, armor.need(sit().wear("iron_helmet").wear("iron_boots").wear("iron_chestplate").wear("iron_leggings").build()));
    }

    @Test
    void diamondsWaitForAnIronPickaxe() {
        PlanResult.Next n = next("mine_diamonds", sit().inv("iron_pickaxe", 1).build());
        assertEquals("diamonds", n.step().id());
        assertEquals("goTo", call(n, 0).name());
        assertInstanceOf(PlanResult.Achieved.class, new StepPlanner().next(goal("mine_diamonds"),
                sit().inv("iron_pickaxe", 1).inv("diamond", 3).build(), new AutopilotMemory(), Tuning.DEFAULT));
    }

    @Test
    void sitCanBeBuiltWithoutActions() {
        Sit empty = sit().actions();
        PlanResult r = new StepPlanner().next(goal("get_wood_tools"), empty.build(), new AutopilotMemory(), Tuning.DEFAULT);
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, r);
        assertEquals("needs craft, mine (not available yet)", b.reason());
    }
}
