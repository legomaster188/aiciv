package com.aiciv.building;

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
import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Build from a blueprint or a template, and remember finished structures by name. */
public final class BuildingModule implements ToolModule {
    static final int HUT_BLOCKS = 71, WALL_BLOCKS = 24;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Set<String> MATERIALS = Set.of("cobblestone", "stone", "stone_bricks", "bricks", "cobbled_deepslate", "mud_bricks");
    private static final int SECTION_SIZE = 8;

    @Override
    public String name() {
        return "building";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject build = Tools.props("name", "string", "A short name for the structure: a-z, 0-9 and _ only. Others find it under Structures.");
        build.add("layers", Tools.arrayOfStringArrays("Layers from bottom to top; each layer is a list of row strings (row 0 nearest you, columns left to right)."));
        build.add("legend", Tools.stringMap("Maps each symbol used in the rows to a block id, e.g. {\"c\": \"cobblestone\", \"p\": \"oak_planks\"}."));
        position(build);
        JsonObject template = Tools.props("template", "string", "One of " + String.join(", ", Templates.NAMES) + ".");
        template.add("material", Tools.prop("string", "Block id you carry enough of, e.g. cobblestone or oak_planks."));
        template.add("name", Tools.prop("string", "A short name for the structure: a-z, 0-9 and _ only."));
        template.add("size", Tools.prop("integer", "Optional size; each template has its own range and default."));
        position(template);
        return List.of(
                Tools.fn("build", "Build a structure you design: layers from bottom to top, each a list of rows (row 0 nearest you, columns left to right); "
                        + "legend maps symbols to blocks; '.' must be air, ' ' is left alone. Max 16x16x16. You must carry every block. "
                        + "Without x/y/z it is built 2 blocks in front of you, centred. "
                        + "Legend values may give block states, e.g. \"repeater[facing=forward,delay=2]\"; facing forward/back/left/right is relative to the blueprint.", build, "name", "layers", "legend"),
                Tools.fn("build_template", "Build a ready-made structure from one material: " + describeTemplates()
                        + ". You must carry every block. Without x/y/z it is built 2 blocks in front of you, centred.",
                        template, "template", "material", "name"));
    }

    private static void position(JsonObject props) {
        props.add("x", Tools.prop("integer", "Optional: block x of the corner cell (row 0, column 0, layer 0)."));
        props.add("y", Tools.prop("integer", "Optional: block y of that corner cell."));
        props.add("z", Tools.prop("integer", "Optional: block z of that corner cell."));
        props.add("facing", Tools.prop("string", "Optional: N, E, S or W, the way you face while building. Default the way you face now."));
    }

    private static String describeTemplates() {
        List<String> parts = new ArrayList<>();
        for (String t : Templates.NAMES) {
            try {
                int size = Templates.defaultSize(t);
                parts.add(t + " (size " + size + " by default, " + Templates.make(t, size, "stone").placeCount() + " blocks)");
            } catch (BlueprintException e) {
                throw new IllegalStateException(e);
            }
        }
        return String.join(", ", parts);
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject a) {
        return switch (key) {
            case "build" -> build(a);
            case "buildtemplate" -> buildTemplate(a);
            default -> null;
        };
    }

    private static ActionFactory.Result build(JsonObject a) {
        String name = Args.str(a, "name");
        if (name == null || name.isBlank()) return invalid("build needs a \"name\"");
        if (!a.has("layers") || a.get("layers").isJsonNull()) return invalid("build needs \"layers\"");
        if (!a.has("legend") || a.get("legend").isJsonNull()) return invalid("build needs a \"legend\"");
        return common("build", a, name, "custom", () -> Blueprint.parse(a.get("layers"), a.get("legend")));
    }

    private static ActionFactory.Result buildTemplate(JsonObject a) {
        String template = Args.str(a, "template");
        if (template == null || template.isBlank()) return invalid("build_template needs a \"template\"");
        String material = Args.str(a, "material");
        if (material == null || material.isBlank()) return invalid("build_template needs a \"material\"");
        String name = Args.str(a, "name");
        if (name == null || name.isBlank()) return invalid("build_template needs a \"name\"");
        Integer size = Args.integer(a, "size");
        String kind = template.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return common("build_template", a, name, kind, () -> Templates.make(kind, size == null ? Templates.defaultSize(kind) : size, material));
    }

