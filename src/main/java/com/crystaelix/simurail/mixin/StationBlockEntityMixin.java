package com.crystaelix.simurail.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

import com.crystaelix.simurail.api.consist.ConsistLocator;
import com.crystaelix.simurail.api.consist.SimurailConsist;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.extension.StationBlockEntityExtension;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import dev.ryanhcode.sable.companion.SableCompanion;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Lets Create's own {@code StationBlockEntity} (the real block placed on the real track graph) recognize a
 * docked Simurail physics consist, instead of Simurail shipping a parallel custom station block.
 * <p>
 * Real Create trains mark a station "present" via {@code Train}/{@code TravellingPoint} graph traversal, which
 * physics bogeys never participate in (they're not part of any {@code Train} object). Rather than faking a
 * {@code Train}, or mixin-patching every UI/consumer that expects one - both dead ends investigated and
 * rejected, see the write-up - this mixin does the one thing that's both safe and sufficient: it chunk-scans
 * for a nearby {@link PhysicsBogeyBlockEntity} every {@link #SCAN_INTERVAL_TICKS} ticks, the same
 * placement-order-independent scan Simurail's old custom station used, and OR's the result onto the vanilla
 * {@code trainPresent} field at the tail of {@code tick()} - after Create's own train-based assignment for
 * that tick has already happened, so this never fights it, only adds to it. Everything downstream that already
 * reads {@code trainPresent} - the comparator output in {@code StationBlock}, most display sources - picks
 * this up for free with no further changes.
 * <p>
 * <b>Deliberately not attempted:</b> feeding physics presence into {@code GlobalStation.nearestTrain}/
 * {@code getPresentTrain()} itself. That field is a {@code WeakReference<Train>} written only by Create's own
 * train-reservation system, and mail-transfer/UI code downstream of it assumes a real {@code Train} with real
 * {@code Carriage}s - there's no physics-consist equivalent to hand it without a much larger, fragile
 * proxy-object undertaking. The comparator/redstone paradigm and Simurail's own display sources don't need
 * that field at all, so this gap doesn't block either of those.
 */
@Mixin(StationBlockEntity.class)
public abstract class StationBlockEntityMixin extends SmartBlockEntity implements StationBlockEntityExtension {

	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final int SCAN_RANGE = 8;
	private static final int DEPART_GRACE_TICKS = 40;

	@Shadow
	public boolean trainPresent;

	@Unique
	private boolean simurail$physicsPresent;
	@Unique
	private int simurail$scanCooldown;
	@Unique
	private int simurail$ticksSinceBogeySeen;
	@Unique
	private int simurail$dwellTicks;
	@Unique
	@Nullable
	private UUID simurail$dockedConsistId;
	@Unique
	@Nullable
	private Component simurail$dockedConsistName;
	@Unique
	@Nullable
	private SimurailConsist simurail$dockedConsist;
	@Unique
	private int simurail$dockedLength;

	// Mixin never actually constructs this class - Create's own StationBlockEntity constructor runs instead -
	// but javac still requires the superclass constructor contract to be satisfiable, matching the pattern
	// already used by TrackObserverMixin/SingleBlockEntityEdgePoint elsewhere in this mixin package.
	public StationBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void simurail$tick(CallbackInfo ci) {
		if(level == null || level.isClientSide()) {
			return;
		}

		if(--simurail$scanCooldown > 0) {
			if(simurail$dockedConsistId != null) {
				simurail$dwellTicks++;
			}
		}
		else {
			simurail$scanCooldown = SCAN_INTERVAL_TICKS;
			simurail$rescan();
		}

		// OR our own physics-presence flag onto Create's real trainPresent every tick, after Create's own
		// train-based assignment for this tick has already run - never overwrites, only adds.
		if(simurail$physicsPresent && !this.trainPresent) {
			this.trainPresent = true;
			setChanged();
		}
	}

	@Unique
	private void simurail$rescan() {
		PhysicsBogeyBlockEntity found = simurail$findNearbyBogey();

		if(found != null) {
			simurail$ticksSinceBogeySeen = 0;
			simurail$physicsPresent = true;
			SimurailConsist consist = ConsistLocator.locate(level, found);
			if(simurail$dockedConsistId == null || !simurail$dockedConsistId.equals(consist.id())) {
				simurail$dockedConsistId = consist.id();
				simurail$dockedConsistName = consist.name();
				simurail$dockedConsist = consist;
				simurail$dockedLength = consist.length();
				setChanged();
			}
			simurail$dwellTicks += SCAN_INTERVAL_TICKS;
		}
		else if(simurail$dockedConsistId != null) {
			simurail$ticksSinceBogeySeen += SCAN_INTERVAL_TICKS;
			if(simurail$ticksSinceBogeySeen >= DEPART_GRACE_TICKS) {
				simurail$dockedConsistId = null;
				simurail$dockedConsistName = null;
				simurail$dockedConsist = null;
				simurail$dockedLength = 0;
				simurail$dwellTicks = 0;
				simurail$physicsPresent = false;
				setChanged();
			}
		}
	}

	/**
	 * Scans the block-entity maps of loaded chunks within {@link #SCAN_RANGE} for the nearest
	 * {@link PhysicsBogeyBlockEntity} - placement-history-independent, since it reads live chunk data rather
	 * than a registry populated only on placement/(re)construction.
	 */
	@Unique
	@Nullable
	private PhysicsBogeyBlockEntity simurail$findNearbyBogey() {
		if(!(level instanceof ServerLevel serverLevel)) {
			return null;
		}

		BlockPos pos = getBlockPos();
		Vec3 stationVec = Vec3.atCenterOf(pos);
		double maxDistSq = (double) SCAN_RANGE * SCAN_RANGE;
		PhysicsBogeyBlockEntity closest = null;
		double minDistSq = Double.MAX_VALUE;

		ChunkPos center = new ChunkPos(pos);
		int chunkRadius = (SCAN_RANGE >> 4) + 1;
		for(int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for(int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				int chunkX = center.x + dx;
				int chunkZ = center.z + dz;
				if(!serverLevel.hasChunk(chunkX, chunkZ)) {
					continue; // don't force-load chunks just to scan them
				}
				LevelChunk chunk = serverLevel.getChunk(chunkX, chunkZ);
				for(BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if(!(blockEntity instanceof PhysicsBogeyBlockEntity bogey)) {
						continue;
					}
					Vec3 bogeyVec = Vec3.atCenterOf(bogey.getBlockPos());
					double distSq = SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, stationVec, bogeyVec);
					if(distSq <= maxDistSq && distSq < minDistSq) {
						minDistSq = distSq;
						closest = bogey;
					}
				}
			}
		}
		return closest;
	}

	@Override
	public boolean simurail$hasDockedConsist() {
		return simurail$dockedConsistId != null;
	}

	@Override
	@Nullable
	public Component simurail$getDockedConsistName() {
		return simurail$dockedConsistName;
	}

	@Override
	@Nullable
	public SimurailConsist simurail$getDockedConsist() {
		return simurail$dockedConsist;
	}

	@Override
	public int simurail$getDockedLength() {
		return simurail$dockedLength;
	}

	@Override
	public int simurail$getDwellTicks() {
		return simurail$dwellTicks;
	}
}
