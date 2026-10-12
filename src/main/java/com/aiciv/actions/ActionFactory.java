package com.aiciv.actions;

import com.aiciv.llm.ToolCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import java.util.Set;

/** Validates a model's tool call and builds the action, or explains what was wrong. */
public final class ActionFactory {
    public sealed interface Result {
        record Ok(Action action) implements Result {}
        record Invalid(String reason) implements Result {}
    }

    static final String AVAILABLE = "say, goTo, goToPlayer, follow, explore, mine, collect, craft, smelt, place, eat, equip, attack, stop";
    static final int WORLD_LIMIT = 29_999_984;
    private static final Set<String> KNOWN = Set.of("say", "goto", "gotoplayer", "follow", "explore", "mine", "collect",
            "craft", "smelt", "place", "eat", "equip", "attack", "stop");

    private ActionFactory() {}

    /** The normalised names the factory accepts; the catalog test keeps this in step with {@link ToolCatalog}. */
    static Set<String> knownKeys() {
        return KNOWN;
    }

    public static Result create(ToolCall call) {
        String key = call.name() == null ? "" : call.name().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (!KNOWN.contains(key)) return invalid("unknown action '" + call.name() + "'. Available: " + AVAILABLE);
        if (call.argsError() != null) return invalid(call.argsError());
        JsonObject a = call.args();
        switch (key) {
            case "say": {
                String text = str(a, "text");
                if (text == null || SayAction.sanitize(text).isEmpty()) return invalid("say needs non-empty \"text\"");
                return new Result.Ok(new SayAction(text));
            }
            case "goto": {
                Integer x = integer(a, "x"), y = integer(a, "y"), z = integer(a, "z");
                if (x == null || y == null || z == null) return invalid("goTo needs whole numbers x, y and z");
                if (y < -64 || y > 320) return invalid("goTo y must be between -64 and 320");
                if (x < -WORLD_LIMIT || x > WORLD_LIMIT || z < -WORLD_LIMIT || z > WORLD_LIMIT) return invalid("goTo x and z must be inside the world border");
                return new Result.Ok(new GoToAction(x, y, z));
            }
            case "gotoplayer": {
                String name = str(a, "name");
                if (name == null || name.isBlank()) return invalid("goToPlayer needs a player \"name\"");
                return new Result.Ok(new GoToPlayerAction(name.trim()));
            }
            case "follow": {
                String name = str(a, "name");
                if (name == null || name.isBlank()) return invalid("follow needs a player \"name\"");
                Integer seconds = integer(a, "seconds");
                return new Result.Ok(new FollowAction(name.trim(), seconds == null ? 60 : seconds));
            }
            case "explore": {
                String direction = str(a, "direction");
                if (!ExploreAction.isDirection(direction)) return invalid("explore needs a \"direction\": N, NE, E, SE, S, SW, W or NW");
                Integer distance = integer(a, "distance");
                return new Result.Ok(new ExploreAction(direction, distance == null ? 64 : Math.max(1, Math.min(128, distance))));
            }
            case "mine": {
                String block = str(a, "block");
                if (block == null || block.isBlank()) return invalid("mine needs a \"block\" name, e.g. oak_log, stone or iron_ore");
                return new Result.Ok(new MineAction(block, count(a)));
            }
            case "collect": {
                String item = str(a, "item");
                if (item == null || item.isBlank()) return invalid("collect needs an \"item\" name");
                return new Result.Ok(new CollectAction(item));
            }
            case "craft": {
                String item = str(a, "item");
                if (item == null || item.isBlank()) return invalid("craft needs an \"item\" name, e.g. wooden_pickaxe");
                return new Result.Ok(new CraftAction(item, count(a)));
            }
            case "smelt": {
                String item = str(a, "item");
                if (item == null || item.isBlank()) return invalid("smelt needs an \"item\" name, e.g. raw_iron");
                return new Result.Ok(new SmeltAction(item, count(a)));
            }
            case "place": {
                String block = str(a, "block");
                Integer x = integer(a, "x"), y = integer(a, "y"), z = integer(a, "z");
                if (block == null || block.isBlank()) return invalid("place needs a \"block\" name, e.g. torch");
                if (x == null || y == null || z == null) return invalid("place needs whole numbers x, y and z");
                if (y < -64 || y > 320) return invalid("place y must be between -64 and 320");
                if (x < -WORLD_LIMIT || x > WORLD_LIMIT || z < -WORLD_LIMIT || z > WORLD_LIMIT) return invalid("place x and z must be inside the world border");
                return new Result.Ok(new PlaceAction(block, x, y, z));
            }
            case "eat":
                return new Result.Ok(new EatAction());
            case "equip": {
                String item = str(a, "item");
                if (item == null || item.isBlank()) return invalid("equip needs an \"item\" name, e.g. iron_chestplate");
                return new Result.Ok(new EquipAction(item));
            }
            case "attack": {
                String target = str(a, "target");
                if (target == null || target.isBlank()) return invalid("attack needs a \"target\": a mob type like zombie or a player's name");
                return new Result.Ok(new AttackAction(target));
            }
            default:
                return new Result.Ok(new StopAction());
        }
    }

    /** "count" defaulting to 1 and clamped to 1..64. */
    private static int count(JsonObject a) {
        Integer count = integer(a, "count");
        return count == null ? 1 : Math.max(1, Math.min(64, count));
    }

    /** A whole number from a JSON number or numeric string; fractions round down to the containing block. */
    static Integer integer(JsonObject a, String k) {
        JsonElement e = a.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            double d = p.isNumber() ? p.getAsDouble() : Double.parseDouble(p.getAsString().trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return (int) Math.floor(d);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String str(JsonObject a, String k) {
        JsonElement e = a.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Result invalid(String reason) {
        return new Result.Invalid(reason);
    }
}