    private interface BlueprintSource {
        Blueprint get() throws BlueprintException;
    }

    private static ActionFactory.Result common(String action, JsonObject a, String rawName, String kind, BlueprintSource source) {
        String name = rawName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!NAME.matcher(name).matches()) return invalid("\"name\" may use only a-z, 0-9 and _ (1 to 32 characters)");
        String pos = Args.posProblem(action, a);
        if (pos != null) return invalid(pos);
        Facing facing = null;
        String rawFacing = Args.str(a, "facing");
        if (rawFacing != null && !rawFacing.isBlank()) {
            facing = Facing.parse(rawFacing);
            if (facing == null) return invalid("facing must be N, E, S or W");
        }
        Blueprint blueprint;
        try {
            blueprint = source.get();
            String door = BuildPlan.doorProblem(blueprint);
            if (door != null) return invalid(door);
        } catch (BlueprintException e) {
            return invalid(e.getMessage());
        }
        BlockPos origin = Args.optionalPos(a).orElse(null);
        return new ActionFactory.Result.Ok(new BuildAction(name, blueprint, origin, facing, kind));
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Build: design with build (layers + legend) or use build_template; finished builds are listed under Structures for everyone. "
                + "Redstone and other facing blocks go in the legend with states, e.g. repeater[facing=forward,delay=2]; you stand and look the way a player would to place them.");
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : ctx.inventory().entrySet()) {
            String id = e.getKey();
            if (!(id.endsWith("_planks") || MATERIALS.contains(id))) continue;
            int n = e.getValue();
            if (n > bestCount || (n == bestCount && best != null && id.compareTo(best) < 0)) {
                best = id;
                bestCount = n;
            }
        }
        List<MenuOption> out = new ArrayList<>();
        if (best == null) return out;
        String spot = coordinate(snap.x()) + "_" + coordinate(snap.z());
        if (bestCount >= HUT_BLOCKS) {
            out.add(option("build_hut", "build a small " + best + " hut right here (" + HUT_BLOCKS + " blocks)", "hut", 5, best, "hut_" + spot));
        }
        if (bestCount >= WALL_BLOCKS) {
            out.add(option("build_wall", "build a " + best + " wall 8 long and 3 high in front of you (" + WALL_BLOCKS + " blocks)", "wall", 8, best, "wall_" + spot));
        }
        return out;
    }

    private static String coordinate(int v) {
        return v < 0 ? "m" + (-v) : String.valueOf(v);
    }

    private static MenuOption option(String id, String description, String template, int size, String material, String name) {
        JsonObject args = new JsonObject();
        args.addProperty("template", template);
        args.addProperty("size", size);
        args.addProperty("material", material);
        args.addProperty("name", name);
        return new MenuOption(id, description, List.of(ToolCall.of("build_template", args)));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("oak_planks", 80));
    }

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        ServerLevel level = bot.level();
        BlockPos at = bot.blockPosition();
        List<String> items = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (Structure s : StructureRegistry.of(level.getServer()).store().nearest(level.dimension().identifier().toString(),
                new Pos(at.getX(), at.getY(), at.getZ()), SECTION_SIZE)) {
            int dx = s.origin().x() - at.getX(), dy = s.origin().y() - at.getY(), dz = s.origin().z() - at.getZ();
            long metres = Math.round(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
            items.add(String.format(Locale.ROOT, "%s by %s at %d %d %d (%dm %s)", s.name(), s.owner(), s.origin().x(), s.origin().y(),
                    s.origin().z(), metres, Compass.direction(dx, dz)));
            tags.add("structure:" + s.name());
        }
        return List.of(new SnapshotSection("Structures", items, "none known", tags));
    }

    @Override
    public void serverStarted(MinecraftServer server) {
        StructureRegistry.of(server);
    }

    @Override
    public void autosave(MinecraftServer server) {
        StructureRegistry.of(server).save();
    }

    @Override
    public void serverStopping(MinecraftServer server) {
        StructureRegistry.unload(server);
    }
}
