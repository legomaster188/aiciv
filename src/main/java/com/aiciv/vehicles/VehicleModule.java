package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.Compass;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/** Boats, minecarts and mounts: getting on, steering to a place, getting off. */
public final class VehicleModule implements ToolModule {
    private static final int WORLD_LIMIT = 29_999_984;
    static final String RIDING = "riding";
    static final String RIDEABLE = "rideable:";
    private static final int WATER_RANGE = 4;
    private static final int BOAT_TRIP = 64;
    private static final int MAX_SHOWN = 3;
    private static final int MAX_BOARD_OPTIONS = 2;
    private static final String KIND_LIST = "boat, minecart, horse, donkey, mule, llama, camel, pig, strider";
    /** id, dx, dz, word: north is -z, east is +x. */
    private static final String[][] COMPASS = {{"n", "0", "-1", "north"}, {"e", "1", "0", "east"}, {"s", "0", "1", "south"}, {"w", "-1", "0", "west"}};

    @Override
    public String name() {
        return "vehicles";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject board = Tools.props("target", "string", "boat, minecart, horse, donkey, mule, llama, camel, pig or strider");
        JsonObject rideTo = xz();
        JsonObject boatTo = xz();
        JsonObject cart = Tools.props("x", "integer", "Rail X (optional)");
        cart.add("y", Tools.prop("integer", "Rail Y (optional)"));
        cart.add("z", Tools.prop("integer", "Rail Z (optional)"));
        return List.of(
                Tools.fn("place_boat", "Put a boat or raft from your inventory on open water within 4 blocks.", null),
                Tools.fn("board", "Walk to the nearest free boat, minecart or rideable animal and get on it.", board, "target"),
                Tools.fn("dismount", "Get off whatever you are riding.", null),
                Tools.fn("ride_to", "Steer the boat, horse, pig, strider, camel or minecart you are riding to x z, at most 512 blocks away.", rideTo, "x", "z"),
                Tools.fn("boat_to", "Cross water to x z by boat: place or find a boat, paddle there, get out and take the boat back. At most 512 blocks.", boatTo, "x", "z"),
                Tools.fn("place_minecart", "Put a minecart from your inventory on a rail you can see, or on the rail at x y z.", cart),
                Tools.fn("ride_minecart", "Place a minecart on a nearby rail, ride it until it stops, then get out and pick it up.", null));
    }

