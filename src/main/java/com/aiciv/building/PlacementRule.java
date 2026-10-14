package com.aiciv.building;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;

/**
 * How vanilla decides the orientation of a block at placement, and so where a player has to stand and look to get the
 * orientation they want. Pure: no world.
 */
public final class PlacementRule {
    public enum Kind { HORIZONTAL_OPPOSITE, NEAREST_OPPOSITE, NEAREST_SAME, CLICKED_FACE_OPPOSITE, CLICKED_FACE, ATTACHED, AXIS, NONE }

    /** What the placer must satisfy; null means "any". {@code supportSide} is the direction from the cell to the block clicked. */
    public record Need(Direction horizontalLook, Direction nearestLook, Direction supportSide) {}

    private PlacementRule() {}

    public static Kind of(String blockId) {
        return switch (blockId) {
            case "repeater", "comparator" -> Kind.HORIZONTAL_OPPOSITE;
            case "piston", "sticky_piston", "dispenser", "dropper" -> Kind.NEAREST_OPPOSITE;
            case "observer" -> Kind.NEAREST_SAME;
            case "hopper" -> Kind.CLICKED_FACE_OPPOSITE;
            case "redstone_wall_torch", "wall_torch", "ladder" -> Kind.CLICKED_FACE;
            case "lever" -> Kind.ATTACHED;
            case "basalt", "quartz_pillar", "bone_block", "hay_block" -> Kind.AXIS;
            default -> {
                if (blockId.endsWith("_button")) yield Kind.ATTACHED;
                if (blockId.endsWith("_log") || blockId.endsWith("_wood") || blockId.endsWith("_stem") || blockId.endsWith("_hyphae")) yield Kind.AXIS;
                yield Kind.NONE;
            }
        };
    }

    public static Need need(Kind kind, Map<String, String> props) {
        Direction facing = direction(props.get("facing"));
        List<Direction> supports = supports(kind, props);
        Direction support = supports.isEmpty() ? null : supports.get(0);
        return switch (kind) {
            case HORIZONTAL_OPPOSITE -> new Need(facing == null ? null : facing.getOpposite(), null, Direction.DOWN);
            case NEAREST_OPPOSITE -> new Need(null, facing == null ? null : facing.getOpposite(), null);
            case NEAREST_SAME -> new Need(null, facing, null);
            case ATTACHED -> new Need(facing != null && (support == Direction.DOWN || support == Direction.UP) ? facing : null, null, support);
            case CLICKED_FACE_OPPOSITE, CLICKED_FACE, AXIS -> new Need(null, null, support);
            case NONE -> new Need(null, null, null);
        };
    }

    /** The sides of the cell whose neighbour may be clicked, best first (a log can lean on either end of its axis). */
    public static List<Direction> supports(Kind kind, Map<String, String> props) {
        Direction facing = direction(props.get("facing"));
        switch (kind) {
            case HORIZONTAL_OPPOSITE:
                return List.of(Direction.DOWN);
            case CLICKED_FACE_OPPOSITE:
                if (facing == null) return List.of();
                return List.of(facing.getAxis() == Direction.Axis.Y ? Direction.DOWN : facing);
            case CLICKED_FACE:
                return facing == null ? List.of() : List.of(facing.getOpposite());
            case ATTACHED:
                String face = props.getOrDefault("face", "wall");
                if (face.equals("floor")) return List.of(Direction.DOWN);
                if (face.equals("ceiling")) return List.of(Direction.UP);
                return facing == null ? List.of() : List.of(facing.getOpposite());
            case AXIS:
                String axis = props.getOrDefault("axis", "y");
                return switch (axis) {
                    case "x" -> List.of(Direction.EAST, Direction.WEST);
                    case "z" -> List.of(Direction.NORTH, Direction.SOUTH);
                    default -> List.of(Direction.DOWN, Direction.UP);
                };
            default:
                return List.of();
        }
    }

    static Direction direction(String name) {
        if (name == null) return null;
        return switch (name) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            default -> null;
        };
    }
}
