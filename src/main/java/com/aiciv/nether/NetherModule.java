package com.aiciv.nether;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.building.Facing;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** The nether: portals (built, cast from buckets, lit), fortresses and blazes. */
public final class NetherModule implements ToolModule {
    static final int PORTAL_MENU_RANGE = 32;
    static final int ROD_TARGET = 6;
    private static final String IN_NETHER = "in_nether";
    private static final String FORTRESS_KNOWN = "fortress_known";
    private static final String SPAWNER_KNOWN = "blaze_spawner_known";

    @Override
    public String name() {
        return "nether";
    }

    @Override
    public List<JsonObject> tools() {
        return List.of(
                Tools.fn("build_portal", "Build a nether portal frame from 10 obsidian (14 with obsidian corners) and light it if you hold flint_and_steel or a fire_charge.",
                        position(facing()), new String[0]),
                Tools.fn("cast_portal", "Cast a portal frame with a water_bucket and lava (a lava_bucket, or an empty bucket and lava in sight) against a temporary wall of 20 cobblestone, dirt or netherrack, then light it.",
                        position(facing()), new String[0]),
                Tools.fn("light_portal", "Light a complete empty obsidian portal frame with flint_and_steel or a fire_charge.", position(null)),
                Tools.fn("go_to_nether", "Go through a nether portal: one you see or remember, a frame to light, or a new one built from obsidian.", null),
                Tools.fn("leave_nether", "Walk back to the nearest known nether portal and go home to the overworld.", null),
                Tools.fn("find_fortress", "Walk a spiral through the nether until a nether fortress is in sight. Marks it as a waypoint.", fortressProps()),
                Tools.fn("hunt_blazes", "Fight blazes (arrows beyond 6 blocks if you have a bow) and pick up their blaze_rods.", count()),
                Tools.fn("get_blaze_rods", "Find a nether fortress if none is known nearby, then hunt blazes there for blaze_rods.", count()));
    }

    private static JsonObject position(JsonObject props) {
        JsonObject p = props == null ? new JsonObject() : props;
        p.add("x", Tools.prop("integer", "Optional: block x. Give x, y and z together."));
        p.add("y", Tools.prop("integer", "Optional: block y."));
        p.add("z", Tools.prop("integer", "Optional: block z."));
        return p;
    }

    private static JsonObject facing() {
        return Tools.props("facing", "string", "Optional: N, E, S or W, the way you face while building.");
    }

    private static JsonObject fortressProps() {
        JsonObject p = Tools.props("direction", "string", "Optional first direction: N, E, S or W. Default the most open one.");
        p.add("distance", Tools.prop("integer", "How far to search, 64 to 1024 blocks (default 384)."));
        return p;
    }

    private static JsonObject count() {
        return Tools.props("count", "integer", "How many blaze rods, 1 to 16 (default 6).");
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject a) {
        return switch (key) {
            case "buildportal" -> site("build_portal", a, true);
            case "castportal" -> site("cast_portal", a, true);
            case "lightportal" -> lightPortal(a);
            case "gotonether" -> ok(new GoToNetherRoutine());
            case "leavenether" -> ok(new LeaveNetherRoutine());
            case "findfortress" -> findFortress(a);
            case "huntblazes" -> ok(new HuntBlazesAction(Args.intIn(a, "count", ROD_TARGET, 1, 16)));
            case "getblazerods" -> ok(new GetBlazeRodsRoutine(Args.intIn(a, "count", ROD_TARGET, 1, 16)));
            default -> null;
        };
    }

