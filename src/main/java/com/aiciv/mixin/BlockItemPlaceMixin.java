package com.aiciv.mixin;

import com.aiciv.world.PlacedBlocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records every block a player places by hand, after setPlacedBy has placed any second half (doors, beds). */
@Mixin(BlockItem.class)
abstract class BlockItemPlaceMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;setPlacedBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void aiciv$recordPlacement(Block block, Level level, BlockPos pos, BlockState state, LivingEntity placer,
                                       ItemStack stack, Operation<Void> original) {
        original.call(block, level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && placer instanceof Player player) {
            PlacedBlocks.of(server.getServer()).record(server, pos, player.getScoreboardName());
        }
    }
}
