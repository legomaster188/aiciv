package com.aiciv.stronghold;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.world.Finder;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Eyes of ender, finding the stronghold and opening the End portal. */
public final class StrongholdModule implements ToolModule {
    static final String EYE_READINGS = "eye_readings:";
    static final String STRONGHOLD_KNOWN = "stronghold_known";
    static final String PORTAL_ROOM_KNOWN = "portal_room_known";
    static final String END_PORTAL_OPEN = "end_portal_open";
    static final int MENU_FRAME_RANGE = 24;
    static final int PORTAL_SIGHT = 12;

    @Override
    public String name() {
        return "stronghold";
    }

    @Override
    public List<JsonObject> tools() {
        return List.of(
                Tools.fn("make_eyes", "Craft eyes of ender from ender pearls and blaze powder (blaze rods are ground into powder).",
                        Tools.props("count", "integer", "how many, 1 to 12 (default 12)")),
                Tools.fn("throw_eye", "Throw one eye of ender toward the horizon, note which way it flies and pick it up again if it survives.", null),
                Tools.fn("locate_stronghold", "Throw eyes from two places to work out where the stronghold is (needs 2 or 3 eyes). Remembers it.",
                        Tools.props("spread", "integer", "blocks to walk sideways between throws, 32 to 256 (default 96)")),
                Tools.fn("go_to_stronghold", "Travel to the remembered stronghold, dig down to it and find the portal room.", null),
                Tools.fn("find_portal_room", "Search the stronghold you are in for the room with the end portal frames.", null),
                Tools.fn("fill_end_portal", "Put an eye of ender in every empty end portal frame in sight to open the portal.", null),
                Tools.fn("go_to_end", "Find the portal room, fill the frames and step into the End portal.", null));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        return switch (key) {
            case "makeeyes" -> ok(new MakeEyesRoutine(Args.intIn(args, "count", 12, 1, 12)));
            case "throweye" -> ok(new ThrowEyeAction());
            case "locatestronghold" -> ok(new LocateStrongholdRoutine(Args.intIn(args, "spread", 96, 32, 256)));
            case "gotostronghold" -> ok(new GoToStrongholdRoutine());
            case "findportalroom" -> ok(new FindPortalRoomAction(64));
            case "fillendportal" -> ok(new FillEndPortalAction());
            case "gotoend" -> ok(new GoToEndRoutine());
            default -> null;
        };
    }

    private static ActionFactory.Result ok(com.aiciv.actions.Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Stronghold: make_eyes, locate_stronghold (2 eye throws), go_to_stronghold (travels, digs down, finds the portal room), "
                + "fill_end_portal, or go_to_end for all of it.");
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        List<String> items = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        String dimension = Portals.key(bot.level());
        BlockPos feet = bot.blockPosition();
        Pos here = new Pos(feet.getX(), feet.getY(), feet.getZ());
        memory.find(EyeReadings.class).ifPresent(r -> {
            List<EyeReadings.Reading> all = r.all();
            if (all.isEmpty()) return;
            double b = all.get(all.size() - 1).bearing();
            String dir = Compass.direction(Math.sin(Math.toRadians(b)), -Math.cos(Math.toRadians(b)));
            items.add("eye readings: " + all.size() + " (last " + Math.round(b) + "° " + dir + ")");
            tags.add(EYE_READINGS + all.size());
        });
        Optional<Waypoints> waypoints = memory.find(Waypoints.class);
        waypoints.flatMap(w -> w.nearest(Waypoints.STRONGHOLD, dimension, here)).ifPresent(w -> {
            double dx = w.pos().x() - here.x(), dz = w.pos().z() - here.z();
            items.add("stronghold about " + Math.round(Math.sqrt(dx * dx + dz * dz)) + "m " + Compass.direction(dx, dz) + " at "
                    + w.pos().x() + " " + w.pos().z());
            tags.add(STRONGHOLD_KNOWN);
        });
        waypoints.flatMap(w -> w.nearest(Waypoints.END_PORTAL, dimension, here)).ifPresent(w -> {
            items.add("end portal room at " + w.pos().x() + " " + w.pos().y() + " " + w.pos().z());
            tags.add(PORTAL_ROOM_KNOWN);
        });
        if (dimension.equals("minecraft:overworld")
                && !Finder.visibleStates(bot, s -> s.is(Blocks.END_PORTAL), PORTAL_SIGHT).isEmpty()) {
            tags.add(END_PORTAL_OPEN);
        }
        return List.of(new SnapshotSection("Stronghold", items, null, tags));
    }

    // ---------------------------------------------------------------- menus

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        Map<String, Integer> inv = ctx.inventory();
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        String dimension = tags.stream().filter(t -> t.startsWith("dim:")).map(t -> t.substring("dim:".length())).findFirst().orElse("overworld");
        boolean overworld = dimension.equals("overworld");
        List<MenuOption> out = new ArrayList<>();
        int pearls = inv.getOrDefault("ender_pearl", 0);
        boolean powder = inv.getOrDefault("blaze_powder", 0) > 0 || inv.getOrDefault("blaze_rod", 0) > 0;
        if (pearls >= 1 && powder && (overworld || dimension.equals("the_nether"))) {
            out.add(option("make_eyes", "craft " + Math.min(pearls, 12) + " eyes of ender from your pearls and blaze powder", "make_eyes",
                    "count", Math.min(pearls, 12)));
        }
        boolean known = tags.contains(STRONGHOLD_KNOWN);
        boolean roomKnown = tags.contains(PORTAL_ROOM_KNOWN);
        if (overworld && inv.getOrDefault("ender_eye", 0) >= 2 && !known) {
            out.add(option("locate_stronghold", "throw eyes of ender from two places to find the stronghold", "locate_stronghold"));
        }
        if (known && !roomKnown) {
            out.add(option("go_to_stronghold", "travel to the stronghold, dig down and find the portal room", "go_to_stronghold"));
        }
        boolean frameNear = snap.blocks().stream().anyMatch(b -> b.label().equals("end_portal_frame") && b.distance() <= MENU_FRAME_RANGE);
        if (roomKnown || frameNear) {
            out.add(option("go_to_end", "fill the end portal and step into it", "go_to_end"));
        }
        return out;
    }

    private static MenuOption option(String id, String description, String tool, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
            else args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return new MenuOption(id, description, List.of(ToolCall.of(tool, args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.<Sighting>of(), List.of());
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("ender_eye", 12));
    }
}
