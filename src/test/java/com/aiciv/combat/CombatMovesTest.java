package com.aiciv.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.combat.CombatMove.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class CombatMovesTest {
    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static String error(String moves) {
        return assertThrows(IllegalArgumentException.class, () -> CombatMoves.parse(json(moves))).getMessage();
    }

    @Test
    void objectAndStringFormsParseToEqualLists() {
        List<CombatMove> objects = CombatMoves.parse(json("""
                [{"move":"strafe","dir":"left","ticks":10},{"move":"attack_once","target":"zombie"},
                 {"move":"bow","target":"skeleton","charge":20},{"move":"hotbar","slot":2},
                 {"move":"shield","ticks":30},{"move":"back_off","ticks":8},{"move":"jump_crit","target":"zombie"}]"""));
        List<CombatMove> strings = CombatMoves.parse(json("""
                ["strafe left 10","attack_once zombie","bow skeleton 20","hotbar 2","shield 30","back_off 8","jump_crit zombie"]"""));
        assertEquals(objects, strings);
        assertEquals(List.of(new Strafe(true, 10), new AttackOnce("zombie"), new Bow("skeleton", 20), new Hotbar(2),
                new Shield(30), new BackOff(8), new JumpCrit("zombie")), strings);
    }

    @Test
    void numbersAreClamped() {
        assertEquals(new Strafe(true, 20), CombatMoves.parse(json("[\"strafe left 50\"]")).get(0));
        assertEquals(new Strafe(false, 1), CombatMoves.parse(json("[\"strafe right 0\"]")).get(0));
        assertEquals(new BackOff(20), CombatMoves.parse(json("[{\"move\":\"back_off\",\"ticks\":99}]")).get(0));
        assertEquals(new Shield(40), CombatMoves.parse(json("[\"shield 100\"]")).get(0));
        assertEquals(new Bow("zombie", 30), CombatMoves.parse(json("[\"bow zombie 99\"]")).get(0));
        assertEquals(new Hotbar(9), CombatMoves.parse(json("[\"hotbar 12\"]")).get(0));
        assertEquals(new Hotbar(1), CombatMoves.parse(json("[\"hotbar 0\"]")).get(0));
    }

    @Test
    void bowWithoutChargeDefaultsToFull() {
        assertEquals(new Bow("zombie", 20), CombatMoves.parse(json("[\"bow zombie\"]")).get(0));
    }

    @Test
    void aTargetCarriesOver() {
        assertEquals(List.of(new AttackOnce("zombie"), new JumpCrit("zombie")), CombatMoves.parse(json("[\"attack_once zombie\",\"jump_crit\"]")));
        assertEquals(List.of(new AttackOnce("cave spider"), new BackOff(8), new AttackOnce("cave spider")),
                CombatMoves.parse(json("[\"attack_once cave spider\",\"back_off 8\",\"attack_once\"]")));
    }

    @Test
    void movesNeedAList() {
        String message = "combat needs \"moves\": a list like [\"attack_once zombie\", \"back_off 8\"]";
        assertEquals(message, error("[]"));
        assertEquals(message, error("null"));
        assertEquals(message, error("\"attack_once zombie\""));
        assertThrows(IllegalArgumentException.class, () -> CombatMoves.parse(null));
    }

    @Test
    void unknownMoveListsTheValidOnes() {
        String message = "unknown move 'dance' (use attack_once, strafe, back_off, jump_crit, shield, bow, hotbar)";
        assertEquals(message, error("[\"dance zombie\"]"));
        assertEquals(message, error("[{\"move\":\"dance\"}]"));
    }

    @Test
    void atMostTwelveMoves() {
        JsonArray twelve = new JsonArray();
        for (int i = 0; i < 12; i++) twelve.add("back_off 2");
        assertEquals(12, CombatMoves.parse(twelve).size());
        twelve.add("back_off 2");
        assertEquals("at most 12 moves", error(twelve.toString()));
    }

    @Test
    void theFirstTargetedMoveMustNameOne() {
        assertEquals("attack_once needs a target", error("[\"attack_once\"]"));
        assertEquals("jump_crit needs a target", error("[\"back_off 8\",\"jump_crit\"]"));
        assertEquals("bow needs a target", error("[{\"move\":\"bow\"}]"));
    }

    @Test
    void maxTicksPerMove() {
        assertEquals(60, new AttackOnce("z").maxTicks());
        assertEquals(30, new JumpCrit("z").maxTicks());
        assertEquals(10, new Strafe(true, 10).maxTicks());
        assertEquals(8, new BackOff(8).maxTicks());
        assertEquals(30, new Shield(30).maxTicks());
        assertEquals(40, new Bow("z", 20).maxTicks());
        assertEquals(1, new Hotbar(3).maxTicks());
    }
}
