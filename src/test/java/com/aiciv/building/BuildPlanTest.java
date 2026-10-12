package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BuildPlanTest {
    private static Blueprint bp(String layers, String legend) throws BlueprintException {
        return Blueprint.parse(JsonParser.parseString(layers), JsonParser.parseString(legend));
    }

    private static Set<Pos> cells(BuildPlan plan) {
        return plan.places().stream().map(BuildPlan.Cell::pos).collect(Collectors.toSet());
    }

    @Test
    void lShapeFollowsTheFacing() throws Exception {
        Blueprint l = bp("[[\"cc\",\"c \"]]", "{\"c\":\"stone\"}");
        Pos o = new Pos(0, 0, 0);
        assertEquals(Set.of(new Pos(0, 0, 0), new Pos(-1, 0, 0), new Pos(0, 0, 1)), cells(BuildPlan.of(l, o, Facing.SOUTH)));
        assertEquals(Set.of(new Pos(0, 0, 0), new Pos(0, 0, 1), new Pos(1, 0, 0)), cells(BuildPlan.of(l, o, Facing.EAST)));
        assertEquals(Set.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(0, 0, -1)), cells(BuildPlan.of(l, o, Facing.NORTH)));
        assertEquals(Set.of(new Pos(0, 0, 0), new Pos(0, 0, -1), new Pos(-1, 0, 0)), cells(BuildPlan.of(l, o, Facing.WEST)));
    }

    @Test
    void placesAreOrderedBottomUpThenRowThenColumn() throws Exception {
        Blueprint b = bp("[[\"cc\",\"cc\"],[\"cc\",\"cc\"]]", "{\"c\":\"stone\"}");
        List<BuildPlan.Cell> places = BuildPlan.of(b, new Pos(0, 5, 0), Facing.SOUTH).places();
        assertEquals(8, places.size());
        for (int i = 1; i < places.size(); i++) assertTrue(places.get(i).pos().y() >= places.get(i - 1).pos().y());
        assertEquals(new Pos(0, 5, 0), places.get(0).pos());
        assertEquals(new Pos(-1, 5, 0), places.get(1).pos());
        assertEquals(new Pos(0, 5, 1), places.get(2).pos());
    }

    @Test
    void yOffsetShiftsTheWholeBlueprint() throws Exception {
        Blueprint b = bp("[[\"c\"]]", "{\"c\":\"stone\"}").withYOffset(-1);
        assertEquals(new Pos(0, 9, 0), BuildPlan.of(b, new Pos(0, 10, 0), Facing.SOUTH).places().get(0).pos());
    }

    @Test
    void airCellsAndBoundingBox() throws Exception {
        Blueprint b = bp("[[\"c.c\"]]", "{\"c\":\"stone\"}");
        BuildPlan plan = BuildPlan.of(b, new Pos(10, 64, 10), Facing.EAST);
        assertEquals(List.of(new Pos(10, 64, 11)), plan.mustBeAir());
        assertEquals(new Pos(10, 64, 10), plan.min());
        assertEquals(new Pos(10, 64, 12), plan.max());
    }

    @Test
    void materialsSubtractCellsAlreadyCorrect() throws Exception {
        Blueprint b = bp("[[\"cc\",\"cd\"]]", "{\"c\":\"cobblestone\",\"d\":\"oak_planks\"}");
        BuildPlan plan = BuildPlan.of(b, new Pos(0, 0, 0), Facing.SOUTH);
        assertEquals(Map.of("cobblestone", 3, "oak_planks", 1), plan.materials(p -> "air"));
        Map<String, Integer> some = plan.materials(p -> p.equals(new Pos(0, 0, 0)) ? "cobblestone" : "air");
        assertEquals(2, some.get("cobblestone"));
    }

    @Test
    void aDoorCountsOnceAndItsUpperHalfIsNotAPlace() throws Exception {
        Blueprint door = bp("[[\"d\"],[\"d\"]]", "{\"d\":\"oak_door\"}");
        BuildPlan plan = BuildPlan.of(door, new Pos(0, 0, 0), Facing.SOUTH);
        assertEquals(1, plan.places().size());
        assertEquals(Map.of("oak_door", 1), plan.materials(p -> "air"));
    }

    @Test
    void anUnsupportedCellFloatsButTheSameCellOnAPillarDoesNot() throws Exception {
        Blueprint floating = bp("[[\" \"],[\" \"],[\"c\"]]", "{\"c\":\"stone\"}");
        BuildPlan plan = BuildPlan.of(floating, new Pos(0, 0, 0), Facing.SOUTH);
        assertEquals(1, plan.floating(p -> false).size());
        Blueprint pillar = bp("[[\"c\"],[\"c\"],[\"c\"]]", "{\"c\":\"stone\"}");
        BuildPlan p2 = BuildPlan.of(pillar, new Pos(0, 0, 0), Facing.SOUTH);
        assertTrue(p2.floating(p -> p.equals(new Pos(0, -1, 0))).isEmpty());
        assertEquals(3, p2.floating(p -> false).size());
    }

    @Test
    void connectivityRunsThroughPlannedNeighbours() throws Exception {
        Blueprint wall = bp("[[\"ccc\"]]", "{\"c\":\"stone\"}");
        BuildPlan plan = BuildPlan.of(wall, new Pos(0, 0, 0), Facing.SOUTH);
        // Only the end cell touches the world; the rest hang off it.
        assertTrue(plan.floating(p -> p.equals(new Pos(1, 0, 0))).isEmpty());
        Set<Pos> none = new HashSet<>();
        assertEquals(3, plan.floating(none::contains).size());
    }

    @Test
    void doorProblemFiresForAirAboveADoor() throws Exception {
        Blueprint bad = bp("[[\"cdc\"],[\"c.c\"]]", "{\"c\":\"stone\",\"d\":\"oak_door\"}");
        assertEquals("door at layer 1 row 1 col 2 needs ' ' above it", BuildPlan.doorProblem(bad));
        Blueprint ok = bp("[[\"cdc\"],[\"c c\"]]", "{\"c\":\"stone\",\"d\":\"oak_door\"}");
        assertNull(BuildPlan.doorProblem(ok));
        Blueprint twoHigh = bp("[[\"d\"],[\"d\"]]", "{\"d\":\"oak_door\"}");
        assertNull(BuildPlan.doorProblem(twoHigh));
    }

    @Test
    void facingParsesLettersAndWords() {
        assertEquals(Facing.NORTH, Facing.parse("n"));
        assertEquals(Facing.EAST, Facing.parse("East"));
        assertEquals(Facing.SOUTH, Facing.parse(" S "));
        assertEquals(Facing.WEST, Facing.parse("west"));
        assertNull(Facing.parse("up"));
        assertNull(Facing.parse(null));
        assertEquals(1, Facing.SOUTH.forwardZ());
        assertEquals(-1, Facing.SOUTH.rightX());
    }
}
