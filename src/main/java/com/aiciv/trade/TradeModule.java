package com.aiciv.trade;

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
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.villager.Villager;

/** Trading with villagers through their real trading menu: see the offers, then buy or sell at the current prices. */
public final class TradeModule implements ToolModule {
    private static final int MENU_LISTS = 3;
    private static final int MENU_TRADES = 3;

    @Override
    public String name() {
        return "trade";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject list = Tools.props("villager", "string", "optional profession of the villager to ask, e.g. farmer or librarian");
        JsonObject trade = Tools.props("villager", "string", "optional profession of the villager to trade with, e.g. farmer");
        trade.add("want", Tools.prop("string", "the item you want to get, e.g. bread (use this or offer)"));
        trade.add("offer", Tools.prop("integer", "the offer number from trade_list (use this or want); also sells, e.g. wheat for emeralds"));
        trade.add("times", Tools.prop("integer", "how many times to make the trade, 1 to 12 (default 1)"));
        return List.of(
                Tools.fn("trade_list", "Walk up to a villager and read its offers (they are remembered). Prices change with demand.", list),
                Tools.fn("trade", "Trade with a villager at its real prices, for a wanted item or an offer number.", trade));
    }

    @Override
    public List<String> guideLines() {
        return List.of("Villagers: trade_list shows offers (prices change with demand); trade by \"want\" or \"offer\" index.");
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        return switch (key) {
            case "tradelist" -> new ActionFactory.Result.Ok(new TradeListAction(Args.str(args, "villager")));
            case "trade" -> trade(args);
            default -> null;
        };
    }

    private static ActionFactory.Result trade(JsonObject a) {
        String want = Args.str(a, "want");
        boolean hasWant = want != null && !want.isBlank();
        boolean hasOffer = a.has("offer") && !a.get("offer").isJsonNull();
        if (!hasWant && !hasOffer) {
            return invalid("trade needs \"want\" (an item) or \"offer\" (a number from trade_list)");
        }
        if (hasWant && hasOffer) return invalid("use either \"want\" or \"offer\", not both");
        Integer offer = null;
        if (hasOffer) {
            offer = Args.integer(a, "offer");
            if (offer == null || offer < 0) return invalid("trade \"offer\" must be a whole number from trade_list, starting at 0");
        }
        int times = Args.intIn(a, "times", 1, 1, 12);
        return new ActionFactory.Result.Ok(new TradeAction(Args.str(a, "villager"), hasWant ? want.trim() : null, offer, times));
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        BlockPos feet = bot.blockPosition();
        Pos from = new Pos(feet.getX(), feet.getY(), feet.getZ());
        VillagerMemory remembered = memory.find(VillagerMemory.class).orElse(null);
        List<Villager> seen = bot.level().getEntitiesOfClass(Villager.class, bot.getBoundingBox().inflate(Villagers.RADIUS),
                        v -> v.isAlive() && !v.isBaby() && Villagers.trades(Villagers.profession(v))
                                && v.distanceTo(bot) <= Villagers.RADIUS && bot.hasLineOfSight(v))
                .stream().sorted(Comparator.comparingDouble(v -> v.distanceToSqr(bot))).toList();
        List<String> items = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        for (Villager v : seen) {
            String profession = Villagers.profession(v);
            tags.add("villager:" + profession);
            if (remembered != null && remembered.knows(v.getUUID())) continue; // described below with its offers
            if (items.size() < MENU_LISTS) {
                BlockPos at = v.blockPosition();
                items.add(VillagerMemory.where(profession, new Pos(at.getX(), at.getY(), at.getZ()), from));
            }
        }
        if (remembered != null) {
            items.addAll(remembered.describe(from, 3));
            tags.addAll(remembered.tags(from));
        }
        if (items.isEmpty() && tags.isEmpty()) return List.of();
        return List.of(new SnapshotSection("Villagers", items, null, new ArrayList<>(tags)));
    }

    // ---------------------------------------------------------------- menu

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        Set<String> professions = new LinkedHashSet<>();
        Set<String> withOffers = new LinkedHashSet<>();
        List<String[]> offers = new ArrayList<>();
        for (SnapshotSection section : snap.sections()) {
            for (String tag : section.tags()) {
                String[] parts = tag.split(":");
                if (parts.length == 2 && parts[0].equals("villager")) {
                    professions.add(parts[1]);
                } else if (parts.length == 6 && parts[0].equals("offer")) {
                    withOffers.add(parts[1]);
                    offers.add(parts);
                }
            }
        }
        List<MenuOption> out = new ArrayList<>();
        int lists = 0;
        for (String profession : professions) {
            if (withOffers.contains(profession) || lists >= MENU_LISTS) continue;
            lists++;
            out.add(new MenuOption("trade_list_" + profession, "ask the " + profession + " what it trades",
                    List.of(call("trade_list", "villager", profession))));
        }
        int trades = 0;
        for (String[] o : offers) {
            if (trades >= MENU_TRADES) break;
            int countA = number(o[5]);
            int index = number(o[2]);
            if (countA <= 0 || index < 0 || ctx.inventory().getOrDefault(o[4], 0) < countA) continue;
            trades++;
            ToolCall call = call("trade", "villager", o[1], "offer", index, "times", 1);
            out.add(new MenuOption("trade_" + o[1] + "_" + o[2], "trade " + countA + " " + o[4] + " for " + o[3], List.of(call)));
        }
        return out;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ToolCall call(String name, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
            else args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return ToolCall.of(name, args);
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(), List.of(new SnapshotSection("Villagers", List.of("farmer 6m N"), null,
                List.of("villager:farmer", "villager:librarian", "offer:farmer:1:bread:emerald:1"))));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("emerald", 3));
    }
}
