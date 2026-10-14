package com.aiciv.dimension;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.Situation;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Dimensions: walking through portals, and the waypoints (portals, fortress, stronghold) a bot remembers per dimension. */
public final class DimensionModule implements ToolModule {
    static final int MENU_RANGE = 16;
    private static final Set<String> KINDS = Set.of("nether", "end", "any");

    @Override
    public String name() {
        return "dimension";
    }

    @Override
    public List<JsonObject> tools() {
        return List.of(Tools.fn("enter_portal",
                "Walk into the nearest nether portal, end portal or end gateway you can see and wait until it takes you to the other dimension.",
                Tools.props("kind", "string", "nether, end or any (default any)")));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        if (!key.equals("enterportal")) return null;
        String kind = Optional.ofNullable(Args.str(args, "kind")).map(k -> k.trim().toLowerCase(Locale.ROOT)).orElse("");
        if (kind.isEmpty()) kind = "any";
        if (!KINDS.contains(kind)) return new ActionFactory.Result.Invalid("kind must be nether, end or any");
        return new ActionFactory.Result.Ok(new EnterPortalAction(kind));
    }

    /** Portals matter outside the overworld, or when one is in sight. */
    @Override
    public boolean relevant(SnapshotData snap, BotContext ctx) {
        return !Situation.dimension(snap).equals("overworld")
                || Situation.sees(snap, l -> l.equals("nether_portal") || l.equals("end_portal") || l.equals("end_gateway"), Integer.MAX_VALUE);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Dimensions: enter_portal walks through a portal you can see. Waypoints lists the portals, fortress and "
                + "stronghold you know in this dimension.");
    }

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        String dimension = Portals.key(bot.level());
        String shortName = Portals.shortName(dimension);
        List<SnapshotSection> out = new ArrayList<>();
        List<String> where = dimension.equals("minecraft:overworld") ? List.of() : List.of(shortName);
        out.add(new SnapshotSection("Dimension", where, null, List.of("dim:" + shortName)));
        Waypoints waypoints = memory.find(Waypoints.class).orElse(null);
        if (waypoints != null) {
            BlockPos feet = bot.blockPosition();
            Pos here = new Pos(feet.getX(), feet.getY(), feet.getZ());
            List<String> tags = new ArrayList<>();
            for (Waypoints.Waypoint w : waypoints.nearestIn(dimension, here, 6)) tags.add("waypoint:" + w.kind());
            out.add(new SnapshotSection("Waypoints", waypoints.describe(dimension, here, 6), null, tags.stream().distinct().toList()));
        }
        return out;
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<MenuOption> out = new ArrayList<>();
        nearest(snap, "nether_portal").ifPresent(s -> out.add(option("enter_nether_portal", "nether", "nether portal", s)));
        Optional<Sighting> end = nearest(snap, "end_portal").or(() -> nearest(snap, "end_gateway"));
        end.ifPresent(s -> out.add(option("enter_end_portal", "end", s.label().replace('_', ' '), s)));
        return out;
    }

    private static Optional<Sighting> nearest(SnapshotData snap, String label) {
        return snap.blocks().stream().filter(s -> s.label().equals(label) && s.distance() <= MENU_RANGE).findFirst();
    }

    private static MenuOption option(String id, String kind, String what, Sighting s) {
        JsonObject args = new JsonObject();
        args.addProperty("kind", kind);
        return new MenuOption(id, "walk through the " + what + " " + s.distance() + "m " + s.direction(),
                List.of(ToolCall.of("enter_portal", args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(new Sighting("nether_portal", 6, 5, "N")), List.of());
    }
}
