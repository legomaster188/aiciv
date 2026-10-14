package com.aiciv.animals;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Dyes the nearest sheep that is not yet the wanted colour. */
public final class DyeSheepAction extends InteractAction {
    private final DyeColor color;
    private Item dye;

    public DyeSheepAction(String color) {
        super("sheep");
        this.color = parse(color);
        if (this.color == null) throw new IllegalArgumentException("unknown color " + color);
    }

    /** "light blue", "Light_Blue" and "red" all work; null for anything that is not a dye colour. */
    public static DyeColor parse(String name) {
        if (name == null) return null;
        return DyeColor.byName(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_'), null);
    }

    @Override
    protected String find(AiPlayer p) {
        String name = color.getSerializedName();
        dye = Items.DYE.pick(color); // looked up here: the item registry is not there in unit tests
        if (!InventoryOps.has(p, dye, 1)) return "needs " + name + "_dye (you have none)";
        List<Sheep> sheep = Animals.nearby(p, EntityTypes.SHEEP, Sheep.class, Animals.RADIUS, s -> true);
        if (sheep.isEmpty()) return "no sheep within 16 blocks";
        target = sheep.stream().filter(s -> s.getColor() != color).findFirst().orElse(null);
        return target == null ? "every sheep within 16 blocks is already " + name : null;
    }

    @Override
    protected boolean hold(AiPlayer p) {
        return InventoryOps.selectBest(p, s -> s.is(dye) ? 1 : 0);
    }

    @Override
    protected String missing() {
        return "ran out of " + color.getSerializedName() + "_dye";
    }

    @Override
    protected boolean done(AiPlayer p) {
        return ((Sheep) target).getColor() == color;
    }

    @Override
    protected String success(AiPlayer p) {
        return "dyed the sheep " + color.getSerializedName();
    }

    @Override
    protected String verb() {
        return "dye the";
    }
}
