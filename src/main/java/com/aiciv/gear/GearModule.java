package com.aiciv.gear;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.gear.GearPlan.Slot;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.world.ItemNames;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Armor and gear: wear or make the best armor and a shield, and show durability in the snapshot. */
public final class GearModule implements ToolModule {
    private static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND);

    @Override
    public String name() {
        return "gear";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject props = Tools.props("material", "string",
                "auto (default, best you can make), leather, golden, iron or diamond");
        props.add("shield", Tools.prop("boolean", "also make and hold a shield in the off hand; default true"));
        return List.of(Tools.fn("gear_up",
                "Put on the best armor you carry and craft what you can still make from your inventory, then hold a shield.", props));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        if (!key.equals("gearup")) return null;
        String material = args.has("material") && args.get("material").isJsonPrimitive() ? args.get("material").getAsString().trim().toLowerCase(Locale.ROOT) : "auto";
        if (material.equals("gold")) material = "golden";
        if (material.equals("netherite")) {
            return new ActionFactory.Result.Invalid("gear_up cannot make netherite armor (it needs smithing); use auto, leather, golden, iron or diamond");
        }
        List<String> materials;
        if (material.isEmpty() || material.equals("auto")) materials = GearPlan.AUTO;
        else if (GearPlan.AUTO.contains(material)) materials = List.of(material);
        else return new ActionFactory.Result.Invalid("gear_up material \"" + material + "\" is not one of auto, leather, golden, iron, diamond");
        return new ActionFactory.Result.Ok(new GearUpAction(materials, flag(args.get("shield"), true)));
    }

    private static boolean flag(JsonElement e, boolean fallback) {
        if (e == null || !e.isJsonPrimitive()) return fallback;
        String text = e.getAsString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("true")) return true;
        if (text.equals("false")) return false;
        return fallback;
    }

    @Override
    public List<String> guideLines() {
        return List.of("Gear: gear_up puts on the best armor you carry, crafts what you can still make (iron, golden, leather, diamond) "
                + "and holds a shield. Your Gear line shows armor points and durability; replace anything almost broken.");
    }

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        List<GearReport.Entry> worn = new ArrayList<>();
        for (EquipmentSlot slot : WORN) {
            ItemStack s = bot.getItemBySlot(slot);
            if (!s.isEmpty()) worn.add(entry(s));
        }
        ItemStack held = bot.getInventory().getSelectedItem();
        GearReport.Entry heldEntry = !held.isEmpty() && held.isDamageableItem() ? entry(held) : null;
        return List.of(new SnapshotSection("Gear", GearReport.lines(bot.getArmorValue(), worn, heldEntry), null, List.of()));
    }

    private static GearReport.Entry entry(ItemStack s) {
        String id = ItemNames.id(s);
        return new GearReport.Entry(id, s.isDamageableItem() ? GearReport.percentLeft(s.getDamageValue(), s.getMaxDamage()) : -1);
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<String> wornIds = List.of(snap.equipment().split(",\\s*"));
        Map<Slot, String> worn = GearPlan.wornFrom(wornIds);
        Set<String> craftable = Set.copyOf(ctx.craftableTargets());
        boolean shieldWorn = wornIds.contains(GearUpAction.SHIELD);
        boolean changes = GearPlan.next(worn, ctx.inventory(), GearPlan.AUTO, craftable::contains, GearPlan::tableScore, Set.of()) != null
                || (!shieldWorn && (ctx.inventory().containsKey(GearUpAction.SHIELD) || craftable.contains(GearUpAction.SHIELD)));
        if (!changes) return List.of();
        return List.of(new MenuOption("gear_up", "put on or make the best armor you can, and a shield",
                List.of(ToolCall.of("gear_up", new JsonObject()))));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("iron_helmet", 1));
    }
}
