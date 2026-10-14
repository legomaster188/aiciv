package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.dimension.Portals;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.Situation;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.Compass;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.world.Finder;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Blocks;

/** Fighting the ender dragon and leaving the End. */
public final class DragonModule implements ToolModule {
    static final String SECTION = "Dragon";
    static final String DRAGON_PERCHED = "dragon_perched";
    static final String DRAGON_FLYING = "dragon_flying";
    static final String CRYSTALS_LEFT = "crystals_left";
    static final String DRAGON_DEAD = "dragon_dead";
    static final String EXIT_PORTAL_OPEN = "exit_portal_open";

    @Override
    public String name() {
        return "dragon";
    }

    @Override
    public List<JsonObject> tools() {
        return List.of(
                Tools.fn("fight_dragon", "Fight the ender dragon to the end: shoot crystals, break cages, hit its head while it perches, dodge its breath.", null),
                Tools.fn("shoot_crystals", "Shoot open end crystals with your bow from at least 9 blocks away.",
                        Tools.props("count", "integer", "how many, 1 to 10 (default 10)")),
                Tools.fn("break_cage", "Climb beside a caged end crystal, break the bars, bridge away and shoot it through the gap.", null),
                Tools.fn("hit_dragon", "Fight the dragon for some seconds: melee its head while it perches, shoot it while it flies.",
                        Tools.props("seconds", "integer", "how long, 5 to 120 (default 30)")),
                Tools.fn("leave_end", "Walk to the fountain and through the exit portal once the dragon is dead.", null));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        return switch (key) {
            case "fightdragon" -> ok(new FightDragonRoutine());
            case "shootcrystals" -> ok(new ShootCrystalsAction(Args.intIn(args, "count", 10, 1, 10)));
            case "breakcage" -> ok(new BreakCageAction());
            case "hitdragon" -> ok(new HitDragonAction(Args.intIn(args, "seconds", 30, 5, 120)));
            case "leaveend" -> ok(new LeaveEndRoutine());
            default -> null;
        };
    }

    private static ActionFactory.Result ok(Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    /** Only in the End. */
    @Override
    public boolean relevant(SnapshotData snap, BotContext ctx) {
        return Situation.dimension(snap).equals("the_end");
    }

    @Override
    public List<String> guideLines() {
        return List.of("Dragon: fight_dragon does it all; or shoot_crystals (bow), break_cage, hit_dragon (melee the head while it "
                + "perches), then leave_end through the exit portal.");
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        if (!Portals.key(bot.level()).equals("minecraft:the_end")) return List.of();
        List<String> items = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        EnderDragon dragon = DragonView.dragon(bot).orElse(null);
        boolean dead = false;
        if (dragon != null && DragonView.dead(dragon)) dead = true;
        if (dragon != null && !dead) {
            double dx = dragon.getX() - bot.getX(), dz = dragon.getZ() - bot.getZ();
            String where = Math.round(Math.hypot(dx, dz)) + "m " + Compass.direction(dx, dz);
            boolean perched = DragonView.perched(dragon);
            items.add("ender_dragon " + Math.round(dragon.getHealth()) + "/" + Math.round(dragon.getMaxHealth()) + " hp, "
                    + (perched ? "perched on the fountain (" + where + ")" : "flying " + where));
            tags.add(perched ? DRAGON_PERCHED : DRAGON_FLYING);
        }
        List<EndCrystal> crystals = DragonView.crystals(bot);
        if (!crystals.isEmpty()) {
            long caged = crystals.stream().filter(c -> DragonView.caged(bot.level(), c)).count();
            items.add("end crystals: " + crystals.size() + " seen, " + caged + " caged");
            tags.add(CRYSTALS_LEFT);
        }
        List<BlockPos> portal = Finder.visibleStates(bot, s -> s.is(Blocks.END_PORTAL), FightDragonRoutine.PORTAL_SIGHT);
        if (!portal.isEmpty()) {
            BlockPos at = portal.get(0);
            items.add("exit portal open at " + at.getX() + " " + at.getY() + " " + at.getZ());
            tags.add(EXIT_PORTAL_OPEN);
        }
        if (dragon == null && !portal.isEmpty()) dead = true;
        if (dead) tags.add(DRAGON_DEAD);
        if (items.isEmpty()) items.add("no dragon in sight");
        return List.of(new SnapshotSection(SECTION, items, null, tags));
    }

    // ---------------------------------------------------------------- menus

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        boolean inEnd = snap.sections().stream().anyMatch(s -> s.label().equals(SECTION));
        if (!inEnd) return List.of();
        List<String> tags = snap.sections().stream().filter(s -> s.label().equals(SECTION)).flatMap(s -> s.tags().stream()).toList();
        boolean dead = tags.contains(DRAGON_DEAD);
        Map<String, Integer> inv = ctx.inventory();
        boolean armed = inv.getOrDefault("bow", 0) > 0 && inv.entrySet().stream().anyMatch(e -> e.getKey().endsWith("arrow") && e.getValue() > 0);
        List<MenuOption> out = new ArrayList<>();
        if (!dead) out.add(option("fight_dragon", "fight the ender dragon until it is dead", "fight_dragon"));
        if (tags.contains(CRYSTALS_LEFT) && armed) {
            out.add(option("shoot_crystals", "shoot the end crystals with your bow", "shoot_crystals", "count", 10));
        }
        if (tags.contains(DRAGON_PERCHED)) {
            out.add(option("hit_dragon", "hit the perched dragon on the head", "hit_dragon", "seconds", 20));
        }
        if (tags.contains(EXIT_PORTAL_OPEN) && dead) {
            out.add(option("leave_end", "walk through the exit portal out of the End", "leave_end"));
        }
        return out;
    }

    private static MenuOption option(String id, String description, String tool, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) args.addProperty((String) kv[i], (Number) kv[i + 1]);
        return new MenuOption(id, description, List.of(ToolCall.of(tool, args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(), List.of(new SnapshotSection(SECTION,
                List.of("ender_dragon 200/200 hp, flying 40m NE", "end crystals: 10 seen, 2 caged"), null,
                List.of(DRAGON_FLYING, CRYSTALS_LEFT))));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("bow", 1, "arrow", 32));
    }
}
