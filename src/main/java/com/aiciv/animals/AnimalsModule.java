package com.aiciv.animals;

import com.aiciv.actions.Action;
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
import com.aiciv.perception.Compass;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

/** Taming and pets: tame, saddle, leash, tie to a fence, unleash, milk, shear, dye sheep. */
public final class AnimalsModule implements ToolModule {
    static final String LEADING = "leading";
    static final String PET_PREFIX = "pet:";
    static final String TAMEABLE_PREFIX = "tameable:";
    private static final int RADIUS = 16;
    private static final int MAX_ITEMS = 6;
    private static final int SIGHT_RADIUS = 8;

    @Override
    public String name() {
        return "animals";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject xyz = Tools.props("x", "integer", "Fence x (omit x, y, z to use the nearest fence in sight)");
        xyz.add("y", Tools.prop("integer", "Fence y"));
        xyz.add("z", Tools.prop("integer", "Fence z"));
        return List.of(
                Tools.fn("tame", "Tame the nearest wild wolf (bone), cat (cod, salmon), parrot (seeds) by feeding it, or a horse, donkey, mule or llama by riding it until it settles.",
                        Tools.props("animal", "string", "wolf, cat, parrot, horse, donkey, mule, llama"), "animal"),
                Tools.fn("saddle", "Put a saddle you carry on the nearest horse, donkey, mule, pig, strider or camel (horses must be tame).",
                        Tools.props("animal", "string", "Animal to saddle, e.g. horse, pig"), "animal"),
                Tools.fn("leash", "Put a lead you carry on the nearest animal of a kind, so you lead it along.",
                        Tools.props("animal", "string", "Animal to leash, e.g. cow, horse"), "animal"),
                Tools.fn("tie_lead", "Tie every animal you are leading to a fence (the nearest in sight, or the one at x, y, z).", xyz),
                Tools.fn("unleash", "Take the lead off the nearest leashed animal of a kind, from you or from a fence, and pick the lead up.",
                        Tools.props("animal", "string", "Animal to unleash, e.g. cow"), "animal"),
                Tools.fn("milk", "Milk a cow, goat or mooshroom into an empty bucket you carry (a mooshroom also fills a bowl).",
                        Tools.props("animal", "string", "cow (default), goat or mooshroom")),
                Tools.fn("shear", "Shear a sheep (wool), mooshroom (mushrooms) or snow golem (pumpkin) with shears you carry and pick up the drops.",
                        Tools.props("animal", "string", "sheep (default), mooshroom or snow_golem")),
                Tools.fn("dye_sheep", "Dye the nearest sheep a colour with a matching dye you carry.",
                        Tools.props("color", "string", "A dye colour such as red, light_blue, black, white"),
                        "color"));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "tame":
                return withAnimal(args, "tame", "wolf, cat, parrot, horse", TameAction::new);
            case "saddle":
                return withAnimal(args, "saddle", "horse, pig", SaddleAction::new);
            case "leash":
                return withAnimal(args, "leash", "cow, horse", LeashAction::new);
            case "unleash":
                return withAnimal(args, "unleash", "cow, horse", UnleashAction::new);
            case "tielead": {
                String problem = Args.posProblem("tie_lead", args);
                if (problem != null) return invalid(problem);
                return ok(new TieLeadAction(Args.optionalPos(args).orElse(null)));
            }
            case "milk": {
                String animal = optional(args);
                if (animal != null && !listed(MilkAction.MILKABLE, animal)) return invalid("milk works on " + String.join(", ", MilkAction.MILKABLE));
                return ok(new MilkAction(animal));
            }
            case "shear": {
                String animal = optional(args);
                if (animal != null && !listed(ShearAction.SHEARABLE, animal)) {
                    return invalid("shear works on " + String.join(", ", ShearAction.SHEARABLE));
                }
                return ok(new ShearAction(animal));
            }
            case "dyesheep": {
                String color = Args.str(args, "color");
                if (color == null || color.isBlank()) return invalid("dye_sheep needs a \"color\" such as red or light_blue");
                if (DyeSheepAction.parse(color) == null) {
                    return invalid("unknown color \"" + color + "\" (white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black)");
                }
                return ok(new DyeSheepAction(color));
            }
            default:
                return null;
        }
    }

    private static ActionFactory.Result withAnimal(JsonObject args, String tool, String examples, java.util.function.Function<String, Action> make) {
        String animal = Args.str(args, "animal");
        if (animal == null || animal.isBlank()) return invalid(tool + " needs an \"animal\" type, e.g. " + examples);
        return ok(make.apply(animal.trim()));
    }

    private static String optional(JsonObject args) {
        String animal = Args.str(args, "animal");
        return animal == null || animal.isBlank() ? null : animal.trim();
    }

    /** Whether the model's word ("cows", "Snow Golem") names one of the allowed ids. Pure: no registry access. */
    static boolean listed(List<String> allowed, String animal) {
        String q = animal.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "_");
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        if (allowed.contains(q) || (q.endsWith("s") && allowed.contains(q.substring(0, q.length() - 1)))) return true;
        return q.endsWith("es") && allowed.contains(q.substring(0, q.length() - 2));
    }

    private static ActionFactory.Result ok(Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    /** When an animal is in view, or the bot has pets or leads one. */
    @Override
    public boolean relevant(SnapshotData snap, BotContext ctx) {
        return Situation.seesMob(snap, ANIMALS::contains, Integer.MAX_VALUE)
                || Situation.tagged(snap, t -> t.equals(LEADING) || t.startsWith(PET_PREFIX) || t.startsWith(TAMEABLE_PREFIX));
    }

    private static final Set<String> ANIMALS = Set.of("wolf", "cat", "ocelot", "parrot", "horse", "donkey", "mule", "llama",
            "trader_llama", "camel", "pig", "strider", "cow", "mooshroom", "goat", "sheep", "chicken", "rabbit", "fox", "snow_golem",
            "frog", "axolotl", "turtle", "bee", "panda", "polar_bear", "armadillo", "sniffer", "happy_ghast", "nautilus");

    @Override
    public List<String> guideLines() {
        return List.of("Animals: tame wolves (bone), cats (cod), parrots (seeds), horses (ride them); saddle, leash and tie_lead to a fence; "
                + "milk cows; shear and dye_sheep. breed is in Farming.");
    }

    // ---------------------------------------------------------------- menus

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        Map<String, Integer> inv = ctx.inventory();
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        List<MenuOption> out = new ArrayList<>();
        int tames = 0;
        for (String tag : tags) {
            if (!tag.startsWith(TAMEABLE_PREFIX) || tames >= 2) continue;
            String type = tag.substring(TAMEABLE_PREFIX.length());
            out.add(option("tame_" + type, "tame the " + type + " nearby", "tame", "animal", type));
            tames++;
        }
        if (inv.getOrDefault("bucket", 0) > 0 && sighted(snap, "cow")) out.add(option("milk_cow", "milk the cow nearby into your bucket", "milk", "animal", "cow"));
        if (inv.getOrDefault("shears", 0) > 0 && sighted(snap, "sheep")) out.add(option("shear_sheep", "shear the sheep nearby", "shear", "animal", "sheep"));
        if (tags.contains(LEADING)) out.add(option("tie_lead", "tie the animal you are leading to the nearest fence", "tie_lead"));
        return out;
    }

    private static boolean sighted(SnapshotData snap, String label) {
        return snap.mobs().stream().anyMatch(m -> m.label().equals(label) && m.distance() <= SIGHT_RADIUS);
    }

    private static MenuOption option(String id, String description, String tool, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        return new MenuOption(id, description, List.of(ToolCall.of(tool, args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        SnapshotSection pets = new SnapshotSection("Pets", List.of("leading: cow"), null, List.of(TAMEABLE_PREFIX + "wolf", LEADING));
        return Samples.snapshot(List.of(), List.of(new Sighting("wolf", 1, 4, "N"), new Sighting("cow", 1, 3, "E"), new Sighting("sheep", 1, 5, "S")),
                List.of(pets));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("bone", 5, "bucket", 1, "shears", 1));
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        List<String> items = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> tameable = new LinkedHashSet<>();
        for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(RADIUS), m -> m.isAlive() && m.distanceTo(bot) <= RADIUS)) {
            String type = Animals.id(mob.getType());
            if (isMine(mob, bot)) {
                tags.add(PET_PREFIX + type);
                items.add(type + " " + Math.round(mob.distanceTo(bot)) + "m " + Compass.direction(mob.getX() - bot.getX(), mob.getZ() - bot.getZ())
                        + " (yours" + status(mob) + ")");
            } else if (canTameNow(mob, bot, type)) {
                tameable.add(type);
            }
        }
        for (String type : tameable) tags.add(TAMEABLE_PREFIX + type);
        Set<String> led = new LinkedHashSet<>();
        for (Leashable l : Leashable.leashableLeashedTo(bot)) led.add(Animals.id(((Entity) l).getType()));
        if (!led.isEmpty()) {
            items.add("leading: " + String.join(", ", led));
            tags.add(LEADING);
        }
        if (items.size() > MAX_ITEMS) items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        return List.of(new SnapshotSection("Pets", items, null, new ArrayList<>(tags)));
    }

    private static boolean isMine(Mob mob, AiPlayer bot) {
        if (mob instanceof OwnableEntity o && o.getOwnerReference() != null) return o.getOwnerReference().matches(bot);
        return false;
    }

    private static String status(Mob mob) {
        List<String> out = new ArrayList<>();
        if (mob instanceof TamableAnimal t && t.isInSittingPose()) out.add("sitting");
        if (mob.isSaddled()) out.add("saddled");
        return out.isEmpty() ? "" : ", " + String.join(", ", out);
    }

    /** An untamed grown animal the bot could start taming now: it holds the item, or the animal has to be ridden. */
    private static boolean canTameNow(Mob mob, AiPlayer bot, String type) {
        if (mob instanceof LivingEntity l && l.isBaby()) return false;
        var method = Tameables.method(type);
        if (method.isEmpty()) return false;
        if (mob instanceof TamableAnimal t) {
            if (t.isTame()) return false;
            List<String> items = Tameables.items(type);
            return com.aiciv.world.InventoryOps.count(bot, s -> items.contains(Animals.itemId(s))) > 0;
        }
        return mob instanceof AbstractHorse h && !h.isTamed();
    }
}
