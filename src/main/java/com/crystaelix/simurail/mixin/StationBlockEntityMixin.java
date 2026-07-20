package com.crystaelix.simurail.mixin;

import com.crystaelix.simurail.api.controller.ICustomStationPresence;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StationBlockEntity.class)
public abstract class StationBlockEntityMixin extends SmartBlockEntity implements ICustomStationPresence {

    @Unique
    private boolean simurail$customTrain = false;

    @Shadow
    boolean trainPresent;

    protected StationBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public boolean simurail$hasCustomTrain() {
        return simurail$customTrain;
    }

    @Override
    public void simurail$setCustomTrain(boolean present) {
        if (simurail$customTrain == present) {
            return;
        }

        simurail$customTrain = present;

        if (getLevel() != null && !getLevel().isClientSide()) {
            sendData();
        }
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void simurail$writeCustomTrain(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket && simurail$customTrain) {
            tag.putBoolean("SimurailCustomTrain", true);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void simurail$readCustomTrain(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        simurail$customTrain = tag.getBoolean("SimurailCustomTrain");

        if (getLevel() != null && getLevel().isClientSide() && simurail$customTrain) {
            trainPresent = true;
        }
    }
}