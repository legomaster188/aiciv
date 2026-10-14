package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;

/**
 * Getting on, steering and getting off vehicles with only what a client sends: a right-click with an empty hand, the
 * movement keys ({@code xxa}/{@code zza}, the last client input, a boat's paddle input), the rider's yaw, and sneak.
 */
public final class Riding {
    private Riding() {}

    /**
     * Selects an empty hotbar slot; with none, moves the selected stack into an empty main-inventory slot and keeps the
     * now empty slot selected. False when the inventory has no empty slot at all.
     */
    public static boolean selectEmptyHand(AiPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        int selected = inv.getSelectedSlot();
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, inv.getItem(selected));
                inv.setItem(selected, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /** Right-clicks the vehicle with an empty hand; USED when the bot is then riding it. */
    public static BlockUse.Result mount(AiPlayer p, Entity vehicle) {
        if (!selectEmptyHand(p)) return BlockUse.Result.NO_EFFECT;
        BlockUse.Result r = EntityUse.interact(p, vehicle, InteractionHand.MAIN_HAND);
        if (r == BlockUse.Result.OUT_OF_REACH || r == BlockUse.Result.NOT_VISIBLE) return r;
        return p.getVehicle() == vehicle ? BlockUse.Result.USED : BlockUse.Result.NO_EFFECT;
    }

    /** Lets go of every key. */
    public static void clearInput(AiPlayer p) {
        p.xxa = 0.0F;
        p.zza = 0.0F;
        p.setJumping(false);
        p.setLastClientInput(Input.EMPTY);
        if (p.getVehicle() instanceof AbstractBoat boat) boat.setInput(false, false, false, false);
    }

    /** Lets go of the keys and gets off, which is what the server does for a sneaking client. */
    public static void dismount(AiPlayer p) {
        clearInput(p);
        p.stopRiding();
    }

    /** Holds the given movement keys, as a client's input packet would report them. */
    public static void pushInput(AiPlayer p, boolean forward, boolean left, boolean right, boolean jump) {
        p.setLastClientInput(new Input(forward, false, left, right, jump, false, false));
        p.zza = forward ? 1.0F : 0.0F;
        p.xxa = left ? 1.0F : right ? -1.0F : 0.0F;
    }

    /** The yaw that faces along (dx, dz): south 0, west 90, north 180 (or -180), east -90. */
    public static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    }

    public static void faceYaw(AiPlayer p, float yaw) {
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
    }
}
