package com.aiciv.dragon;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DragonModuleTest {
    private final DragonModule module = new DragonModule();

    private static Action action(String tool, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return assertInstanceOf(ActionFactory.Result.Ok.class, ActionFactory.create(ToolCall.of(tool, args))).action();
    }

    @Test
    void everyToolMakesAnAction() {
        assertInstanceOf(FightDragonRoutine.class, action("fight_dragon", "{}"));
        assertInstanceOf(BreakCageAction.class, action("break_cage", "{}"));
        assertInstanceOf(LeaveEndRoutine.class, action("leave_end", "{}"));
        assertInstanceOf(ShootCrystalsAction.class, action("shoot_crystals", "{}"));
        assertInstanceOf(HitDragonAction.class, action("hit_dragon", "{}"));
    }

    @Test
    void countAndSecondsAreClampedWithDefaults() {
        assertEquals("shoot 10 end crystals", action("shoot_crystals", "{}").describe());
        assertEquals("shoot 10 end crystals", action("shoot_crystals", "{\"count\": 99}").describe());
        assertEquals("shoot 1 end crystal", action("shoot_crystals", "{\"count\": -4}").describe());
        assertEquals("shoot 3 end crystals", action("shoot_crystals", "{\"count\": \"3\"}").describe());
        assertEquals("hit the dragon for 30 seconds", action("hit_dragon", "{}").describe());
        assertEquals("hit the dragon for 5 seconds", action("hit_dragon", "{\"seconds\": 1}").describe());
        assertEquals("hit the dragon for 120 seconds", action("hit_dragon", "{\"seconds\": 9000}").describe());
        assertEquals(30 * 20 + 200, action("hit_dragon", "{}").timeoutTicks());
        assertEquals(36000, action("fight_dragon", "{}").timeoutTicks());
    }

    private static SnapshotData in(String[] tags, String... items) {
        return Samples.snapshot(List.of(), List.of(new SnapshotSection("Dragon", List.of(items), null, List.of(tags))));
    }

    private List<String> ids(SnapshotData snap, Map<String, Integer> inventory) {
        return module.menuOptions(snap, Samples.context(inventory)).stream().map(MenuOption::id).toList();
    }

    private static final Map<String, Integer> ARMED = Map.of("bow", 1, "arrow", 32);

    @Test
    void nothingIsOfferedOutsideTheEnd() {
        assertEquals(List.of(), ids(Samples.snapshot(List.of(), List.of()), ARMED));
    }

    @Test
    void fightDragonIsOfferedInTheEndUntilTheDragonIsDead() {
        assertEquals(List.of("fight_dragon"), ids(in(new String[] {"dragon_flying"}, "ender_dragon 200/200 hp, flying 40m NE"), Map.of()));
        assertFalse(ids(in(new String[] {"dragon_dead"}, "no dragon in sight"), Map.of()).contains("fight_dragon"));
    }

    @Test
    void shootCrystalsNeedsCrystalsABowAndArrows() {
        String[] left = {"dragon_flying", "crystals_left"};
        assertTrue(ids(in(left, "end crystals: 6 seen, 2 caged"), ARMED).contains("shoot_crystals"));
        assertFalse(ids(in(left, "x"), Map.of("bow", 1)).contains("shoot_crystals"));
        assertFalse(ids(in(left, "x"), Map.of("arrow", 32)).contains("shoot_crystals"));
        assertFalse(ids(in(new String[] {"dragon_flying"}, "x"), ARMED).contains("shoot_crystals"));
    }

    @Test
    void hitDragonOnlyWhilePerched() {
        assertTrue(ids(in(new String[] {"dragon_perched"}, "x"), Map.of()).contains("hit_dragon"));
        assertFalse(ids(in(new String[] {"dragon_flying"}, "x"), Map.of()).contains("hit_dragon"));
    }

    @Test
    void leaveEndNeedsAnOpenPortalAndADeadDragon() {
        assertEquals(List.of("leave_end"), ids(in(new String[] {"dragon_dead", "exit_portal_open"}, "exit portal open at 0 64 0"), Map.of()));
        assertFalse(ids(in(new String[] {"dragon_flying", "exit_portal_open"}, "x"), Map.of()).contains("leave_end"));
        assertFalse(ids(in(new String[] {"dragon_dead"}, "x"), Map.of()).contains("leave_end"));
    }

    @Test
    void theSampleOffersOptionsThatTheFactoryAccepts() {
        List<MenuOption> options = module.menuOptions(module.sampleSnapshot(), module.sampleContext());
        assertEquals(List.of("fight_dragon", "shoot_crystals"), options.stream().map(MenuOption::id).toList());
        for (MenuOption o : options) {
            for (ToolCall call : o.calls()) assertInstanceOf(ActionFactory.Result.Ok.class, ActionFactory.create(call));
        }
    }
}
