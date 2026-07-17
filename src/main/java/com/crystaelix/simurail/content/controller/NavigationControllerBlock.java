package com.crystaelix.simurail.content.controller;

import com.crystaelix.simurail.content.SimurailBlockEntities;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.AbstractEncasedShaftBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.ticks.TickPriority;

public class NavigationControllerBlock extends AbstractEncasedShaftBlock
		implements IBE<NavigationControllerBlockEntity>, IWrenchable {

	public NavigationControllerBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
											  Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (level.isClientSide()) return ItemInteractionResult.SUCCESS;

		if (stack.getItem() instanceof com.simibubi.create.content.trains.schedule.ScheduleItem) {
			withBlockEntityDo(level, pos, be -> {
				if (!be.getSchedule().isEmpty()) {
					player.getInventory().add(be.getSchedule().copy());
					be.setSchedule(ItemStack.EMPTY);
					player.displayClientMessage(Component.translatable("simurail.navigation_controller.schedule_removed"), true);
				} else {
					be.setSchedule(stack.copy());
					if (!player.isCreative()) stack.shrink(1);
					player.displayClientMessage(Component.translatable("simurail.navigation_controller.schedule_applied"), true);
				}
			});
			return ItemInteractionResult.SUCCESS;
		}
		return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
											   BlockHitResult hitResult) {
		if (!level.isClientSide() && player instanceof ServerPlayer sp) {
			withBlockEntityDo(level, pos, be -> {
				if (be.getSchedule().isEmpty()) {
					sp.displayClientMessage(Component.translatable("simurail.navigation_controller.no_schedule"), true);
					return;
				}
				sp.displayClientMessage(be.getStatusLine(), true);
			});
		}
		return InteractionResult.SUCCESS;
	}

	public void detachKinetics(Level worldIn, BlockPos pos, boolean reAttachNextTick) {
		BlockEntity be = worldIn.getBlockEntity(pos);
		if (be == null || !(be instanceof KineticBlockEntity)) return;
		RotationPropagator.handleRemoved(worldIn, pos, (KineticBlockEntity) be);
		if (reAttachNextTick)
			worldIn.scheduleTick(pos, this, 1, TickPriority.EXTREMELY_HIGH);
	}

	@Override
	public void tick(BlockState state, ServerLevel worldIn, BlockPos pos, RandomSource random) {
		BlockEntity be = worldIn.getBlockEntity(pos);
		if (be instanceof KineticBlockEntity kte) {
			com.simibubi.create.content.kinetics.RotationPropagator.handleAdded(worldIn, pos, kte);
		}
	}

	@Override
	public Class<NavigationControllerBlockEntity> getBlockEntityClass() {
		return NavigationControllerBlockEntity.class;
	}

	@Override
	public BlockEntityType<NavigationControllerBlockEntity> getBlockEntityType() {
		return SimurailBlockEntities.NAVIGATION_CONTROLLER.get();
	}

	@Override
	@Deprecated
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		if (level.getBlockEntity(pos) instanceof NavigationControllerBlockEntity be) {
			return be.getRedstoneSignal();
		}
		return 0;
	}

	@Override
	@Deprecated
	public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return getSignal(state, level, pos, direction);
	}
}