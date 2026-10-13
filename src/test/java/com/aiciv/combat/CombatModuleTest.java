package com.aiciv.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatModuleTest {
    private final CombatModule module = new CombatModule();

    private static SnapshotData holding(String item, String equipment) {
        SnapshotData s = Samples.snapshot(List.of(), List.of());
        return new SnapshotData(s.name(), s.x(), s.y(), s.z(), s.health(), s.maxHealth(), s.food(), s.day(), s.timeOfDay(),
                s.biome(), item, equipment, s.inventory(), s.blocks(), s.players(), s.mobs(), s.remembered(),
                s.currentAction(), s.lastResult(), s.xpLevel(), s.sections());
    }

    private static OptionMenu.MenuContext zombies(Map<String, Integer> inventory) {
        return Samples.context(inventory, List.of("zombie"));
    }

    private static List<String> ids(List<MenuOption> options) {
        return options.stream().map(MenuOption::id).toList();
    }

    @Test
    void combatWithoutMovesIsInvalid() {
        ActionFactory.Result r = ActionFactory.create(ToolCall.of("combat", new JsonObject()));
        ActionFactory.Result.Invalid invalid = assertInstanceOf(ActionFactory.Result.Invalid.class, r);
        assertTrue(invalid.reason().contains("\"moves\""), invalid.reason());
    }

    @Test
    void badMovesAreReportedToTheModel() {
        JsonObject args = new JsonObject();
        JsonArray moves = new JsonArray();
        moves.add("dance");
        args.add("moves", moves);
        ActionFactory.Result.Invalid invalid = assertInstanceOf(ActionFactory.Result.Invalid.class, ActionFactory.create(ToolCall.of("combat", args)));
        assertTrue(invalid.reason().contains("unknown move 'dance'"), invalid.reason());
    }

    @Test
    void goodMovesMakeAnAction() {
        JsonObject args = new JsonObject();
        JsonArray moves = new JsonArray();
        moves.add("attack_once zombie");
        moves.add("back_off 8");
        args.add("moves", moves);
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, ActionFactory.create(ToolCall.of("combat", args)));
        assertEquals("combat 2 moves vs zombie", ok.action().describe());
    }

    @Test
    void kiteAppearsWithASwordAndNotWithout() {
        assertTrue(ids(module.menuOptions(holding("iron_sword", "none"), zombies(Map.of("iron_sword", 1)))).contains("kite_zombie"));
        assertTrue(ids(module.menuOptions(holding("stone_axe", "none"), zombies(Map.of()))).contains("kite_zombie"));
        assertFalse(ids(module.menuOptions(holding("nothing", "none"), zombies(Map.of()))).contains("kite_zombie"));
        assertFalse(ids(module.menuOptions(holding("iron_sword", "none"), Samples.context(Map.of()))).contains("kite_zombie"));
    }

    @Test
    void kiteCallsCombatWithTheExactMoves() {
        MenuOption kite = module.menuOptions(holding("iron_sword", "none"), zombies(Map.of())).stream()
                .filter(o -> o.id().equals("kite_zombie")).findFirst().orElseThrow();
        assertEquals(1, kite.calls().size());
        assertEquals("combat", kite.calls().get(0).name());
        assertEquals("[\"attack_once zombie\",\"back_off 8\",\"attack_once zombie\",\"back_off 8\",\"attack_once zombie\"]",
                kite.calls().get(0).args().get("moves").toString());
    }

    @Test
    void shootNeedsABowAndArrowsAndBlockNeedsAShield() {
        assertTrue(ids(module.menuOptions(holding("bow", "none"), zombies(Map.of("arrow", 5)))).contains("shoot_zombie"));
        assertFalse(ids(module.menuOptions(holding("bow", "none"), zombies(Map.of()))).contains("shoot_zombie"));
        assertFalse(ids(module.menuOptions(holding("iron_sword", "none"), zombies(Map.of("arrow", 5)))).contains("shoot_zombie"));
        assertTrue(ids(module.menuOptions(holding("shield", "none"), zombies(Map.of()))).contains("block_zombie"));
        assertTrue(ids(module.menuOptions(holding("iron_sword", "shield"), zombies(Map.of()))).contains("block_zombie"));
        assertFalse(ids(module.menuOptions(holding("iron_sword", "none"), zombies(Map.of()))).contains("block_zombie"));
    }

    @Test
    void atMostTwoMobTypesAreOffered() {
        var ctx = Samples.context(Map.of(), List.of("zombie", "spider", "creeper"));
        List<String> ids = ids(module.menuOptions(holding("iron_sword", "none"), ctx));
        assertTrue(ids.contains("kite_zombie") && ids.contains("kite_spider"));
        assertFalse(ids.contains("kite_creeper"));
    }

    @Test
    void theSampleOffersAnOption() {
        assertFalse(module.menuOptions(module.sampleSnapshot(), module.sampleContext()).isEmpty());
        for (MenuOption o : module.menuOptions(module.sampleSnapshot(), module.sampleContext())) {
            assertTrue(OptionMenu.ID.matcher(o.id()).matches(), o.id());
        }
    }
}
