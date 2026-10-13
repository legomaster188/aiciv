package com.aiciv.combat;

import com.aiciv.actions.Args;
import com.aiciv.combat.CombatMove.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads a model's {@code moves} list: strings like "strafe left 10" or objects like {"move":"strafe","dir":"left"}. */
public final class CombatMoves {
    public static final int MAX_MOVES = 12;
    static final String NEEDS_MOVES = "combat needs \"moves\": a list like [\"attack_once zombie\", \"back_off 8\"]";
    private static final String KNOWN = "attack_once, strafe, back_off, jump_crit, shield, bow, hotbar";

    private CombatMoves() {}

    /** The moves in order; throws with a reason fit for the model. */
    public static List<CombatMove> parse(JsonElement moves) {
        if (moves == null || !moves.isJsonArray() || moves.getAsJsonArray().isEmpty()) throw new IllegalArgumentException(NEEDS_MOVES);
        JsonArray array = moves.getAsJsonArray();
        if (array.size() > MAX_MOVES) throw new IllegalArgumentException("at most " + MAX_MOVES + " moves");
        List<CombatMove> out = new ArrayList<>();
        String lastTarget = null;
        for (JsonElement e : array) {
            Raw raw = Raw.of(e);
            String target = raw.target != null ? raw.target : lastTarget;
            CombatMove move = switch (raw.name) {
                case "attack_once" -> new AttackOnce(need(raw.name, target));
                case "jump_crit" -> new JumpCrit(need(raw.name, target));
                case "bow" -> new Bow(need(raw.name, target), clamp(raw.number("charge"), 20, 1, 30));
                case "strafe" -> new Strafe(left(raw), clamp(raw.number("ticks"), 10, 1, 20));
                case "back_off" -> new BackOff(clamp(raw.number("ticks"), 8, 1, 20));
                case "shield" -> new Shield(clamp(raw.number("ticks"), 20, 1, 40));
                case "hotbar" -> {
                    Integer slot = raw.number("slot");
                    if (slot == null) throw new IllegalArgumentException("hotbar needs a slot from 1 to 9");
                    yield new Hotbar(clamp(slot, 1, 1, 9));
                }
                default -> throw new IllegalArgumentException("unknown move '" + raw.display + "' (use " + KNOWN + ")");
            };
            if (move instanceof AttackOnce a) lastTarget = a.target();
            else if (move instanceof JumpCrit j) lastTarget = j.target();
            else if (move instanceof Bow b) lastTarget = b.target();
            out.add(move);
        }
        return out;
    }

    private static String need(String move, String target) {
        if (target == null) throw new IllegalArgumentException(move + " needs a target");
        return target;
    }

    private static boolean left(Raw raw) {
        if ("left".equals(raw.dir)) return true;
        if ("right".equals(raw.dir)) return false;
        throw new IllegalArgumentException("strafe needs a direction: left or right");
    }

    private static int clamp(Integer value, int def, int min, int max) {
        return value == null ? def : Math.max(min, Math.min(max, value));
    }

    /** A move as written, before it is checked. */
    private static final class Raw {
        String name = "";
        String display = "";
        String dir;
        String target;
        Integer first; // the first number of a string move
        JsonObject object; // set for the object form

        static Raw of(JsonElement e) {
            Raw r = new Raw();
            if (e.isJsonObject()) {
                r.object = e.getAsJsonObject();
                String move = Args.str(r.object, "move");
                r.display = move == null ? "" : move.trim();
                r.name = normalise(r.display);
                String dir = Args.str(r.object, "dir");
                if (dir == null) dir = Args.str(r.object, "direction");
                r.dir = dir == null ? null : dir.trim().toLowerCase(Locale.ROOT);
                r.target = clean(Args.str(r.object, "target"));
            } else if (e.isJsonPrimitive()) {
                String[] words = e.getAsString().trim().split("\\s+");
                r.display = words[0];
                r.name = normalise(words[0]);
                StringBuilder target = new StringBuilder();
                for (int i = 1; i < words.length; i++) {
                    String w = words[i];
                    String lower = w.toLowerCase(Locale.ROOT);
                    if (lower.equals("left") || lower.equals("right")) {
                        r.dir = lower;
                    } else if (w.matches("-?\\d+")) {
                        if (r.first == null) r.first = clampInt(w);
                    } else {
                        if (target.length() > 0) target.append(' ');
                        target.append(w);
                    }
                }
                r.target = clean(target.toString());
            } else {
                r.display = e.toString();
            }
            return r;
        }

        /** A number from the object field, or the string form's first number. */
        Integer number(String key) {
            if (object == null) return first;
            Integer v = Args.integer(object, key);
            return v != null ? v : Args.integer(object, "ticks");
        }

        private static String normalise(String s) {
            return s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        }

        private static String clean(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }

        private static int clampInt(String digits) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return digits.startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            }
        }
    }
}