    private static ActionFactory.Result ok(com.aiciv.actions.Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    private static ActionFactory.Result site(String tool, JsonObject a, boolean withFacing) {
        String pos = Args.posProblem(tool, a);
        if (pos != null) return invalid(pos);
        Facing facing = null;
        String raw = Args.str(a, "facing");
        if (withFacing && raw != null && !raw.isBlank()) {
            facing = Facing.parse(raw);
            if (facing == null) return invalid("facing must be N, E, S or W");
        }
        BlockPos origin = Args.optionalPos(a).orElse(null);
        return ok(tool.equals("build_portal") ? new BuildPortalAction(origin, facing) : new CastPortalAction(origin, facing));
    }

    private static ActionFactory.Result lightPortal(JsonObject a) {
        String pos = Args.posProblem("light_portal", a);
        if (pos != null) return invalid(pos);
        return ok(new LightPortalAction(Args.optionalPos(a).orElse(null)));
    }

    private static ActionFactory.Result findFortress(JsonObject a) {
        String direction = Args.str(a, "direction");
        if (direction != null && !direction.isBlank()) {
            direction = direction.trim().toUpperCase(Locale.ROOT);
            if (!List.of("N", "E", "S", "W").contains(direction)) return invalid("direction must be N, E, S or W");
        } else {
            direction = null;
        }
        return ok(new FindFortressAction(direction, Args.intIn(a, "distance", 384, 64, 1024)));
    }

    @Override
    public List<String> guideLines() {
        return List.of("Nether: build_portal (10+ obsidian, lights it with flint_and_steel), go_to_nether, find_fortress, hunt_blazes "
                + "or get_blaze_rods, leave_nether. Each routine is also its steps.");
    }

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        if (bot.level().dimension() != Level.NETHER) return List.of();
        BlockPos at = bot.blockPosition();
        Pos here = new Pos(at.getX(), at.getY(), at.getZ());
        String dimension = Portals.key(bot.level());
        List<String> items = new ArrayList<>();
        List<String> tags = new ArrayList<>(List.of(IN_NETHER));
        Optional<Waypoints> waypoints = memory.find(Waypoints.class);
        Optional<Waypoints.Waypoint> fortress = waypoints.flatMap(w -> w.nearest(Waypoints.FORTRESS, dimension, here));
        fortress.ifPresent(w -> {
            items.add(describe("fortress", w.pos(), here));
            tags.add(FORTRESS_KNOWN);
        });
        Optional<Waypoints.Waypoint> spawner = waypoints.flatMap(w -> w.nearest(Waypoints.BLAZE_SPAWNER, dimension, here));
        spawner.ifPresent(w -> {
            items.add(describe("blaze spawner", w.pos(), here));
            tags.add(SPAWNER_KNOWN);
        });
        int rods = InventoryOps.count(bot, s -> s.is(Items.BLAZE_ROD));
        if (rods > 0) items.add("blaze rods: " + rods);
        return List.of(new SnapshotSection("Nether", items, null, tags));
    }

    private static String describe(String what, Pos p, Pos from) {
        int dx = p.x() - from.x(), dy = p.y() - from.y(), dz = p.z() - from.z();
        long metres = Math.round(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
        return String.format(Locale.ROOT, "%s at %d %d %d (%dm %s)", what, p.x(), p.y(), p.z(), metres, Compass.direction(dx, dz));
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        boolean inNether = tags.contains(IN_NETHER);
        Map<String, Integer> inv = ctx.inventory();
        List<MenuOption> out = new ArrayList<>();
        boolean overworld = !inNether && !tags.contains("dim:the_end");
        boolean canBuild = inv.getOrDefault("obsidian", 0) >= PortalFrame.OBSIDIAN
                && (inv.containsKey("flint_and_steel") || inv.containsKey("fire_charge"));
        boolean portalSeen = snap.blocks().stream().anyMatch(s -> s.label().equals("nether_portal") && s.distance() <= PORTAL_MENU_RANGE);
        if (overworld && (canBuild || portalSeen)) {
            out.add(new MenuOption("go_to_nether", portalSeen ? "go through the nether portal you can see" : "build a nether portal from your obsidian, light it and go through",
                    List.of(ToolCall.of("go_to_nether", new JsonObject()))));
        }
        if (inNether && !tags.contains(FORTRESS_KNOWN)) {
            out.add(new MenuOption("find_fortress", "walk a spiral through the nether looking for a fortress", List.of(ToolCall.of("find_fortress", new JsonObject()))));
        }
        if (tags.contains(FORTRESS_KNOWN) || tags.contains(SPAWNER_KNOWN)) {
            JsonObject args = new JsonObject();
            args.addProperty("count", ROD_TARGET);
            out.add(new MenuOption("hunt_blazes", "fight blazes near the fortress for " + ROD_TARGET + " blaze rods", List.of(ToolCall.of("hunt_blazes", args))));
        }
        if (inNether && inv.getOrDefault("blaze_rod", 0) >= ROD_TARGET) {
            out.add(new MenuOption("leave_nether", "walk back to your nether portal and go home", List.of(ToolCall.of("leave_nether", new JsonObject()))));
        }
        return out;
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(), List.of(new SnapshotSection("Dimension", List.of(), null, List.of("dim:overworld"))));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("obsidian", 14, "flint_and_steel", 1));
    }
}
