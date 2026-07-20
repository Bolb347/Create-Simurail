package com.crystaelix.simurail.mixin;

import com.crystaelix.simurail.api.controller.ICustomStationPresence;
import com.simibubi.create.content.trains.station.StationBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StationBlock.class)
public abstract class StationBlockMixin {

    @Inject(method = "getAnalogOutputSignal", at = @At("HEAD"), cancellable = true)
    private void simurail$customComparator(BlockState state, Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (level.getBlockEntity(pos) instanceof ICustomStationPresence presence && presence.simurail$hasCustomTrain()) {
            cir.setReturnValue(15);
        }
    }
}