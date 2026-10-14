package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.building.PlacementRule.Kind;
import com.aiciv.building.PlacementRule.Need;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PlacementRuleTest {
    @Test
    void kindsByBlock() {
        assertEquals(Kind.HORIZONTAL_OPPOSITE, PlacementRule.of("repeater"));
        assertEquals(Kind.HORIZONTAL_OPPOSITE, PlacementRule.of("comparator"));
        assertEquals(Kind.NEAREST_OPPOSITE, PlacementRule.of("sticky_piston"));
        assertEquals(Kind.NEAREST_OPPOSITE, PlacementRule.of("dropper"));
        assertEquals(Kind.NEAREST_SAME, PlacementRule.of("observer"));
        assertEquals(Kind.CLICKED_FACE_OPPOSITE, PlacementRule.of("hopper"));
        assertEquals(Kind.CLICKED_FACE, PlacementRule.of("redstone_wall_torch"));
        assertEquals(Kind.ATTACHED, PlacementRule.of("lever"));
        assertEquals(Kind.ATTACHED, PlacementRule.of("oak_button"));
        assertEquals(Kind.AXIS, PlacementRule.of("oak_log"));
        assertEquals(Kind.AXIS, PlacementRule.of("hay_block"));
        assertEquals(Kind.NONE, PlacementRule.of("stone"));
    }

    @Test
    void repeaterLooksTheOppositeWayFromItsFacing() {
        assertEquals(new Need(Direction.SOUTH, null, Direction.DOWN), PlacementRule.need(Kind.HORIZONTAL_OPPOSITE, Map.of("facing", "north")));
    }

    @Test
    void pistonNeedsTheNearestLookOppositeItsFacing() {
        assertEquals(new Need(null, Direction.DOWN, null), PlacementRule.need(Kind.NEAREST_OPPOSITE, Map.of("facing", "up")));
    }

    @Test
    void observerNeedsTheNearestLookAlongItsFacing() {
        assertEquals(new Need(null, Direction.EAST, null), PlacementRule.need(Kind.NEAREST_SAME, Map.of("facing", "east")));
    }

    @Test
    void hopperClicksTheNeighbourOnItsFacingSide() {
        assertEquals(new Need(null, null, Direction.NORTH), PlacementRule.need(Kind.CLICKED_FACE_OPPOSITE, Map.of("facing", "north")));
        assertEquals(new Need(null, null, Direction.DOWN), PlacementRule.need(Kind.CLICKED_FACE_OPPOSITE, Map.of("facing", "down")));
    }

    @Test
    void wallTorchLeansOnTheOppositeSide() {
        assertEquals(new Need(null, null, Direction.WEST), PlacementRule.need(Kind.CLICKED_FACE, Map.of("facing", "east")));
    }

    @Test
    void leverOnAWallLeansOnTheOppositeSide() {
        assertEquals(new Need(null, null, Direction.WEST), PlacementRule.need(Kind.ATTACHED, Map.of("face", "wall", "facing", "east")));
    }

    @Test
    void leverOnTheFloorOrCeilingLooksAlongItsFacing() {
        assertEquals(new Need(Direction.EAST, null, Direction.DOWN), PlacementRule.need(Kind.ATTACHED, Map.of("face", "floor", "facing", "east")));
        assertEquals(new Need(Direction.EAST, null, Direction.UP), PlacementRule.need(Kind.ATTACHED, Map.of("face", "ceiling", "facing", "east")));
    }

    @Test
    void logsLeanOnEitherEndOfTheirAxis() {
        assertEquals(List.of(Direction.EAST, Direction.WEST), PlacementRule.supports(Kind.AXIS, Map.of("axis", "x")));
        assertEquals(List.of(Direction.NORTH, Direction.SOUTH), PlacementRule.supports(Kind.AXIS, Map.of("axis", "z")));
        assertEquals(List.of(Direction.DOWN, Direction.UP), PlacementRule.supports(Kind.AXIS, Map.of("axis", "y")));
        assertEquals(Direction.EAST, PlacementRule.need(Kind.AXIS, Map.of("axis", "x")).supportSide());
    }
}
