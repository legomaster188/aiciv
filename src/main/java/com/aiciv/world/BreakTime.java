package com.aiciv.world;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla survival break time for a block and tool. */
public final class BreakTime {
    private BreakTime() {}

    public static double seconds(BlockState s, float hardness, ItemStack tool) {
        float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(s);
        boolean canHarvest = !s.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(s));
        return seconds(hardness, speed, canHarvest);
    }

    public static double seconds(float hardness, float speed, boolean canHarvest) {
        if (hardness < 0) return Double.POSITIVE_INFINITY;
        if (hardness == 0) return 0.05;
        double perTick = Math.max(1.0F, speed) / hardness / (canHarvest ? 30 : 100);
        return 1 / perTick / 20;
    }
}