    private static JsonObject xz() {
        JsonObject p = Tools.props("x", "integer", "Target X coordinate");
        p.add("z", Tools.prop("integer", "Target Z coordinate"));
        return p;
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "placeboat":
                return ok(new PlaceBoatAction());
            case "board": {
                String target = Args.str(args, "target");
                String kind = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
                if (!Mounts.KINDS.contains(kind)) return invalid("board \"target\" must be one of " + KIND_LIST);
                return ok(new BoardAction(kind));
            }
            case "dismount":
                return ok(new DismountAction());
            case "rideto":
            case "boatto": {
                boolean ride = key.equals("rideto");
                String tool = ride ? "ride_to" : "boat_to";
                Integer x = Args.integer(args, "x"), z = Args.integer(args, "z");
                if (x == null) return invalid(tool + " needs a whole number \"x\"");
                if (z == null) return invalid(tool + " needs a whole number \"z\"");
                if (Math.abs(x) > WORLD_LIMIT || Math.abs(z) > WORLD_LIMIT) return invalid(tool + " x and z must be inside the world border");
                return ok(ride ? new RideToAction(x, z) : new BoatToRoutine(x, z));
            }
            case "placeminecart": {
                String problem = Args.posProblem("place_minecart", args);
                if (problem != null) return invalid(problem);
                return ok(new PlaceMinecartAction(Args.optionalPos(args).orElse(null)));
            }
            case "rideminecart":
                return ok(new RideMinecartRoutine());
            default:
                return null;
        }
    }

    private static ActionFactory.Result ok(Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Travel: boat_to x z crosses water; board a tamed saddled horse (or pig/strider with its stick) and ride_to x z; "
                + "ride_minecart on rails; dismount.");
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        List<String> items = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        Entity riding = bot.getVehicle();
        if (riding != null) {
            items.add(Mounts.idOf(riding) + state(riding));
            tags.add(RIDING);
            tags.add(RIDING + ":" + Mounts.kindOf(riding));
        }
        List<Entity> nearby = new ArrayList<>(bot.level().getEntitiesOfClass(Entity.class, bot.getBoundingBox().inflate(Mounts.RANGE),
                e -> e != bot && e != riding && e.isAlive() && !e.isVehicle() && isMount(e) && e.distanceTo(bot) <= Mounts.RANGE
                        && Mounts.problem(bot, e) == null));
        nearby.sort(Comparator.comparingDouble(e -> e.distanceToSqr(bot)));
        int shown = 0;
        for (Entity e : nearby) {
            if (shown >= MAX_SHOWN) break;
            shown++;
            int dist = (int) Math.round(e.distanceTo(bot));
            items.add(Mounts.idOf(e) + " " + dist + "m " + Compass.direction(e.getX() - bot.getX(), e.getZ() - bot.getZ()) + state(e));
            String tag = RIDEABLE + Mounts.kindOf(e);
            if (!tags.contains(tag)) tags.add(tag);
        }
        return List.of(new SnapshotSection("Riding", items, null, tags));
    }

    private static boolean isMount(Entity e) {
        return e instanceof AbstractBoat || e instanceof AbstractMinecart || e instanceof AbstractHorse || e instanceof Pig || e instanceof Strider;
    }

    private static String state(Entity e) {
        if (e instanceof AbstractHorse h) return " (" + (h.isTamed() ? "tamed" : "wild") + (h.isSaddled() ? ", saddled)" : ")");
        if (e instanceof Mob m) return m.isSaddled() ? " (saddled)" : "";
        return "";
    }

    // ---------------------------------------------------------------- menus

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<MenuOption> out = new ArrayList<>();
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        if (tags.contains(RIDING)) out.add(new MenuOption("dismount", "get off what you are riding", List.of(ToolCall.of("dismount", new JsonObject()))));
        int boards = 0;
        for (String tag : tags) {
            if (!tag.startsWith(RIDEABLE) || boards >= MAX_BOARD_OPTIONS) continue;
            boards++;
            String kind = tag.substring(RIDEABLE.length());
            JsonObject args = new JsonObject();
            args.addProperty("target", kind);
            out.add(new MenuOption("board_" + kind, "get on the nearby " + kind, List.of(ToolCall.of("board", args))));
        }
        String held = snap.holding();
        boolean boat = held.endsWith("_boat") || held.endsWith("_raft");
        boolean water = snap.blocks().stream().anyMatch(b -> b.label().equals("water") && b.distance() <= WATER_RANGE);
        if (boat && water) {
            for (String[] d : COMPASS) {
                JsonObject args = new JsonObject();
                args.addProperty("x", snap.x() + BOAT_TRIP * Integer.parseInt(d[1]));
                args.addProperty("z", snap.z() + BOAT_TRIP * Integer.parseInt(d[2]));
                out.add(new MenuOption("boat_to_" + d[0], "cross the water " + BOAT_TRIP + " blocks " + d[3] + " in your boat", List.of(ToolCall.of("boat_to", args))));
            }
        }
        return out;
    }

    @Override
    public SnapshotData sampleSnapshot() {
        SnapshotData s = Samples.snapshot(List.of(new Sighting("water", 12, 2, "E")), List.of());
        SnapshotSection riding = new SnapshotSection("Riding", List.of("horse 6m E (tamed, saddled)"), null, List.of(RIDEABLE + "horse"));
        return new SnapshotData(s.name(), s.x(), s.y(), s.z(), s.health(), s.maxHealth(), s.food(), s.day(), s.timeOfDay(),
                s.biome(), "oak_boat", s.equipment(), s.inventory(), s.blocks(), s.players(), s.mobs(), s.remembered(),
                s.currentAction(), s.lastResult(), s.xpLevel(), List.of(riding));
    }
}
