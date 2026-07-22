package com.crystaelix.simurail.content.controller;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import net.createmod.catnip.data.Couple;

import org.jetbrains.annotations.Nullable;

import com.crystaelix.simurail.api.controller.ICustomStationPresence;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.*;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.simibubi.create.content.trains.station.GlobalStation;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;

public class NavigationControllerBlockEntity extends SplitShaftBlockEntity {

	private static final int EVALUATE_INTERVAL = 5;

	private static final double BRAKE_START_DISTANCE = 128.0;
	private static final double FULL_STOP_DISTANCE = 0.35;
	private static final double ARRIVAL_HOLD_DISTANCE = 1.0;
	private static final double MAX_DECELERATION = 0.4;

	private static final int SUBLEVEL_SCAN_DEPTH = 8;
	private static final int MAX_OBJECT_GRAPH_DEPTH = 8;
	private static final int DOCKING_CONNECTOR_SCAN_RADIUS = 12;
	private static final double DOCKING_CONNECTOR_MAX_PAIR_DISTANCE = 4.5;
	private static final double DOCKING_CONNECTOR_MAX_PAIR_DISTANCE_SQ = DOCKING_CONNECTOR_MAX_PAIR_DISTANCE * DOCKING_CONNECTOR_MAX_PAIR_DISTANCE;
	private static final double DOCKING_CONNECTOR_MIN_CENTER_DISTANCE_SQ = 1.5 * 1.5;

	private static final Direction[] CAPABILITY_SIDES = new Direction[]{
			null,
			Direction.DOWN,
			Direction.UP,
			Direction.NORTH,
			Direction.SOUTH,
			Direction.WEST,
			Direction.EAST
	};

	private ItemStack scheduleStack = ItemStack.EMPTY;

	private int tickCounter = 0;
	private int currentEntry = 0;

	private boolean arrivedAtDestination = false;

	private final List<Integer> conditionProgress = new ArrayList<>();
	private final List<CompoundTag> conditionContext = new ArrayList<>();

	private float currentSpeedMultiplier = 0.0f;
	private int directionSign = 1;

	private double lastDistance = Double.NaN;

	private List<TrackNode> currentPath = Collections.emptyList();

	@Nullable
	private String lastMatchedStationName;

	@Nullable
	private GlobalStation currentStation = null;

	@Nullable
	private TrackEdge lastStationEdge = null;

	private boolean lastMovingTowardsNode2 = true;
	private boolean hasLastMovingDirection = false;

	private Vec3 lastTravelDir = Vec3.ZERO;
	private boolean hasDirectionSignBeenSet = false;
	private double lastSourceSign = 1.0;
	private int lastForwardSign = 0;

	private String debugInfo = "";

	@Nullable
	private Target currentTarget = null;

	@Nullable
	private Train cachedTrain = null;

	private long lastConditionTickTime = -1;

	private boolean storagePortsActive = false;

	private boolean dockingConnectorsActive = false;
	private final Set<ConnectorRef> activatedDockingConnectors = new HashSet<>();
	private final Set<DockingPairRef> activeDockingPairs = new HashSet<>();

	private static final int DEPARTURE_HOLD_TICKS = 60;
	private static final float ACCEL_BASE = 0.25f;
	private static final float ACCEL_SCALE = 0.25f;
	private int departureHoldTicks = 0;
	private boolean hasDeparted = false;
	private int dockUndockDelayTicks = 0;
	private boolean lastRedstoneOutput = false;
	private static final double DEPARTURE_CLEAR_DISTANCE = 5.0;

	private record ConnectorRef(ServerLevel level, BlockPos pos) {}
	private record DockingPairRef(BlockPos a, BlockPos b) {
		static DockingPairRef of(BlockPos a, BlockPos b) {
			return Long.compareUnsigned(a.asLong(), b.asLong()) <= 0
					? new DockingPairRef(a, b)
					: new DockingPairRef(b, a);
		}
	}

	private record DockingCandidate(DockingConnectorBlockEntity a, DockingConnectorBlockEntity b, double distSq) {}

	@Nullable
	private BlockPos customStationPos = null;

	public NavigationControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public interface IStoragePortActivatable {
		void setStoragePortActive(boolean active);
	}

	@Override
	public float getRotationSpeedModifier(Direction face) {
		if (!hasSource()) return 0f;
		if (face == getSourceFacing()) return 1.0f;
		return currentSpeedMultiplier;
	}

	public int getRedstoneSignal() {
		return 0;
	}

	public ScrollValueBehaviour maxSpeedScroll;

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);

		ValueBoxTransform slot = new ValueBoxTransform() {
			@Override
			public boolean testHit(LevelAccessor level, BlockPos pos, BlockState state, Vec3 hit) {
				double dx = Math.abs(hit.x - 0.5);
				double dz = Math.abs(hit.z - 0.5);
				return hit.y > 0.9 && dx < 0.2 && dz < 0.2;
			}

			@Override
			public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
				return new Vec3(0.5, 1.01, 0.5);
			}

			@Override
			public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
				Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);

				ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(270.0F));
				ms.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));

				if (axis == Direction.Axis.X) {
					ms.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90.0F));
				} else if (axis == Direction.Axis.Y) {
					ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90.0F));
				}
			}
		};

		maxSpeedScroll = new ScrollValueBehaviour(
				Component.translatable("simurail.navigation_controller.max_speed"),
				this,
				slot
		);

		maxSpeedScroll.between(1, 256);
		maxSpeedScroll.value = 32;

		behaviours.add(maxSpeedScroll);
	}

	private record EdgePathDirection(boolean towardsNode2, int startIdx) {}

	private EdgePathDirection getEdgePathDirection(List<TrackNode> path, TrackEdge edge) {
		if (path == null || path.isEmpty() || edge == null) {
			return new EdgePathDirection(false, -1);
		}

		int idx1 = path.indexOf(edge.node1);
		int idx2 = path.indexOf(edge.node2);

		if (idx1 == -1 && idx2 == -1) {
			return new EdgePathDirection(false, -1);
		}

		int earliestIdx;
		TrackNode earliestNode;
		TrackNode otherNode;

		if (idx1 == -1) {
			earliestIdx = idx2;
			earliestNode = edge.node2;
			otherNode = edge.node1;
		} else if (idx2 == -1) {
			earliestIdx = idx1;
			earliestNode = edge.node1;
			otherNode = edge.node2;
		} else {
			if (idx1 <= idx2) {
				earliestIdx = idx1;
				earliestNode = edge.node1;
				otherNode = edge.node2;
			} else {
				earliestIdx = idx2;
				earliestNode = edge.node2;
				otherNode = edge.node1;
			}

			if (earliestIdx + 1 < path.size() && path.get(earliestIdx + 1) == otherNode) {
				boolean towardsNode2 = otherNode == edge.node2;
				return new EdgePathDirection(towardsNode2, earliestIdx + 1);
			}
		}

		boolean towardsNode2 = earliestNode == edge.node2;
		return new EdgePathDirection(towardsNode2, earliestIdx);
	}

	private double computeSteerForBogey(PhysicsBogeyBlockEntity bogey, TrackGraph graph,
										List<TrackNode> path, TrackNode n1, TrackNode n2,
										boolean movingTowardsNode2, GlobalStation targetStation) {
		PhysicsBogeyAxle axle = findAnchorAxle(bogey);
		if (axle == null) return 0.0;

		TravellingPoint point = axle.getTrackPoint();
		if (point == null || point.edge == null) return 0.0;

		EdgePathDirection dir = getEdgePathDirection(path, point.edge);
		if (dir.startIdx() < 0) return 0.0;

		boolean bogeyMovingTowardsNode2 = dir.towardsNode2();
		int startIdx = dir.startIdx();

		Vec3 requiredDir = bogeyMovingTowardsNode2
				? point.edge.node2.getLocation().getLocation()
				.subtract(point.edge.node1.getLocation().getLocation())
				.normalize()
				: point.edge.node1.getLocation().getLocation()
				.subtract(point.edge.node2.getLocation().getLocation())
				.normalize();

		Vec3 lookahead = getLookaheadTangent(graph, point, path, startIdx, bogeyMovingTowardsNode2, 16.0);
		if (lookahead == Vec3.ZERO) return 0.0;

		double cross = requiredDir.x * lookahead.z - requiredDir.z * lookahead.x;
		double steer = Math.clamp(cross * 3.0, -1.0, 1.0);

		Vec3 bogeyForward = Vec3.atLowerCornerOf(bogey.getFacing().getNormal());
		if (requiredDir.dot(bogeyForward) < 0) steer *= -1;

		return steer;
	}

	@Override
	public void tick() {
		super.tick();

		if (level == null || level.isClientSide()) return;

		if (departureHoldTicks > 0) {
			departureHoldTicks--;
		}

		if (dockUndockDelayTicks > 0) {
			dockUndockDelayTicks--;
		}

		if (dockingConnectorsActive && level instanceof ServerLevel) {
			for (ConnectorRef ref : activatedDockingConnectors) {
				if (ref.level().getBlockEntity(ref.pos()) instanceof DockingConnectorBlockEntity connector) {
					setDockingConnectorPowered(connector, true);
				}
			}
		}

		if (arrivedAtDestination && currentTarget != null && level instanceof ServerLevel serverLevel) {
			tickArrivalConditions(serverLevel);
		}

		tickCounter++;
		if (tickCounter < EVALUATE_INTERVAL) return;
		tickCounter = 0;

		evaluateAndDrive();
	}

	private void tickArrivalConditions(ServerLevel level) {
		if (currentTarget == null || currentStation == null) return;

		PhysicsBogeyBlockEntity anchorBogey = findAdjacentBogey();
		if (anchorBogey == null) return;

		Set<PhysicsBogeyBlockEntity> consist = traverseConsist(anchorBogey);

		if (cachedTrain == null || level.getGameTime() % 20 == 0) {
			cachedTrain = findTrain(level, currentTarget.station, anchorBogey);
		}

		if (tickConditionsOnce(level, currentTarget.entry, cachedTrain, currentTarget.station, consist)) {
			advanceEntry(currentTarget.schedule, level.registryAccess());
			setStoragePortsActive(level, consist, currentTarget.station, false);
		}
	}

	private boolean tickConditionsOnce(ServerLevel level, ScheduleEntry entry, @Nullable Train train,
									   GlobalStation station, Set<PhysicsBogeyBlockEntity> consist) {
		long gameTime = level.getGameTime();

		if (gameTime == lastConditionTickTime) {
			return false;
		}

		lastConditionTickTime = gameTime;

		return tickConditions(level, entry, train, station, consist);
	}

	private void evaluateAndDrive() {
		if (!(level instanceof ServerLevel serverLevel)) return;

		PhysicsBogeyBlockEntity anchorBogey = findAdjacentBogey();
		if (anchorBogey == null) {
			clearOverridesOnConsist();
			setSpeedMultiplier(0.0f);
			currentTarget = null;
			cachedTrain = null;
			debugInfo = "No bogey found";
			departureHoldTicks = 0;
			hasDeparted = false;
			dockUndockDelayTicks = 0;

			if (level instanceof ServerLevel sl) {
				deactivateDockingConnectors(sl);
				setStoragePortsActive(sl, Collections.<PhysicsBogeyBlockEntity>emptySet(), null, false);
				updateCustomStationPresence(sl, null);
			}
			return;
		}

		Set<PhysicsBogeyBlockEntity> consist = traverseConsist(anchorBogey);

		if (hasDeparted && Math.abs(anchorBogey.getMovementSpeed()) > 0.1) {
			hasDeparted = false;
		}

		Target target = resolveDestination(serverLevel, anchorBogey);
		currentTarget = target;

		if (target == null) {
			cachedTrain = null;
		}

		double targetRPM = (maxSpeedScroll != null) ? Math.min(maxSpeedScroll.getValue(), 256.0) : 32.0;

		double inputSpeed = getSpeed();
		double absInputSpeed = Math.abs(inputSpeed);

		double baselineSpeed = Math.max(absInputSpeed, 16.0);
		double gearRatio = targetRPM / baselineSpeed;
		double maxSafeRatio = 256.0 / baselineSpeed;
		gearRatio = Math.min(gearRatio, maxSafeRatio);

		double brakeStrength = 0.0;
		float newMultiplier = 0.0f;

		boolean hasValidPath = false;
		PhysicsBogeyBlockEntity frontBogey = null;
		double frontStopOffset = 0.0;
		TrackGraph graph = null;

		List<TrackNode> pathForSteering = currentPath;
		TrackNode n1ForSteering = null;
		TrackNode n2ForSteering = null;
		boolean movingTowardsNode2ForSteering = false;

		boolean movingTowardsNode2 = false;
		boolean mustStopToReverse = false;

		if (target != null && hasSource()) {
			PhysicsBogeyAxle anchor = findAnchorAxle(anchorBogey);

			if (anchor != null) {
				TravellingPoint point = anchor.getTrackPoint();
				graph = anchor.getTrackGraph();

				if (graph != null && point != null && point.edge != null) {
					TrackNode n1 = point.edge.node1;
					TrackNode n2 = point.edge.node2;

					double trackSpeed = anchor.getTrackSpeed();
					boolean trackReversed = anchor.isTrackReversed();

					Vec3 edgeForward = n2.getLocation().getLocation()
							.subtract(n1.getLocation().getLocation())
							.normalize();

					Vec3 axleForward = trackReversed ? edgeForward.scale(-1) : edgeForward;

					double sourceSign = Math.signum(inputSpeed);
					if (sourceSign == 0) sourceSign = 1;

					if (Math.abs(trackSpeed) > 0.05) {
						movingTowardsNode2 = (trackSpeed > 0.0) ^ trackReversed;
						lastMovingTowardsNode2 = movingTowardsNode2;
						hasLastMovingDirection = true;
					} else {
						movingTowardsNode2 = hasLastMovingDirection
								? lastMovingTowardsNode2
								: this.directionSign > 0;
					}

					int directionPreference = 0;

					if (Math.abs(trackSpeed) > 0.05) {
						directionPreference = ((trackSpeed > 0.0) ^ trackReversed) ? 2 : 1;
					} else if (hasLastMovingDirection) {
						directionPreference = lastMovingTowardsNode2 ? 2 : 1;
					}

					if (directionPreference == 0 && lastTravelDir.lengthSqr() > 0.01) {
						directionPreference = lastTravelDir.dot(edgeForward) >= 0.0 ? 2 : 1;
					}

					if (directionPreference == 0) {
						Vec3 bogeyForward = Vec3.atLowerCornerOf(anchorBogey.getFacing().getNormal());
						directionPreference = bogeyForward.dot(edgeForward) >= 0.0 ? 2 : 1;
					}

					TrackEdge targetStationEdge = getStationEdge(graph, target.station);

					if (currentStation != null) {
						TrackEdge oldStationEdge = getStationEdge(graph, currentStation);

						if (oldStationEdge == null || !sameEdge(point.edge, oldStationEdge)) {
							currentStation = null;
							lastStationEdge = null;
						}
					}

					boolean onTargetStationEdge = targetStationEdge != null
							&& sameEdge(point.edge, targetStationEdge)
							&& (target.station != currentStation || arrivedAtDestination);

					if (onTargetStationEdge) {
						double stationPosOnEdge = target.station.getLocationOn(targetStationEdge);

						double startPos = point.position;
						if (point.edge.node1 != targetStationEdge.node1) {
							startPos = point.edge.getLength() - point.position;
						}

						boolean desiredTowardsNode2;

						if (Math.abs(startPos - stationPosOnEdge) < FULL_STOP_DISTANCE) {
							desiredTowardsNode2 = movingTowardsNode2;
						} else {
							boolean shouldIncreasePosition = startPos < stationPosOnEdge;

							if (point.edge.node1 == targetStationEdge.node1) {
								desiredTowardsNode2 = shouldIncreasePosition;
							} else {
								desiredTowardsNode2 = !shouldIncreasePosition;
							}
						}

						currentPath = desiredTowardsNode2 ? List.of(n1, n2) : List.of(n2, n1);
						lastDistance = Double.NaN;
					} else if (currentPath.isEmpty() || !isCurrentEdgeOnPath(currentPath, point.edge, movingTowardsNode2)) {
						currentPath = buildBestStationPath(
								graph,
								point,
								n1,
								n2,
								target.station,
								currentStation,
								directionPreference
						);

						lastDistance = Double.NaN;
					}

					EdgePathDirection currentDir = getEdgePathDirection(currentPath, point.edge);
					boolean usablePath = !currentPath.isEmpty() && currentDir.startIdx() >= 0;

					if (usablePath) {
						movingTowardsNode2 = currentDir.towardsNode2();

						if (onTargetStationEdge) {
							double stationPosOnEdge = target.station.getLocationOn(targetStationEdge);

							double startPos = point.position;
							if (point.edge.node1 != targetStationEdge.node1) {
								startPos = point.edge.getLength() - point.position;
							}

							if (Math.abs(startPos - stationPosOnEdge) >= FULL_STOP_DISTANCE) {
								boolean shouldIncreasePosition = startPos < stationPosOnEdge;

								if (point.edge.node1 == targetStationEdge.node1) {
									movingTowardsNode2 = shouldIncreasePosition;
								} else {
									movingTowardsNode2 = !shouldIncreasePosition;
								}
							}
						}

						movingTowardsNode2ForSteering = movingTowardsNode2;

						lastMovingTowardsNode2 = movingTowardsNode2;
						hasLastMovingDirection = true;

						Vec3 desiredTravelDir = movingTowardsNode2 ? edgeForward : edgeForward.scale(-1);

						frontBogey = selectFrontBogey(consist, desiredTravelDir);
						frontStopOffset = computeFrontStopOffset(anchorBogey, frontBogey, desiredTravelDir);

						boolean sourceChanged = Math.abs(sourceSign - lastSourceSign) > 0.001;
						if (sourceChanged) {
							lastForwardSign = 0;
						}

						if (Math.abs(trackSpeed) > 0.05 && Math.abs(currentSpeedMultiplier) > 0.01) {
							boolean actualTowardsNode2Now = (trackSpeed > 0.0) ^ trackReversed;

							if (actualTowardsNode2Now == movingTowardsNode2) {
								lastForwardSign = currentSpeedMultiplier > 0.0 ? 1 : -1;
							}
						}

						int desiredSign;

						boolean hasPhysicalReference = lastForwardSign != 0 && lastTravelDir.lengthSqr() > 0.01;

						if (hasPhysicalReference) {
							double dot = desiredTravelDir.dot(lastTravelDir);

							if (dot > 0.5) {
								desiredSign = lastForwardSign;
							} else if (dot < -0.5) {
								desiredSign = -lastForwardSign;
							} else {
								desiredSign = this.directionSign;
							}
						} else {
							desiredSign = computeDirectionSign(
									edgeForward,
									axleForward,
									movingTowardsNode2,
									sourceSign
							);
						}

						boolean actualTowardsNode2 = Math.abs(trackSpeed) > 0.05
								? ((trackSpeed > 0.0) ^ trackReversed)
								: movingTowardsNode2;

						boolean wantsPhysicalReverse = actualTowardsNode2 != movingTowardsNode2;
						boolean wantsSignFlip = desiredSign != this.directionSign;

						if (Math.abs(trackSpeed) > 0.1 && (wantsPhysicalReverse || wantsSignFlip)) {
							mustStopToReverse = true;
						} else {
							if (!hasDirectionSignBeenSet || wantsSignFlip || sourceChanged) {
								this.directionSign = desiredSign;

								if (Math.abs(trackSpeed) <= 0.1) {
									lastForwardSign = desiredSign;
								}
							}

							lastTravelDir = desiredTravelDir;
							hasDirectionSignBeenSet = true;
							lastSourceSign = sourceSign;
						}

						int startIdx = currentDir.startIdx();

						if (startIdx > 0 && startIdx < currentPath.size()) {
							currentPath = new ArrayList<>(currentPath.subList(startIdx, currentPath.size()));
						}

						List<TrackNode> remainingPath = currentPath;

						double distance = calculatePathDistance(
								graph,
								remainingPath,
								point,
								movingTowardsNode2,
								target.station,
								currentStation
						);

						double stopThreshold = frontStopOffset + FULL_STOP_DISTANCE;
						double holdThreshold = frontStopOffset + ARRIVAL_HOLD_DISTANCE;

						boolean shouldArrive = false;
						if (departureHoldTicks <= 0) {
							boolean canArrive = !hasDeparted || distance > DEPARTURE_CLEAR_DISTANCE;
							if (canArrive) {
								shouldArrive = distance <= stopThreshold
										|| (arrivedAtDestination && distance <= holdThreshold);
							}
						}

						if (shouldArrive) {
							currentStation = target.station;
							lastStationEdge = targetStationEdge != null ? targetStationEdge : point.edge;

							arrivedAtDestination = true;

							cachedTrain = findTrain(serverLevel, target.station, anchorBogey);

							if (tickConditionsOnce(serverLevel, target.entry, cachedTrain, target.station, consist)) {
								advanceEntry(target.schedule, level.registryAccess());
								setStoragePortsActive(serverLevel, consist, target.station, false);
							}

							newMultiplier = 0.0f;
							brakeStrength = 1.0;
						} else {
							arrivedAtDestination = false;
							resetConditionProgress();
							debugInfo = "";

							double effectiveDistance = Math.max(0.0, distance - frontStopOffset);

							double currentSpeed = Math.abs(anchorBogey.getMovementSpeed());
							double stoppingDistance = (currentSpeed * currentSpeed) / (2.0 * MAX_DECELERATION);
							double brakeStart = Math.min(stoppingDistance + FULL_STOP_DISTANCE, BRAKE_START_DISTANCE);

							double maxLinearSpeed = targetRPM / 16.0;
							double safeSpeed = Math.sqrt(2.0 * MAX_DECELERATION * Math.max(0.0, effectiveDistance - FULL_STOP_DISTANCE));
							double desiredSpeed = Math.min(maxLinearSpeed, safeSpeed);

							double speedFraction = Math.clamp(desiredSpeed / maxLinearSpeed, 0.0, 1.0);

							newMultiplier = (float) (speedFraction * gearRatio * this.directionSign);

							if (Math.abs(newMultiplier) < 0.01f) {
								newMultiplier = 0.0f;
							}

							if (Math.abs(newMultiplier) < 0.01f) {
								newMultiplier = 0.0f;
							}

							if (effectiveDistance <= brakeStart) {
								double brakeRamp = (effectiveDistance - FULL_STOP_DISTANCE)
										/ Math.max(0.01, brakeStart - FULL_STOP_DISTANCE);

								brakeStrength = Math.clamp(1.0 - brakeRamp, 0.0, 1.0);
							} else {
								brakeStrength = 0.0;
							}

							if (mustStopToReverse) {
								newMultiplier = 0.0f;
								brakeStrength = 1.0;
							}

							lastDistance = distance;
						}

						n1ForSteering = n1;
						n2ForSteering = n2;
						pathForSteering = currentPath;

						hasValidPath = true;
					}
				}
			}
		} else {
			currentStation = null;
			lastStationEdge = null;

			arrivedAtDestination = false;
			lastDistance = Double.NaN;
			currentPath = Collections.emptyList();

			newMultiplier = 0.0f;
			brakeStrength = 1.0;

			this.directionSign = 1;

			lastTravelDir = Vec3.ZERO;
			hasDirectionSignBeenSet = false;
			lastSourceSign = 1.0;
			lastForwardSign = 0;

			debugInfo = (target == null && !scheduleStack.isEmpty()) ? "No valid destination" : "";
		}

		if (target != null && hasSource() && !hasValidPath) {
			newMultiplier = 0.0f;
			brakeStrength = 1.0;

			if (debugInfo.isEmpty()) {
				debugInfo = "No valid path";
			}
		}

		boolean shouldStorage = arrivedAtDestination;
		boolean shouldDock = arrivedAtDestination || departureHoldTicks > 0;

		setStoragePortsActive(
				serverLevel,
				consist,
				target != null ? target.station : null,
				shouldStorage
		);

		updateDockingConnectors(
				serverLevel,
				consist,
				target != null ? target.station : null,
				shouldDock
		);

		if (departureHoldTicks > 0 || dockUndockDelayTicks > 0) {
			newMultiplier = 0.0f;
			brakeStrength = 1.0;
		} else {
			newMultiplier = clampAcceleration(newMultiplier);

			if (hasDeparted) {
				float maxDepartMultiplier = 0.25f;
				newMultiplier = Math.copySign(Math.min(Math.abs(newMultiplier), maxDepartMultiplier), newMultiplier);
			}
		}

		if (frontBogey == null && hasValidPath && n1ForSteering != null && n2ForSteering != null) {
			Vec3 edgeForward = n2ForSteering.getLocation().getLocation()
					.subtract(n1ForSteering.getLocation().getLocation())
					.normalize();

			Vec3 travelDir = movingTowardsNode2ForSteering ? edgeForward : edgeForward.scale(-1);

			frontBogey = selectFrontBogey(consist, travelDir);
		}

		for (PhysicsBogeyBlockEntity bogey : consist) {
			bogey.clearNavigationOverride();

			if (bogey == frontBogey && hasValidPath && target != null && graph != null
					&& n1ForSteering != null && n2ForSteering != null) {
				double steer = computeSteerForBogey(
						bogey,
						graph,
						pathForSteering,
						n1ForSteering,
						n2ForSteering,
						movingTowardsNode2ForSteering,
						target.station
				);

				bogey.setNavigationSteerOverride(steer);
			}

			bogey.setNavigationBrakeOverride(brakeStrength);
		}

		setSpeedMultiplier(newMultiplier);

		if (level instanceof ServerLevel sl) {
			updateCustomStationPresence(sl, currentStation);
		}

		if (level != null && !level.isClientSide()) {
			boolean redstoneOutput = getRedstoneSignal() > 0;
			if (redstoneOutput != lastRedstoneOutput) {
				lastRedstoneOutput = redstoneOutput;
				level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
			}
		}
	}

	private float clampAcceleration(float desired) {
		float current = currentSpeedMultiplier;

		if (Math.abs(desired) < 0.01f) {
			return 0.0f;
		}

		if (Math.abs(current) < 0.01f) {
			return Math.copySign(Math.min(Math.abs(desired), ACCEL_BASE), desired);
		}

		if ((desired < 0.0f) != (current < 0.0f)) {
			return 0.0f;
		}

		float absCurrent = Math.abs(current);
		float absDesired = Math.abs(desired);

		if (absDesired > absCurrent) {
			float maxIncrease = ACCEL_BASE + absCurrent * ACCEL_SCALE;
			float next = Math.min(absDesired, absCurrent + maxIncrease);

			return desired < 0.0f ? -next : next;
		}

		float maxDecrease = 1.0f + absCurrent * 0.5f;
		float next = Math.max(absDesired, absCurrent - maxDecrease);

		return desired < 0.0f ? -next : next;
	}

	@Nullable
	private PhysicsBogeyBlockEntity selectFrontBogey(Set<PhysicsBogeyBlockEntity> consist, Vec3 travelDir) {
		PhysicsBogeyBlockEntity best = null;
		double maxDot = -Double.MAX_VALUE;

		for (PhysicsBogeyBlockEntity bogey : consist) {
			Vec3 bogeyPos = Vec3.atCenterOf(bogey.getBlockPos());
			double dot = bogeyPos.dot(travelDir);

			if (dot > maxDot) {
				maxDot = dot;
				best = bogey;
			}
		}

		return best;
	}

	private double computeFrontStopOffset(PhysicsBogeyBlockEntity anchorBogey,
										  @Nullable PhysicsBogeyBlockEntity frontBogey,
										  Vec3 travelDir) {
		if (frontBogey == null || frontBogey == anchorBogey) {
			return 0.0;
		}

		Vec3 anchorPos = Vec3.atCenterOf(anchorBogey.getBlockPos());
		Vec3 frontPos = Vec3.atCenterOf(frontBogey.getBlockPos());

		double offset = frontPos.subtract(anchorPos).dot(travelDir);

		return Math.max(0.0, offset);
	}

	@Nullable
	private Train findTrain(ServerLevel level, GlobalStation station, PhysicsBogeyBlockEntity anchorBogey) {
		try {
			Train present = station.getPresentTrain();
			if (present != null) return present;
		} catch (Throwable ignored) {
		}

		TrackGraph graph = null;
		PhysicsBogeyAxle axle = findAnchorAxle(anchorBogey);
		if (axle != null) graph = axle.getTrackGraph();

		if (graph != null) {
			for (Train train : Create.RAILWAYS.trains.values()) {
				if (train == null) continue;

				try {
					if (train.graph == graph && train.navigation != null && train.navigation.destination == station) {
						return train;
					}
				} catch (Throwable ignored) {
				}
			}
		}

		return null;
	}

	private void setSpeedMultiplier(float newMultiplier) {
		float diff = Math.abs(newMultiplier - currentSpeedMultiplier);
		float threshold = Math.max(0.05f, Math.abs(currentSpeedMultiplier) * 0.05f);

		boolean directionFlipped = (newMultiplier < 0) != (currentSpeedMultiplier < 0)
				&& Math.abs(newMultiplier) > 0.01f;

		if (diff > threshold || directionFlipped) {
			currentSpeedMultiplier = newMultiplier;

			if (level instanceof ServerLevel serverLevel) {
				if (getBlockState().getBlock() instanceof NavigationControllerBlock navBlock) {
					navBlock.detachKinetics(serverLevel, worldPosition, true);
				}
			}

			setChanged();
			sendData();
		}
	}

	private TrackNode findNearestNodeToStation(TrackGraph graph, GlobalStation station) {
		if (station.edgeLocation != null) {
			TrackNode n1 = graph.locateNode(station.edgeLocation.getFirst());
			TrackNode n2 = graph.locateNode(station.edgeLocation.getSecond());

			if (n1 != null && n2 != null) {
				if (station.assembling) {
					Vec3 stationPos = Vec3.atCenterOf(station.getBlockEntityPos());

					double d1 = n1.getLocation().getLocation().distanceToSqr(stationPos);
					double d2 = n2.getLocation().getLocation().distanceToSqr(stationPos);

					return d1 > d2 ? n1 : n2;
				}

				if (station.canApproachFrom(n2)) return n1;
				if (station.canApproachFrom(n1)) return n2;

				return n2;
			}
		}

		TrackNode nearest = null;
		double minDistSq = Double.MAX_VALUE;

		Vec3 stationPos = Vec3.atCenterOf(station.getBlockEntityPos());

		for (var loc : graph.getNodes()) {
			TrackNode node = graph.locateNode(loc);
			if (node == null) continue;

			double distSq = node.getLocation().getLocation().distanceToSqr(stationPos);

			if (distSq < minDistSq) {
				minDistSq = distSq;
				nearest = node;
			}
		}

		return nearest;
	}

	private List<TrackNode> findPath(TrackGraph graph, TrackNode startNode, TrackNode targetNode,
									 @Nullable TrackNode disallowFirstNode) {
		if (startNode == targetNode) return List.of(startNode);

		Map<TrackNode, TrackNode> cameFrom = new HashMap<>();
		Queue<TrackNode> queue = new LinkedList<>();

		queue.add(startNode);
		cameFrom.put(startNode, null);

		if (disallowFirstNode != null && disallowFirstNode != targetNode && disallowFirstNode != startNode) {
			cameFrom.put(disallowFirstNode, startNode);
		}

		while (!queue.isEmpty()) {
			TrackNode current = queue.poll();

			if (current == targetNode) {
				List<TrackNode> path = new ArrayList<>();

				TrackNode step = current;
				while (step != null) {
					path.add(0, step);
					step = cameFrom.get(step);
				}

				return path;
			}

			Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(current);
			if (connections != null) {
				for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
					TrackNode nextNode = entry.getKey();
					TrackEdge edge = entry.getValue();

					GlobalStation station = findStationOnEdge(graph, edge, null);
					if (station != null) {
						boolean movingTowards = edge.node2.equals(nextNode);
						if (!traversesStationCorrectly(edge, movingTowards, station, currentStation)) {
							continue;
						}
					}

					if (!cameFrom.containsKey(nextNode)) {
						cameFrom.put(nextNode, current);
						queue.add(nextNode);
					}
				}
			}
		}

		return Collections.emptyList();
	}

	private List<TrackNode> findPathAvoidingEdge(TrackGraph graph, TrackNode startNode, TrackNode targetNode,
												 TrackNode avoidA, TrackNode avoidB,
												 @Nullable TrackNode disallowFirstNode) {
		if (startNode == targetNode) return List.of(startNode);

		Map<TrackNode, TrackNode> cameFrom = new HashMap<>();
		Queue<TrackNode> queue = new LinkedList<>();

		queue.add(startNode);
		cameFrom.put(startNode, null);

		if (disallowFirstNode != null && disallowFirstNode != targetNode && disallowFirstNode != startNode) {
			cameFrom.put(disallowFirstNode, startNode);
		}

		while (!queue.isEmpty()) {
			TrackNode current = queue.poll();

			if (current == targetNode) {
				List<TrackNode> path = new ArrayList<>();

				TrackNode step = current;
				while (step != null) {
					path.add(0, step);
					step = cameFrom.get(step);
				}

				return path;
			}

			Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(current);
			if (connections != null) {
				for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
					TrackNode nextNode = entry.getKey();
					TrackEdge edge = entry.getValue();

					if ((current == avoidA && nextNode == avoidB) || (current == avoidB && nextNode == avoidA)) {
						continue;
					}

					GlobalStation station = findStationOnEdge(graph, edge, null);
					if (station != null) {
						boolean movingTowards = edge.node2.equals(nextNode);
						if (!traversesStationCorrectly(edge, movingTowards, station, currentStation)) {
							continue;
						}
					}

					if (!cameFrom.containsKey(nextNode)) {
						cameFrom.put(nextNode, current);
						queue.add(nextNode);
					}
				}
			}
		}

		return Collections.emptyList();
	}

	private double calculatePathDistance(TrackGraph graph, List<TrackNode> path, TravellingPoint start,
										 boolean movingTowardsNode2,
										 @Nullable GlobalStation targetStation,
										 @Nullable GlobalStation currentStation) {
		if (path.isEmpty() || start.edge == null) return 0;

		TrackEdge stationEdge = null;
		double stationPosOnEdge = 0;

		if (targetStation != null) {
			stationEdge = getStationEdge(graph, targetStation);

			if (stationEdge != null) {
				stationPosOnEdge = targetStation.getLocationOn(stationEdge);
			}
		}

		boolean suppressStationEdgeArrival = targetStation != null
				&& targetStation == currentStation
				&& !arrivedAtDestination;

		if (stationEdge != null && sameEdge(start.edge, stationEdge) && !suppressStationEdgeArrival) {
			double startPos = start.position;

			if (start.edge.node1 != stationEdge.node1) {
				startPos = start.edge.getLength() - start.position;
			}

			return Math.abs(startPos - stationPosOnEdge);
		}

		double distance = movingTowardsNode2 ? (start.edge.getLength() - start.position) : start.position;

		for (int i = 0; i < path.size() - 1; i++) {
			TrackNode p1 = path.get(i);
			TrackNode p2 = path.get(i + 1);

			TrackEdge edge = graph.getConnection(Couple.create(p1, p2));

			double edgeLength = (edge != null)
					? edge.getLength()
					: p1.getLocation().getLocation().distanceTo(p2.getLocation().getLocation());

			if (i == path.size() - 2 && stationEdge != null && sameEdge(edge, stationEdge)) {
				if (p1 == stationEdge.node1) {
					edgeLength = stationPosOnEdge;
				} else {
					edgeLength = edge.getLength() - stationPosOnEdge;
				}
			}

			distance += edgeLength;
		}

		if (stationEdge != null && !path.isEmpty()) {
			boolean lastEdgeIsStationEdge = false;

			if (path.size() >= 2) {
				TrackNode p1 = path.get(path.size() - 2);
				TrackNode p2 = path.get(path.size() - 1);

				TrackEdge lastEdge = graph.getConnection(Couple.create(p1, p2));
				lastEdgeIsStationEdge = sameEdge(lastEdge, stationEdge);
			}

			if (!lastEdgeIsStationEdge) {
				TrackNode last = path.get(path.size() - 1);

				if (last == stationEdge.node1) {
					distance += stationPosOnEdge;
				} else if (last == stationEdge.node2) {
					distance += stationEdge.getLength() - stationPosOnEdge;
				}
			}
		}

		return Math.max(0.0, distance);
	}

	private Vec3 getLookaheadTangent(TrackGraph graph, TravellingPoint start, List<TrackNode> path,
									 int startIdx, boolean movingTowardsNode2, double lookaheadDistance) {
		if (start.edge == null || path.isEmpty()) return Vec3.ZERO;

		if (startIdx < 0) startIdx = 0;

		if (startIdx < path.size() - 1) {
			TrackNode p1 = path.get(startIdx);
			TrackNode p2 = path.get(startIdx + 1);

			return p2.getLocation().getLocation()
					.subtract(p1.getLocation().getLocation())
					.normalize();
		}

		return movingTowardsNode2
				? start.edge.node2.getLocation().getLocation()
				.subtract(start.edge.node1.getLocation().getLocation())
				.normalize()
				: start.edge.node1.getLocation().getLocation()
				.subtract(start.edge.node2.getLocation().getLocation())
				.normalize();
	}

	@Nullable
	private GlobalStation findStationOnEdge(TrackGraph graph, TrackEdge edge, GlobalStation exclude) {
		for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
			if (station == exclude) continue;
			if (station.edgeLocation == null) continue;

			TrackNode sN1 = graph.locateNode(station.edgeLocation.getFirst());
			TrackNode sN2 = graph.locateNode(station.edgeLocation.getSecond());

			if (sN1 == null || sN2 == null) continue;

			if ((edge.node1 == sN1 && edge.node2 == sN2) || (edge.node1 == sN2 && edge.node2 == sN1)) {
				return station;
			}
		}

		return null;
	}

	private boolean pathRespectsAllStationDirections(TrackGraph graph, List<TrackNode> path,
													 TravellingPoint start, boolean movingTowardsNode2,
													 GlobalStation targetStation,
													 @Nullable GlobalStation currentStation) {
		if (path.isEmpty()) return true;

		TrackEdge currentEdge = start.edge;
		if (currentEdge == null) return true;

		GlobalStation station = findStationOnEdge(graph, currentEdge, targetStation);
		if (station != null && !traversesStationCorrectly(currentEdge, movingTowardsNode2, station, currentStation)) {
			return false;
		}

		TrackNode prevNode = movingTowardsNode2 ? currentEdge.node2 : currentEdge.node1;

		for (TrackNode nextNode : path) {
			if (prevNode.equals(nextNode)) continue;

			TrackEdge edge = graph.getConnection(Couple.create(prevNode, nextNode));
			if (edge == null) continue;

			GlobalStation edgeStation = findStationOnEdge(graph, edge, targetStation);
			if (edgeStation != null) {
				boolean movingTowards = edge.node2.equals(nextNode);

				if (!traversesStationCorrectly(edge, movingTowards, edgeStation, currentStation)) {
					return false;
				}
			}

			prevNode = nextNode;
		}

		return true;
	}

	private boolean traversesStationCorrectly(TrackEdge edge, boolean movingTowardsNode2,
											  GlobalStation station, @Nullable GlobalStation currentStation) {
		if (station == currentStation) return true;

		TrackNode headingTowards = movingTowardsNode2 ? edge.node2 : edge.node1;
		return station.canApproachFrom(headingTowards);
	}

	private Set<PhysicsBogeyBlockEntity> traverseConsist(PhysicsBogeyBlockEntity start) {
		Set<PhysicsBogeyBlockEntity> visited = new HashSet<>();
		Queue<PhysicsBogeyBlockEntity> queue = new LinkedList<>();

		queue.add(start);
		visited.add(start);

		while (!queue.isEmpty()) {
			PhysicsBogeyBlockEntity current = queue.poll();

			PhysicsBogeyBlockEntity front = current.getConnected(true);
			if (front != null && visited.add(front)) queue.add(front);

			PhysicsBogeyBlockEntity back = current.getConnected(false);
			if (back != null && visited.add(back)) queue.add(back);
		}

		return visited;
	}

	private void clearOverridesOnConsist() {
		PhysicsBogeyBlockEntity anchor = findAdjacentBogey();

		if (anchor != null) {
			for (PhysicsBogeyBlockEntity bogey : traverseConsist(anchor)) {
				bogey.clearNavigationOverride();
				bogey.clearNavigationBrakeOverride();
			}
		}
	}

	private void updateDockingConnectors(
			ServerLevel level,
			Set<PhysicsBogeyBlockEntity> consist,
			@Nullable GlobalStation station,
			boolean active
	) {
		if (!active) {
			deactivateDockingConnectors(level);
			return;
		}

		if (dockingConnectorsActive) {
			boolean anyValid = false;

			Iterator<ConnectorRef> it = activatedDockingConnectors.iterator();
			while (it.hasNext()) {
				ConnectorRef ref = it.next();
				BlockEntity be = ref.level().getBlockEntity(ref.pos());

				if (be instanceof DockingConnectorBlockEntity connector && !connector.isRemoved()) {
					setDockingConnectorPowered(connector, true);
					anyValid = true;
				} else {
					it.remove();
				}
			}

			if (anyValid) {
				return;
			}
		}
		Set<ConnectorRef> desired = new HashSet<>();

		for (DockingConnectorBlockEntity connector : collectTrainDockingConnectors(consist)) {
			if (connector.isRemoved()) {
				continue;
			}
			if (!(connector.getLevel() instanceof ServerLevel connectorLevel)) {
				continue;
			}

			desired.add(new ConnectorRef(connectorLevel, connector.getBlockPos()));
		}

		activatedDockingConnectors.clear();

		for (ConnectorRef ref : desired) {
			if (ref.level().getBlockEntity(ref.pos()) instanceof DockingConnectorBlockEntity connector) {
				activatedDockingConnectors.add(ref);
				setDockingConnectorPowered(connector, true);
			}
		}

		dockingConnectorsActive = !activatedDockingConnectors.isEmpty();
	}

	private List<DockingConnectorBlockEntity> collectTrainDockingConnectors(Set<PhysicsBogeyBlockEntity> consist) {
		Set<DockingConnectorBlockEntity> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> seenSubLevels = Collections.newSetFromMap(new IdentityHashMap<>());

		for (PhysicsBogeyBlockEntity bogey : consist) {
			Level beLevel = bogey.getLevel();
			if (beLevel == null) {
				continue;
			}

			Object subLevel = getSubLevel(beLevel, bogey.getBlockPos(), bogey);
			collectDockingConnectorsInSubLevel(
					subLevel,
					result,
					seenSubLevels,
					0
			);
		}

		return new ArrayList<>(result);
	}

	private void collectDockingConnectorsInSubLevel(
			Object subLevel,
			Set<DockingConnectorBlockEntity> out,
			Set<Object> seenSubLevels,
			int depth
	) {
		if (subLevel == null || depth > SUBLEVEL_SCAN_DEPTH || !seenSubLevels.add(subLevel)) {
			return;
		}

		for (BlockEntity be : getSubLevelBlockEntities(subLevel)) {
			if (be instanceof DockingConnectorBlockEntity connector) {
				if (!connector.isRemoved()) {
					out.add(connector);
				}
			}
		}

		for (Object linked : getLinkedSubLevels(subLevel)) {
			collectDockingConnectorsInSubLevel(
					linked,
					out,
					seenSubLevels,
					depth + 1
			);
		}
	}

	private void deactivateDockingConnectors(ServerLevel level) {
		for (ConnectorRef ref : activatedDockingConnectors) {
			if (ref.level().getBlockEntity(ref.pos()) instanceof DockingConnectorBlockEntity connector) {
				setDockingConnectorPowered(connector, false);
			}
		}

		activatedDockingConnectors.clear();
		dockingConnectorsActive = false;
	}

	private void setDockingConnectorPowered(DockingConnectorBlockEntity connector, boolean powered) {
		if (!(connector.getLevel() instanceof ServerLevel connectorLevel)) {
			return;
		}

		BlockPos pos = connector.getBlockPos();
		BlockState state = connectorLevel.getBlockState(pos);
		boolean changed = false;

		if (state.hasProperty(BlockStateProperties.POWERED)
				&& state.getValue(BlockStateProperties.POWERED) != powered) {
			connectorLevel.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), 3);
			changed = true;
		}

		if (connector.powered != powered) {
			connector.powered = powered;
			changed = true;
		}

		if (changed) {
			connector.setChanged();
			try {
				connector.sendData();
			} catch (Throwable ignored) {
			}
		}
	}

	private void updateCustomStationPresence(ServerLevel level, @Nullable GlobalStation station) {
		BlockPos newPos = station != null ? station.getBlockEntityPos() : null;

		if (java.util.Objects.equals(customStationPos, newPos)) {
			return;
		}

		if (customStationPos != null) {
			if (level.getBlockEntity(customStationPos) instanceof ICustomStationPresence presence) {
				presence.simurail$setCustomTrain(false);
			}

			customStationPos = null;
		}

		if (newPos != null) {
			if (level.getBlockEntity(newPos) instanceof ICustomStationPresence presence) {
				presence.simurail$setCustomTrain(true);
				customStationPos = newPos;
			}
		}
	}

	private record Target(
			Schedule schedule,
			ScheduleEntry entry,
			DestinationInstruction instruction,
			GlobalStation station
	) {}

	private record StationApproach(
			TrackNode approach,
			TrackNode depart
	) {}

	private record Candidate(
			boolean valid,
			boolean matchesDirection,
			double score,
			List<TrackNode> path
	) {}

	@Nullable
	private Target resolveDestination(ServerLevel level, PhysicsBogeyBlockEntity anchorBogey) {
		if (scheduleStack.isEmpty()) return null;

		CompoundTag scheduleTag = scheduleStack.get(AllDataComponents.TRAIN_SCHEDULE);
		if (scheduleTag == null) return null;

		Schedule schedule = Schedule.fromTag(level.registryAccess(), scheduleTag);
		if (schedule.entries.isEmpty()) return null;

		currentEntry = Math.floorMod(currentEntry, schedule.entries.size());

		ScheduleEntry entry = schedule.entries.get(currentEntry);

		if (!(entry.instruction instanceof DestinationInstruction destination)) {
			advanceEntry(schedule, level.registryAccess());
			return null;
		}

		BlockPos referencePos = anchorBogey != null ? anchorBogey.getBlockPos() : worldPosition;

		GlobalStation station = findMatchingStation(level, destination, referencePos);
		return station == null ? null : new Target(schedule, entry, destination, station);
	}

	@Nullable
	private GlobalStation findMatchingStation(ServerLevel level, DestinationInstruction destination, BlockPos referencePos) {
		lastMatchedStationName = null;

		String filter = destination.getFilterForRegex();
		if (filter == null || filter.isEmpty()) return null;

		Pattern pattern = null;

		try {
			pattern = Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
		} catch (PatternSyntaxException ignored) {
		}

		GlobalStation exactNearest = null;
		double exactNearestDistSq = Double.MAX_VALUE;

		GlobalStation fuzzyNearest = null;
		double fuzzyNearestDistSq = Double.MAX_VALUE;

		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
			for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
				if (!station.getBlockEntityDimension().equals(level.dimension())) continue;
				if (station.name == null) continue;

				boolean exactMatch = false;

				if (pattern != null) {
					exactMatch = pattern.matcher(station.name).matches();
				}

				if (!exactMatch) {
					exactMatch = station.name.equalsIgnoreCase(filter);
				}

				boolean containsMatch = station.name.toLowerCase().contains(filter.toLowerCase());

				if (!exactMatch && !containsMatch) continue;

				double distSq = station.getBlockEntityPos().distSqr(referencePos);

				if (exactMatch) {
					if (distSq < exactNearestDistSq) {
						exactNearestDistSq = distSq;
						exactNearest = station;
					}
				} else {
					if (distSq < fuzzyNearestDistSq) {
						fuzzyNearestDistSq = distSq;
						fuzzyNearest = station;
					}
				}
			}
		}

		if (exactNearest != null) {
			lastMatchedStationName = exactNearest.name;
			return exactNearest;
		}

		if (fuzzyNearest != null) {
			lastMatchedStationName = fuzzyNearest.name;
			return fuzzyNearest;
		}

		return null;
	}

	private boolean tickConditions(ServerLevel level, ScheduleEntry entry, @Nullable Train train,
								   GlobalStation station, Set<PhysicsBogeyBlockEntity> consist) {
		List<List<ScheduleWaitCondition>> columns = entry.conditions;

		if (columns.isEmpty()) {
			return true;
		}

		while (conditionProgress.size() < columns.size()) {
			conditionProgress.add(0);
			conditionContext.add(new CompoundTag());
		}

		boolean hasNonEmptyColumn = false;

		for (int i = 0; i < columns.size(); i++) {
			List<ScheduleWaitCondition> column = columns.get(i);

			if (column == null || column.isEmpty()) {
				continue;
			}

			hasNonEmptyColumn = true;
			int progress = conditionProgress.get(i);

			if (progress >= column.size()) {
				return true;
			}

			if (evaluateCondition(column.get(progress), level, conditionContext.get(i), train, station, consist)) {
				conditionContext.set(i, new CompoundTag());
				conditionProgress.set(i, progress + 1);

				if (progress + 1 >= column.size()) {
					return true;
				}
			}
		}

		return !hasNonEmptyColumn;
	}

	private boolean evaluateCondition(ScheduleWaitCondition condition, ServerLevel level, CompoundTag context,
									  @Nullable Train train, GlobalStation station,
									  Set<PhysicsBogeyBlockEntity> consist) {
		try {
			if (condition instanceof StationPoweredCondition) {
				return level.hasNeighborSignal(station.getBlockEntityPos());
			}

			if (condition instanceof CargoThresholdCondition cargo) {
				return evaluateCreateCargoCondition(cargo, level, consist);
			}

			String simpleName = condition.getClass().getSimpleName().toLowerCase(Locale.ROOT);

			if (condition instanceof ScheduledDelay || simpleName.contains("delay")) {
				return evaluateScheduledDelay(condition, level, context);
			}

			debugInfo = "Unsupported condition: " + condition.getClass().getSimpleName();
			return false;
		} catch (Throwable t) {
			debugInfo = "Condition error: " + t.getClass().getSimpleName();
			return false;
		}
	}

	private boolean evaluateScheduledDelay(ScheduleWaitCondition condition, ServerLevel level, CompoundTag context) {
		int total = getDelayTicks(condition, level);

		if (total < 0) {
			debugInfo = "Delay not parsed: " + condition.getClass().getSimpleName();
			return false;
		}

		if (total <= 0) {
			return true;
		}

		long now = level.getGameTime();

		if (!context.contains("SimuDelayStart", Tag.TAG_LONG)) {
			context.putLong("SimuDelayStart", now);
			debugInfo = String.format(
					"Delay: 0 / %d ticks (%.1fs)",
					total,
					total / 20.0f
			);
			return false;
		}

		long start = context.getLong("SimuDelayStart");
		long elapsed = now - start;

		if (elapsed < 0) {
			context.putLong("SimuDelayStart", now);
			elapsed = 0;
		}

		debugInfo = String.format(
				"Delay: %d / %d ticks (%.1fs / %.1fs)",
				Math.min(elapsed, total),
				total,
				elapsed / 20.0f,
				total / 20.0f
		);

		return elapsed >= total;
	}

	private int getDelayTicks(ScheduleWaitCondition condition, ServerLevel level) {
		if (condition instanceof ScheduledDelay delay) {
			try {
				return delay.totalWaitTicks();
			} catch (Throwable ignored) {
			}
		}

		for (String methodName : new String[]{
				"totalWaitTicks",
				"getTotalWaitTicks",
				"getDelayTicks",
				"getTicks",
				"getTotalTicks",
				"getDelay",
				"getTime",
				"getValue",
				"getAmount"
		}) {
			Object value = invokeNoArg(condition, methodName);
			if (value instanceof Number number) {
				return number.intValue();
			}
		}

		for (String fieldName : new String[]{
				"totalWaitTicks",
				"ticks",
				"delay",
				"time",
				"value",
				"amount"
		}) {
			Object value = getFieldValue(condition, fieldName);
			if (value instanceof Number number) {
				return number.intValue();
			}
		}

		CompoundTag tag = extractConditionTag(condition, level);
		if (tag != null) {
			for (String key : new String[]{
					"Ticks",
					"TotalTicks",
					"Delay",
					"Time",
					"Value",
					"Amount"
			}) {
				int value = readIntFromTag(tag, key);
				if (value != Integer.MIN_VALUE) {
					return value;
				}
			}

			int days = readIntFromTag(tag, "Days");
			int hours = readIntFromTag(tag, "Hours");
			int minutes = readIntFromTag(tag, "Minutes");
			int seconds = readIntFromTag(tag, "Seconds");
			int ticks = readIntFromTag(tag, "Ticks");

			if (days != Integer.MIN_VALUE
					|| hours != Integer.MIN_VALUE
					|| minutes != Integer.MIN_VALUE
					|| seconds != Integer.MIN_VALUE
					|| ticks != Integer.MIN_VALUE) {

				int total = 0;

				if (days != Integer.MIN_VALUE) {
					total += days * 24000;
				}

				if (hours != Integer.MIN_VALUE) {
					total += hours * 1000;
				}

				if (minutes != Integer.MIN_VALUE) {
					total += minutes * 20;
				}

				if (seconds != Integer.MIN_VALUE) {
					total += seconds * 20;
				}

				if (ticks != Integer.MIN_VALUE) {
					total += ticks;
				}

				return total;
			}
		}

		return -1;
	}

	private int readIntFromTag(CompoundTag tag, String key) {
		if (hasNumericTag(tag, key)) {
			return tag.getInt(key);
		}

		if (tag.contains(key, Tag.TAG_STRING)) {
			try {
				return Integer.parseInt(tag.getString(key).trim());
			} catch (Throwable ignored) {
			}
		}

		return Integer.MIN_VALUE;
	}

	private boolean evaluateCreateCargoCondition(CargoThresholdCondition cargo, ServerLevel level,
												 Set<PhysicsBogeyBlockEntity> consist) {
		CargoThresholdCondition.Ops op;
		int threshold;
		int measure;

		try {
			op = cargo.getOperator();
			threshold = cargo.getThreshold();
			measure = cargo.getMeasure();
		} catch (Throwable t) {
			debugInfo = "Cargo condition API error: " + t.getClass().getSimpleName();
			return false;
		}

		if (op == null) {
			debugInfo = "Cargo operator not parsed";
			return false;
		}

		Object filter = extractConditionFilter(cargo, level);
		boolean fluid = isFluidCargoCondition(cargo, filter);
		String filterText = filterDescription(filter, level);

		if (fluid) {
			int current = countFluidsAll(null, consist, filter);
			int target = threshold;

			if (measure == 1 || summaryContains(cargo, "bucket")) {
				target *= 1000;
			}

			debugInfo = String.format(
					"Fluid cargo: %d / %d mB %s filter=%s",
					current,
					target,
					op.formatted,
					filterText
			);

			return op.test(current, target);
		}

		int currentItems = countItemsAll(null, consist, filter, false);

		boolean stacks = measure == 1 || summaryContains(cargo, "stack");

		if (stacks) {
			int stackSize = Math.max(1, getFilterStackSize(filter, level));
			int currentStacks = currentItems <= 0 ? 0 : 1 + (currentItems - 1) / stackSize;

			debugInfo = String.format(
					"Stack cargo: %d / %d stacks (%d items, stack size %d) %s filter=%s",
					currentStacks,
					threshold,
					currentItems,
					stackSize,
					op.formatted,
					filterText
			);

			return op.test(currentStacks, threshold);
		}

		debugInfo = String.format(
				"Item cargo: %d / %d %s filter=%s",
				currentItems,
				threshold,
				op.formatted,
				filterText
		);

		return op.test(currentItems, threshold);
	}

	private boolean isFluidCargoCondition(CargoThresholdCondition cargo, @Nullable Object filter) {
		String name = cargo.getClass().getSimpleName().toLowerCase(Locale.ROOT);

		if (name.contains("fluid")) {
			return true;
		}

		if (filter instanceof FluidStack) {
			return true;
		}

		if (filter != null && filter.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("fluid")) {
			return true;
		}

		return summaryContains(cargo, "bucket") || summaryContains(cargo, "mb");
	}

	private boolean summaryContains(CargoThresholdCondition cargo, String needle) {
		try {
			Component text = cargo.getSummary().getSecond();
			return text != null && text.getString()
					.toLowerCase(Locale.ROOT)
					.contains(needle.toLowerCase(Locale.ROOT));
		} catch (Throwable ignored) {
			return false;
		}
	}

	@Nullable
	private Object extractConditionFilter(ScheduleWaitCondition condition, ServerLevel level) {
		for (String methodName : new String[]{
				"getFilter",
				"getFilterStack",
				"getFilterItemStack",
				"getItemFilter",
				"getFluidFilter",
				"getFilterFluid",
				"getFluidStack"
		}) {
			Object value = unwrapOptional(invokeNoArg(condition, methodName));

			if (value == null) {
				continue;
			}

			if (value instanceof ItemStack stack && stack.isEmpty()) {
				continue;
			}

			if (value instanceof FluidStack fluid && fluid.isEmpty()) {
				continue;
			}

			return value;
		}

		Object directData = getFieldValue(condition, "data");
		if (directData instanceof CompoundTag dataTag) {
			Object parsed = parseConditionFilterFromTag(dataTag, level);
			if (parsed != null) {
				return parsed;
			}
		}

		CompoundTag tag = extractConditionTag(condition, level);
		if (tag != null) {
			Object parsed = parseConditionFilterFromTag(tag, level);
			if (parsed != null) {
				return parsed;
			}
		}

		return extractItemFilterObject(condition, level);
	}

	@Nullable
	private Object parseConditionFilterFromTag(CompoundTag tag, ServerLevel level) {
		Object createFilter = parseCreateFilterTag(tag, level);
		if (createFilter != null) {
			return createFilter;
		}

		Object fluid = parseFluidFilterTag(tag, level);
		if (fluid != null) {
			return fluid;
		}

		ItemStack item = parseFilterTag(tag, level);
		if (item != null && !item.isEmpty()) {
			return item;
		}

		return null;
	}

	private int getFilterStackSize(@Nullable Object filter, ServerLevel level) {
		ItemStack stack = resolveFilterItemStack(filter, level);

		if (stack != null && !stack.isEmpty()) {
			return Math.max(1, stack.getMaxStackSize());
		}

		if (filter != null) {
			for (String methodName : new String[]{
					"getMaxStackSize",
					"getStackSize",
					"getCapacity"
			}) {
				Object value = invokeNoArg(filter, methodName);

				if (value instanceof Number number && number.intValue() > 0) {
					return number.intValue();
				}
			}
		}

		return 64;
	}

	@Nullable
	private ItemStack resolveFilterItemStack(@Nullable Object filter, ServerLevel level) {
		return resolveFilterItemStack(filter, level, 0);
	}

	@Nullable
	private ItemStack resolveFilterItemStack(@Nullable Object filter, ServerLevel level, int depth) {
		if (filter == null || depth > 3) {
			return null;
		}

		if (filter instanceof ItemStack stack) {
			if (stack.isEmpty()) {
				return null;
			}

			try {
				String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));

				if (id.toLowerCase().contains("filter")) {
					CompoundTag tag = getStackTag(stack, level);

					if (tag != null) {
						ItemStack parsed = parseFilterTag(tag, level);

						if (parsed != null && !parsed.isEmpty()) {
							return resolveFilterItemStack(parsed, level, depth + 1);
						}
					}
				}
			} catch (Throwable ignored) {
			}

			return stack;
		}

		if (filter instanceof IItemHandler handler) {
			if (handler.getSlots() > 0) {
				ItemStack stack = handler.getStackInSlot(0);

				if (stack != null && !stack.isEmpty()) {
					return resolveFilterItemStack(stack, level, depth + 1);
				}
			}
		}

		if (filter instanceof Container container) {
			if (container.getContainerSize() > 0) {
				ItemStack stack = container.getItem(0);

				if (stack != null && !stack.isEmpty()) {
					return resolveFilterItemStack(stack, level, depth + 1);
				}
			}
		}

		Object underlying = getUnderlyingFilterStack(filter);

		if (underlying instanceof ItemStack stack && underlying != filter) {
			return resolveFilterItemStack(stack, level, depth + 1);
		}

		for (String getter : new String[]{
				"getItemStack",
				"getStack",
				"getItem",
				"getFilterStack",
				"getFilter"
		}) {
			Object value = unwrapOptional(invokeNoArg(filter, getter));

			if (value instanceof ItemStack stack && value != filter && !stack.isEmpty()) {
				return resolveFilterItemStack(stack, level, depth + 1);
			}
		}

		return null;
	}

	private String filterDescription(@Nullable Object filter, @Nullable ServerLevel level) {
		String desc = describeFilterObject(filter, level, 0);
		return desc == null || desc.isEmpty() ? "any" : desc;
	}

	@Nullable
	private String describeFilterObject(@Nullable Object obj, @Nullable ServerLevel level, int depth) {
		if (obj == null || depth > 4) return null;

		if (obj instanceof ItemStack stack) {
			return describeItemStack(stack, level, depth);
		}

		if (obj instanceof FluidStack fluid) {
			return describeFluidStack(fluid);
		}

		if (obj instanceof Component component) {
			return sanitizeDescription(component.getString());
		}

		if (obj instanceof String s) {
			return sanitizeDescription(s);
		}

		if (obj instanceof IItemHandler handler) {
			for (int i = 0; i < handler.getSlots(); i++) {
				ItemStack stack = handler.getStackInSlot(i);
				if (!stack.isEmpty()) {
					return describeItemStack(stack, level, depth + 1);
				}
			}
			return "empty";
		}

		if (obj instanceof Container container) {
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack stack = container.getItem(i);
				if (stack != null && !stack.isEmpty()) {
					return describeItemStack(stack, level, depth + 1);
				}
			}
			return "empty";
		}

		for (String getter : new String[]{
				"getFilterDescription", "getDescription", "getDisplayName",
				"getFilter", "getItemStack", "getStack", "getItem",
				"getFilterStack", "getFilterItemStack", "getFilterItem",
				"getFluidStack", "getFluid", "getFilterFluid"
		}) {
			Object ret = unwrapOptional(invokeNoArg(obj, getter));

			if (ret != null && ret != obj) {
				String desc = describeFilterObject(ret, level, depth + 1);
				if (desc != null) return desc;
			}
		}

		try {
			for (Method method : obj.getClass().getMethods()) {
				if (method.getParameterCount() != 0) continue;
				if (Modifier.isStatic(method.getModifiers())) continue;

				String name = method.getName().toLowerCase();
				if (name.equals("getclass")) continue;

				int score = descriptionMethodNameScore(name);
				if (score <= 0) continue;

				Class<?> retType = method.getReturnType();
				if (retType == void.class || retType.isPrimitive()) continue;
				if (Number.class.isAssignableFrom(retType)) continue;
				if (retType == Boolean.class || retType == Character.class) continue;

				trySetAccessible(method);

				Object ret = unwrapOptional(method.invoke(obj));
				if (ret == null || ret == obj) continue;

				String desc = describeFilterObject(ret, level, depth + 1);
				if (desc != null) return desc;
			}
		} catch (Throwable ignored) {
		}

		Class<?> c = obj.getClass();
		while (c != null && c != Object.class) {
			for (Field field : c.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;

				String name = field.getName().toLowerCase();
				int score = descriptionFieldNameScore(name);
				if (score <= 0) continue;

				trySetAccessible(field);

				try {
					Object value = field.get(obj);
					if (value == null || value == obj) continue;

					String desc = describeFilterObject(value, level, depth + 1);
					if (desc != null) return desc;
				} catch (Throwable ignored) {
				}
			}
			c = c.getSuperclass();
		}

		return usefulToString(obj);
	}

	private String describeItemStack(ItemStack stack, @Nullable ServerLevel level, int depth) {
		if (stack.isEmpty()) return "empty";

		try {
			String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
			int count = stack.getCount();

			if (level != null && depth < 3 && id.toLowerCase().contains("filter")) {
				CompoundTag tag = getStackTag(stack, level);

				if (tag != null) {
					String inner = describeItemFilterTag(tag, level, depth + 1);
					if (inner == null) inner = describeFluidFilterTag(tag, level);
					if (inner != null && !inner.isEmpty()) {
						return inner;
					}
				}
			}

			String readableName = getReadableItemName(stack, id);

			if (count != 1) {
				return readableName + " x" + count;
			}

			return readableName;
		} catch (Throwable ignored) {
			return "unknown item";
		}
	}

	private String getReadableItemName(ItemStack stack, String fallbackId) {
		try {
			Component hoverName = stack.getHoverName();
			if (hoverName != null) {
				String name = hoverName.getString();
				if (name != null && !name.isEmpty() && !name.equals(fallbackId)) {
					return name;
				}
			}
		} catch (Throwable ignored) {
		}

		try {
			String descId = stack.getItem().getDescriptionId(stack);
			Component translatable = Component.translatable(descId);
			String name = translatable.getString();
			if (name != null && !name.isEmpty() && !name.equals(descId)) {
				return name;
			}
		} catch (Throwable ignored) {
		}

		return prettifyId(fallbackId);
	}

	private String prettifyId(String id) {
		String name = id;
		int colon = name.indexOf(':');
		if (colon >= 0) {
			String namespace = name.substring(0, colon);
			if (namespace.equals("minecraft")) {
				name = name.substring(colon + 1);
			}
		}

		name = name.replace('/', ' ').replace('_', ' ');
		StringBuilder sb = new StringBuilder();
		boolean capitalizeNext = true;

		for (char ch : name.toCharArray()) {
			if (ch == ' ') {
				sb.append(' ');
				capitalizeNext = true;
			} else if (capitalizeNext) {
				sb.append(Character.toUpperCase(ch));
				capitalizeNext = false;
			} else {
				sb.append(ch);
			}
		}

		return sb.toString();
	}

	private String describeFluidStack(FluidStack stack) {
		if (stack.isEmpty()) return "empty fluid";

		try {
			String id = String.valueOf(BuiltInRegistries.FLUID.getKey(stack.getFluid()));
			int amount = stack.getAmount();

			String readableName = prettifyId(id);

			if (amount != 0 && amount != 1000) {
				return readableName + " x" + amount + "mB";
			}

			return readableName;
		} catch (Throwable ignored) {
			return "unknown fluid";
		}
	}

	@Nullable
	private CompoundTag getStackTag(ItemStack stack, Level saveLevel) {
		try {
			Object tag = invokeNoArg(stack, "getTag");
			if (tag instanceof CompoundTag compound) return compound;
		} catch (Throwable ignored) {
		}

		CompoundTag saved = saveStackToTag(stack, saveLevel);

		if (saved != null) {
			if (saved.contains("tag", Tag.TAG_COMPOUND)) return saved.getCompound("tag");
			if (saved.contains("components", Tag.TAG_COMPOUND)) return saved.getCompound("components");
			return saved;
		}

		return null;
	}

	@Nullable
	private String describeItemFilterTag(CompoundTag tag, ServerLevel level, int depth) {
		for (String key : new String[]{
				"Filter", "Item", "ItemStack", "Stack",
				"ItemFilter", "FilterItem", "FilterItemStack"
		}) {
			if (!tag.contains(key)) continue;

			Tag sub = tag.get(key);
			if (sub == null) continue;

			try {
				Optional<ItemStack> parsed = ItemStack.parse(level.registryAccess(), sub);
				if (parsed.isPresent() && !parsed.get().isEmpty()) {
					return describeItemStack(parsed.get(), level, depth + 1);
				}
			} catch (Throwable ignored) {
			}

			if (sub instanceof CompoundTag compound && compound.contains("id")) {
				String id = compound.getString("id");
				int count = 1;

				if (compound.contains("Count", Tag.TAG_BYTE)) {
					count = compound.getByte("Count");
				} else if (compound.contains("count")) {
					count = compound.getInt("count");
				}

				if (!id.isEmpty()) {
					String readable = prettifyId(id);
					return count != 1 ? readable + " x" + count : readable;
				}
			}
		}

		return null;
	}

	@Nullable
	private String describeFluidFilterTag(CompoundTag tag, ServerLevel level) {
		try {
			Object parsed = parseFluidFilterTag(tag, level);
			if (parsed instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
				return describeFluidStack(fluidStack);
			}
		} catch (Throwable ignored) {
		}

		for (String key : new String[]{"Fluid", "FluidStack", "Filter", "Stack", "FluidFilter"}) {
			if (!tag.contains(key, Tag.TAG_COMPOUND)) continue;

			CompoundTag compound = tag.getCompound(key);

			String id = null;
			if (compound.contains("FluidName")) id = compound.getString("FluidName");
			else if (compound.contains("Name")) id = compound.getString("Name");

			if (id != null && !id.isEmpty()) {
				int amount = 0;
				if (compound.contains("Amount")) amount = compound.getInt("Amount");
				else if (compound.contains("amount")) amount = compound.getInt("amount");

				String readable = prettifyId(id);
				return amount != 0 && amount != 1000
						? readable + " x" + amount + "mB"
						: readable;
			}
		}

		return null;
	}

	@Nullable
	private String sanitizeDescription(@Nullable String s) {
		if (s == null) return null;
		s = s.trim();
		if (s.isEmpty()) return null;
		if (s.length() > 120) s = s.substring(0, 117) + "...";
		if (s.matches("^[A-Za-z0-9_$.]+@[0-9a-fA-F]+$")) return null;

		String lower = s.toLowerCase();
		if ((lower.startsWith("com.") || lower.startsWith("net.") || lower.startsWith("dev.") || lower.startsWith("org."))
				&& !s.contains(":") && !s.contains(" ") && !s.contains("=") && !s.contains("{")) {
			return null;
		}

		return s;
	}

	@Nullable
	private String usefulToString(Object obj) {
		try {
			String s = String.valueOf(obj);
			if (s == null) return null;

			String className = obj.getClass().getName();
			String simpleName = obj.getClass().getSimpleName();

			if (s.equals(className) || s.equals(simpleName)) return null;
			if (s.contains("@") && !s.contains("{") && !s.contains("=") && !s.contains(":") && !s.contains(" ")) return null;

			if (s.startsWith(className) || s.startsWith(simpleName)) {
				int brace = s.indexOf('{');
				if (brace >= 0 && s.endsWith("}")) {
					String inner = s.substring(brace + 1, s.length() - 1).trim();
					if (!inner.isEmpty()) return sanitizeDescription(inner);
				}

				int bracket = s.indexOf('[');
				if (bracket >= 0 && s.endsWith("]")) {
					String inner = s.substring(bracket + 1, s.length() - 1).trim();
					if (!inner.isEmpty()) return sanitizeDescription(inner);
				}
			}

			return sanitizeDescription(s);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private int descriptionMethodNameScore(String n) {
		int score = 0;
		if (n.contains("description")) score += 100;
		if (n.contains("displayname") || n.contains("hovername") || n.contains("tooltip") || n.contains("label")) score += 90;
		if (n.contains("filter")) score += 80;
		if (n.contains("fluidstack") || n.contains("fluid")) score += 75;
		if (n.contains("itemstack") || n.contains("stack")) score += 70;
		if (n.contains("item")) score += 60;
		if (n.contains("name") || n.contains("text") || n.contains("string") || n.contains("component")) score += 40;
		if (n.contains("icon") || n.contains("ghost") || n.contains("animation")) score -= 100;
		return score;
	}

	private int descriptionFieldNameScore(String n) {
		int score = 0;
		if (n.contains("description")) score += 100;
		if (n.contains("displayname") || n.contains("hovername") || n.contains("tooltip") || n.contains("label")) score += 90;
		if (n.contains("filter")) score += 80;
		if (n.contains("fluidstack") || n.contains("fluid")) score += 75;
		if (n.contains("itemstack") || n.contains("stack")) score += 70;
		if (n.contains("item")) score += 60;
		if (n.contains("name") || n.contains("text") || n.contains("string") || n.contains("component")) score += 40;
		if (n.contains("icon") || n.contains("ghost") || n.contains("animation")) score -= 100;
		return score;
	}

	private boolean hasNumericTag(CompoundTag tag, String key) {
		return tag.contains(key, Tag.TAG_BYTE)
				|| tag.contains(key, Tag.TAG_SHORT)
				|| tag.contains(key, Tag.TAG_INT)
				|| tag.contains(key, Tag.TAG_LONG)
				|| tag.contains(key, Tag.TAG_FLOAT)
				|| tag.contains(key, Tag.TAG_DOUBLE);
	}

	@Nullable
	private CompoundTag extractConditionTag(ScheduleWaitCondition condition, ServerLevel level) {
		String[] methodNames = {
				"write",
				"save",
				"serializeNBT",
				"toNbt",
				"toTag",
				"writeToNBT",
				"saveToNBT"
		};

		Object provider = level.registryAccess();

		Class<?> c = condition.getClass();
		while (c != null && c != Object.class) {
			Set<Method> methods = new LinkedHashSet<>();
			methods.addAll(Arrays.asList(c.getMethods()));
			methods.addAll(Arrays.asList(c.getDeclaredMethods()));

			for (Method method : methods) {
				if (Modifier.isStatic(method.getModifiers())) continue;

				boolean wanted = false;

				for (String name : methodNames) {
					if (name.equals(method.getName())) {
						wanted = true;
						break;
					}
				}

				if (!wanted) continue;

				trySetAccessible(method);

				Class<?>[] params = method.getParameterTypes();

				try {
					if (params.length == 0) {
						Object ret = method.invoke(condition);

						if (ret instanceof CompoundTag tag) {
							return tag;
						}
					} else if (params.length == 1) {
						if (params[0].isAssignableFrom(CompoundTag.class)) {
							CompoundTag tag = new CompoundTag();
							Object ret = method.invoke(condition, tag);

							if (ret instanceof CompoundTag returned) {
								return returned;
							}

							return tag;
						}

						if (provider != null && params[0].isAssignableFrom(provider.getClass())) {
							Object ret = method.invoke(condition, provider);

							if (ret instanceof CompoundTag tag) {
								return tag;
							}
						}
					} else if (params.length == 2) {
						boolean p0Tag = params[0].isAssignableFrom(CompoundTag.class);
						boolean p1Tag = params[1].isAssignableFrom(CompoundTag.class);

						boolean p0Provider = provider != null && params[0].isAssignableFrom(provider.getClass());
						boolean p1Provider = provider != null && params[1].isAssignableFrom(provider.getClass());

						if (p0Tag && p1Provider) {
							CompoundTag tag = new CompoundTag();
							Object ret = method.invoke(condition, tag, provider);

							if (ret instanceof CompoundTag returned) {
								return returned;
							}

							return tag;
						}

						if (p1Tag && p0Provider) {
							CompoundTag tag = new CompoundTag();
							Object ret = method.invoke(condition, provider, tag);

							if (ret instanceof CompoundTag returned) {
								return returned;
							}

							return tag;
						}
					}
				} catch (Throwable ignored) {
				}
			}

			c = c.getSuperclass();
		}

		return null;
	}

	@Nullable
	private Object parseCreateFilterTag(CompoundTag tag, ServerLevel level) {
		String[] classNames = {
				"com.simibubi.create.content.logistics.filter.FilterItemStack",
				"com.simibubi.create.content.logistics.filter.FilterItemStack$FilterItemStack",
				"com.simibubi.create.content.logistics.filter.ItemFilterStack"
		};

		for (String className : classNames) {
			try {
				Class<?> clazz = Class.forName(className);

				for (String key : new String[]{
						"Filter",
						"Item",
						"ItemStack",
						"Stack",
						"ItemFilter",
						"FilterItem",
						"FilterItemStack"
				}) {
					if (!tag.contains(key)) continue;

					Tag sub = tag.get(key);
					if (sub == null) continue;

					Object parsed = tryStaticFilterParse(clazz, sub, level);
					if (parsed != null) return parsed;

					if (sub instanceof CompoundTag compound) {
						Object fromTag = tryStaticFilterFromTag(clazz, compound, level);
						if (fromTag != null) return fromTag;
					}
				}

				Object whole = tryStaticFilterFromTag(clazz, tag, level);
				if (whole != null) return whole;
			} catch (Throwable ignored) {
			}
		}

		return null;
	}

	@Nullable
	private Object tryStaticFilterParse(Class<?> clazz, Tag tag, ServerLevel level) {
		String[] names = {
				"parse",
				"fromTag",
				"fromNbt",
				"deserializeNBT",
				"of",
				"from",
				"fromStackTag",
				"fromFilterTag"
		};

		Object provider = level.registryAccess();

		for (Method method : clazz.getMethods()) {
			if (!Modifier.isStatic(method.getModifiers())) continue;

			boolean nameOk = false;

			for (String name : names) {
				if (name.equals(method.getName())) {
					nameOk = true;
					break;
				}
			}

			if (!nameOk) continue;

			Class<?>[] params = method.getParameterTypes();

			try {
				if (params.length == 1 && params[0].isAssignableFrom(tag.getClass())) {
					Object ret = unwrapOptional(method.invoke(null, tag));
					if (ret != null) return ret;
				}

				if (params.length == 2) {
					boolean p0Provider = provider != null && params[0].isAssignableFrom(provider.getClass());
					boolean p1Provider = provider != null && params[1].isAssignableFrom(provider.getClass());

					boolean p0Tag = params[0].isAssignableFrom(tag.getClass());
					boolean p1Tag = params[1].isAssignableFrom(tag.getClass());

					if (p0Provider && p1Tag) {
						Object ret = unwrapOptional(method.invoke(null, provider, tag));
						if (ret != null) return ret;
					} else if (p1Provider && p0Tag) {
						Object ret = unwrapOptional(method.invoke(null, tag, provider));
						if (ret != null) return ret;
					}
				}
			} catch (Throwable ignored) {
			}
		}

		try {
			Object ret = unwrapOptional(clazz.getConstructor(Tag.class).newInstance(tag));
			if (ret != null) return ret;
		} catch (Throwable ignored) {
		}

		try {
			Object ret = unwrapOptional(clazz.getConstructor(CompoundTag.class).newInstance(tag));
			if (ret != null) return ret;
		} catch (Throwable ignored) {
		}

		return null;
	}

	@Nullable
	private Object tryStaticFilterFromTag(Class<?> clazz, CompoundTag tag, ServerLevel level) {
		return tryStaticFilterParse(clazz, tag, level);
	}

	@Nullable
	private Object parseFluidFilterTag(CompoundTag tag, ServerLevel level) {
		for (String key : new String[]{"Filter", "Fluid", "FluidStack", "Stack", "FluidFilter"}) {
			if (!tag.contains(key)) continue;

			Tag sub = tag.get(key);
			if (sub == null) continue;

			Object provider = level.registryAccess();

			for (Method m : FluidStack.class.getMethods()) {
				if (!Modifier.isStatic(m.getModifiers())) continue;
				if (!m.getName().equals("parse") || m.getParameterCount() != 2) continue;

				Class<?>[] p = m.getParameterTypes();

				try {
					boolean p0Provider = provider != null && p[0].isAssignableFrom(provider.getClass());
					boolean p1Provider = provider != null && p[1].isAssignableFrom(provider.getClass());

					boolean p0Tag = p[0].isAssignableFrom(sub.getClass());
					boolean p1Tag = p[1].isAssignableFrom(sub.getClass());

					if (p0Provider && p1Tag) {
						Object ret = unwrapOptional(m.invoke(null, provider, sub));
						if (ret != null) return ret;
					} else if (p1Provider && p0Tag) {
						Object ret = unwrapOptional(m.invoke(null, sub, provider));
						if (ret != null) return ret;
					}
				} catch (Throwable ignored) {
				}
			}

			if (sub instanceof CompoundTag compound) {
				for (Method m : FluidStack.class.getMethods()) {
					if (!Modifier.isStatic(m.getModifiers())) continue;
					if (m.getParameterCount() != 1) continue;

					String name = m.getName();

					if (!name.equals("loadFluidStackFromNBT")
							&& !name.equals("load")
							&& !name.equals("fromNbt")
							&& !name.equals("fromTag")) {
						continue;
					}

					if (!m.getParameterTypes()[0].isAssignableFrom(CompoundTag.class)) continue;

					try {
						Object ret = unwrapOptional(m.invoke(null, compound));
						if (ret != null) return ret;
					} catch (Throwable ignored) {
					}
				}
			}
		}

		return null;
	}

	@Nullable
	private Object extractItemFilterObject(ScheduleWaitCondition condition, ServerLevel level) {
		Object bestFilter = null;
		int bestScore = Integer.MIN_VALUE;

		Class<?> c = condition.getClass();
		while (c != null && c != Object.class) {
			for (Field field : c.getDeclaredFields()) {
				int mods = field.getModifiers();

				if (Modifier.isStatic(mods) || field.isSynthetic()) {
					continue;
				}

				trySetAccessible(field);

				String fieldName = field.getName().toLowerCase();

				try {
					Object value = field.get(condition);
					if (value == null) continue;

					if (isCreateFilterObject(value)) {
						int score = scoreFilterFieldName(fieldName) + 1000;

						if (score > bestScore) {
							bestScore = score;
							bestFilter = value;
						}
					}

					if (value instanceof ItemStack stack) {
						int score = scoreFilterFieldName(fieldName);

						if (score > bestScore) {
							bestScore = score;
							bestFilter = stack;
						}
					}

					if (value instanceof IItemHandler handler && handler.getSlots() > 0) {
						ItemStack stack = handler.getStackInSlot(0);

						if (stack != null) {
							int score = scoreFilterFieldName(fieldName) - 50;

							if (score > bestScore) {
								bestScore = score;
								bestFilter = stack;
							}
						}
					}

					for (String getter : new String[]{
							"getFilter",
							"getItemStack",
							"getStack",
							"getItem",
							"getFilterStack",
							"getItemFilter"
					}) {
						Object filterObj = unwrapOptional(invokeNoArg(value, getter));
						if (filterObj == null) continue;

						int score = scoreFilterGetterName(getter);

						if (isCreateFilterObject(filterObj)) {
							score += 1000;

							if (score > bestScore) {
								bestScore = score;
								bestFilter = filterObj;
							}
						} else if (filterObj instanceof ItemStack stack) {
							if (score > bestScore) {
								bestScore = score;
								bestFilter = stack;
							}
						} else if (filterObj instanceof IItemHandler handler && handler.getSlots() > 0) {
							ItemStack stack = handler.getStackInSlot(0);

							if (stack != null) {
								score -= 50;

								if (score > bestScore) {
									bestScore = score;
									bestFilter = stack;
								}
							}
						}
					}
				} catch (Throwable ignored) {
				}
			}

			c = c.getSuperclass();
		}

		if (bestFilter != null && (bestScore >= 0 || isCreateFilterObject(bestFilter))) {
			return bestFilter;
		}

		CompoundTag tag = extractConditionTag(condition, level);
		if (tag != null) {
			Object createFilter = parseCreateFilterTag(tag, level);
			if (createFilter != null) {
				return createFilter;
			}

			ItemStack parsed = parseFilterTag(tag, level);
			if (parsed != null) {
				return parsed;
			}
		}

		return null;
	}

	private int scoreFilterFieldName(String name) {
		int score = 0;

		if (name.contains("filter")) score += 100;
		if (name.contains("cargo")) score += 15;
		if (name.contains("item")) score += 20;
		if (name.contains("stack")) score += 10;

		if (name.contains("icon") || name.contains("display") || name.contains("ghost") || name.contains("animation")) {
			score -= 100;
		}

		return score;
	}

	private int scoreFilterGetterName(String name) {
		String n = name.toLowerCase();

		int score = 0;

		if (n.contains("filter")) score += 100;
		if (n.contains("itemstack")) score += 30;
		if (n.contains("stack")) score += 20;
		if (n.contains("item")) score += 10;

		if (n.contains("icon") || n.contains("display") || n.contains("ghost")) {
			score -= 100;
		}

		return score;
	}

	private boolean isCreateFilterObject(Object obj) {
		if (obj == null || obj instanceof ItemStack) return false;

		String name = obj.getClass().getName().toLowerCase();

		if (name.contains("filteritemstack") || name.contains("itemfilter")) {
			return true;
		}

		if (name.contains("filter")) {
			return hasFilterMatchMethod(obj);
		}

		return false;
	}

	private boolean hasFilterMatchMethod(Object obj) {
		String[] names = {
				"test",
				"matches",
				"matchesItem",
				"matchesStack",
				"itemMatches",
				"testStack",
				"testItem",
				"accepts",
				"isValid",
				"filterTest",
				"matchesFilter",
				"testFilter",
				"matchesItemStack",
				"testItemStack",
				"match",
				"apply"
		};

		for (Method method : obj.getClass().getMethods()) {
			String methodName = method.getName();

			for (String name : names) {
				if (methodName.equals(name) && (method.getParameterCount() == 1 || method.getParameterCount() == 2)) {
					return true;
				}
			}
		}

		return false;
	}

	@Nullable
	private Object tryWrapAsCreateFilter(ItemStack stack) {
		if (stack.isEmpty()) return null;

		String[] classNames = {
				"com.simibubi.create.content.logistics.filter.FilterItemStack",
				"com.simibubi.create.content.logistics.filter.FilterItemStack$FilterItemStack",
				"com.simibubi.create.content.logistics.filter.ItemFilterStack",
				"com.simibubi.create.content.logistics.filter.FilterItem",
				"com.simibubi.create.content.logistics.filter.FilterItemStack$Impl",
				"com.simibubi.create.content.logistics.filter.FilterItemStack$Simple"
		};

		String[] methodNames = {
				"of",
				"fromStack",
				"fromItemStack",
				"ofStack",
				"from",
				"create",
				"fromFilterStack",
				"wrap"
		};

		Level currentLevel = level;
		Object provider = currentLevel != null ? currentLevel.registryAccess() : null;

		for (String className : classNames) {
			try {
				Class<?> clazz = Class.forName(className);

				Set<Method> methods = new LinkedHashSet<>();
				methods.addAll(Arrays.asList(clazz.getMethods()));
				methods.addAll(Arrays.asList(clazz.getDeclaredMethods()));

				for (Method method : methods) {
					if (!Modifier.isStatic(method.getModifiers())) continue;

					boolean nameOk = false;

					for (String name : methodNames) {
						if (name.equals(method.getName())) {
							nameOk = true;
							break;
						}
					}

					if (!nameOk) continue;

					trySetAccessible(method);

					Object ret = tryInvokeStaticObject(method, stack);
					if (ret != null && isFilterWrapResult(ret)) return ret;

					if (currentLevel != null) {
						ret = tryInvokeStaticObject(method, stack, currentLevel);
						if (ret != null && isFilterWrapResult(ret)) return ret;

						ret = tryInvokeStaticObject(method, currentLevel, stack);
						if (ret != null && isFilterWrapResult(ret)) return ret;
					}

					if (provider != null) {
						ret = tryInvokeStaticObject(method, stack, provider);
						if (ret != null && isFilterWrapResult(ret)) return ret;

						ret = tryInvokeStaticObject(method, provider, stack);
						if (ret != null && isFilterWrapResult(ret)) return ret;
					}
				}

				for (Method method : methods) {
					if (!Modifier.isStatic(method.getModifiers())) continue;

					Class<?> retType = method.getReturnType();

					if (retType == void.class || retType.isPrimitive()) continue;
					if (retType == Boolean.class) continue;
					if (Number.class.isAssignableFrom(retType)) continue;
					if (retType == String.class) continue;

					trySetAccessible(method);

					Object ret = tryInvokeStaticObject(method, stack);
					if (ret != null && isFilterWrapResult(ret)) return ret;

					if (currentLevel != null) {
						ret = tryInvokeStaticObject(method, stack, currentLevel);
						if (ret != null && isFilterWrapResult(ret)) return ret;

						ret = tryInvokeStaticObject(method, currentLevel, stack);
						if (ret != null && isFilterWrapResult(ret)) return ret;
					}

					if (provider != null) {
						ret = tryInvokeStaticObject(method, stack, provider);
						if (ret != null && isFilterWrapResult(ret)) return ret;

						ret = tryInvokeStaticObject(method, provider, stack);
						if (ret != null && isFilterWrapResult(ret)) return ret;
					}
				}

				for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
					trySetAccessible(ctor);

					Object ret = tryInvokeConstructor(ctor, stack);
					if (ret != null && isFilterWrapResult(ret)) return ret;

					if (currentLevel != null) {
						ret = tryInvokeConstructor(ctor, stack, currentLevel);
						if (ret != null && isFilterWrapResult(ret)) return ret;

						ret = tryInvokeConstructor(ctor, currentLevel, stack);
						if (ret != null && isFilterWrapResult(ret)) return ret;
					}

					if (provider != null) {
						ret = tryInvokeConstructor(ctor, stack, provider);
						if (ret != null && isFilterWrapResult(ret)) return ret;

						ret = tryInvokeConstructor(ctor, provider, stack);
						if (ret != null && isFilterWrapResult(ret)) return ret;
					}
				}

				CompoundTag tag = saveStackToTag(stack);
				if (tag != null && level instanceof ServerLevel serverLevel) {
					Object fromTag = tryStaticFilterParse(clazz, tag, serverLevel);
					if (fromTag != null && isFilterWrapResult(fromTag)) return fromTag;
				}
			} catch (Throwable ignored) {
			}
		}

		return null;
	}

	private boolean isFilterWrapResult(Object obj) {
		if (obj == null) return false;
		if (obj instanceof ItemStack) return false;
		if (isCreateFilterObject(obj)) return true;

		String name = obj.getClass().getName().toLowerCase();
		return name.contains("filter");
	}

	@Nullable
	private CompoundTag saveStackToTag(ItemStack stack) {
		return saveStackToTag(stack, level);
	}

	@Nullable
	private CompoundTag saveStackToTag(ItemStack stack, @Nullable Level saveLevel) {
		try {
			if (saveLevel != null) {
				Object saved = stack.save(saveLevel.registryAccess());

				if (saved instanceof CompoundTag compound) {
					return compound;
				}
			}
		} catch (Throwable ignored) {
		}

		return null;
	}

	@Nullable
	private Boolean tryCreateFilterMatch(Object filter, ItemStack stack) {
		if (filter == null || stack == null || stack.isEmpty()) return null;
		if (filter instanceof ItemStack) return null;

		String[] names = {
				"test",
				"matches",
				"matchesItem",
				"matchesStack",
				"itemMatches",
				"testStack",
				"testItem",
				"accepts",
				"isValid",
				"filterTest",
				"matchesFilter",
				"testFilter",
				"matchesItemStack",
				"testItemStack",
				"match",
				"apply"
		};

		Level currentLevel = level;

		Class<?> c = filter.getClass();
		while (c != null && c != Object.class) {
			Set<Method> methods = new LinkedHashSet<>();
			methods.addAll(Arrays.asList(c.getMethods()));
			methods.addAll(Arrays.asList(c.getDeclaredMethods()));

			for (Method method : methods) {
				String methodName = method.getName();

				boolean wanted = false;

				for (String name : names) {
					if (name.equals(methodName)) {
						wanted = true;
						break;
					}
				}

				if (!wanted) continue;

				trySetAccessible(method);

				Class<?>[] params = method.getParameterTypes();

				try {
					if (params.length == 1 && params[0].isAssignableFrom(ItemStack.class)) {
						Object result = method.invoke(filter, stack);

						if (result instanceof Boolean b) return b;
					}

					if (params.length == 2 && currentLevel != null) {
						boolean p0Level = params[0].isAssignableFrom(currentLevel.getClass());
						boolean p1Level = params[1].isAssignableFrom(currentLevel.getClass());

						boolean p0Stack = params[0].isAssignableFrom(ItemStack.class);
						boolean p1Stack = params[1].isAssignableFrom(ItemStack.class);

						if (p0Level && p1Stack) {
							Object result = method.invoke(filter, currentLevel, stack);

							if (result instanceof Boolean b) return b;
						}

						if (p1Level && p0Stack) {
							Object result = method.invoke(filter, stack, currentLevel);

							if (result instanceof Boolean b) return b;
						}
					}
				} catch (Throwable ignored) {
				}
			}

			c = c.getSuperclass();
		}

		return null;
	}

	@Nullable
	private Boolean tryCreateFilterStaticMatch(ItemStack filterStack, ItemStack stack) {
		if (filterStack.isEmpty()) return true;

		String[] classNames = {
				"com.simibubi.create.content.logistics.filter.FilterItemStack",
				"com.simibubi.create.content.logistics.filter.FilterItem",
				"com.simibubi.create.content.logistics.filter.Filtering"
		};

		String[] methodNames = {
				"test",
				"matches",
				"matchesItem",
				"matchesStack",
				"itemMatches",
				"testStack",
				"testItem",
				"filterTest",
				"matchesFilter",
				"testFilter",
				"matchesItemStack",
				"testItemStack",
				"match"
		};

		Level currentLevel = level;
		Object provider = currentLevel != null ? currentLevel.registryAccess() : null;

		for (String className : classNames) {
			try {
				Class<?> clazz = Class.forName(className);

				Set<Method> methods = new LinkedHashSet<>();
				methods.addAll(Arrays.asList(clazz.getMethods()));
				methods.addAll(Arrays.asList(clazz.getDeclaredMethods()));

				for (Method method : methods) {
					if (!Modifier.isStatic(method.getModifiers())) continue;

					boolean nameOk = false;

					for (String name : methodNames) {
						if (name.equals(method.getName())) {
							nameOk = true;
							break;
						}
					}

					if (!nameOk) continue;

					trySetAccessible(method);

					if (method.getParameterCount() == 2) {
						Boolean result = tryInvokeStaticBoolean(method, filterStack, stack);
						if (result != null) return result;

						result = tryInvokeStaticBoolean(method, stack, filterStack);
						if (result != null) return result;
					}

					if (method.getParameterCount() == 3) {
						if (currentLevel != null) {
							Boolean result = tryInvokeStaticBoolean(method, currentLevel, filterStack, stack);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, currentLevel, stack, filterStack);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, filterStack, currentLevel, stack);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, filterStack, stack, currentLevel);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, stack, filterStack, currentLevel);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, stack, currentLevel, filterStack);
							if (result != null) return result;
						}

						if (provider != null) {
							Boolean result = tryInvokeStaticBoolean(method, provider, filterStack, stack);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, provider, stack, filterStack);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, filterStack, provider, stack);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, filterStack, stack, provider);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, stack, filterStack, provider);
							if (result != null) return result;

							result = tryInvokeStaticBoolean(method, stack, provider, filterStack);
							if (result != null) return result;
						}
					}
				}
			} catch (Throwable ignored) {
			}
		}

		return null;
	}

	@Nullable
	private Object tryInvokeStaticObject(Method method, Object... args) {
		Class<?>[] params = method.getParameterTypes();

		if (params.length != args.length) return null;

		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				if (params[i].isPrimitive()) return null;
			} else if (!params[i].isAssignableFrom(args[i].getClass())) {
				return null;
			}
		}

		try {
			return unwrapOptional(method.invoke(null, args));
		} catch (Throwable ignored) {
			return null;
		}
	}

	@Nullable
	private Boolean tryInvokeStaticBoolean(Method method, Object... args) {
		Object result = tryInvokeStaticObject(method, args);
		return result instanceof Boolean b ? b : null;
	}

	@Nullable
	private Object tryInvokeConstructor(Constructor<?> ctor, Object... args) {
		Class<?>[] params = ctor.getParameterTypes();

		if (params.length != args.length) return null;

		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				if (params[i].isPrimitive()) return null;
			} else if (!params[i].isAssignableFrom(args[i].getClass())) {
				return null;
			}
		}

		try {
			return unwrapOptional(ctor.newInstance(args));
		} catch (Throwable ignored) {
			return null;
		}
	}

	@Nullable
	private Object getUnderlyingFilterStack(Object filter) {
		if (filter == null) return null;

		for (String getter : new String[]{
				"getItemStack",
				"getStack",
				"getItem",
				"getFilterStack",
				"getFilter"
		}) {
			Object obj = unwrapOptional(invokeNoArg(filter, getter));

			if (obj instanceof ItemStack stack) {
				return stack;
			}

			if (obj != null && obj != filter && isCreateFilterObject(obj)) {
				Object nested = getUnderlyingFilterStack(obj);

				if (nested instanceof ItemStack stack) {
					return stack;
				}
			}
		}

		return null;
	}

	private Object unwrapOptional(Object obj) {
		if (obj instanceof Optional<?> optional) {
			return optional.isPresent() ? optional.get() : null;
		}

		return obj;
	}

	@Nullable
	private ItemStack parseFilterTag(CompoundTag tag, ServerLevel level) {
		for (String key : new String[]{"Filter", "Item", "ItemStack", "Stack", "ItemFilter", "FilterItem"}) {
			if (tag.contains(key)) {
				try {
					Optional<ItemStack> parsed = ItemStack.parse(level.registryAccess(), tag.get(key));

					if (parsed.isPresent()) {
						return parsed.get();
					}
				} catch (Throwable ignored) {
				}
			}
		}

		return null;
	}

	private int countItemsAll(@Nullable Train train, Set<PhysicsBogeyBlockEntity> consist,
							  Object filter, boolean countStacks) {
		int total = 0;

		total += countItemsInTrainCarriages(train, filter, countStacks);
		total += countItemsInTrainSublevels(consist, filter, countStacks);

		return total;
	}

	private int countFluidsAll(@Nullable Train train, Set<PhysicsBogeyBlockEntity> consist, Object filter) {
		int total = 0;

		total += countFluidsInTrainCarriages(train, filter);
		total += countFluidsInTrainSublevels(consist, filter);

		return total;
	}

	private int countItemsInTrainCarriages(@Nullable Train train, Object filter, boolean countStacks) {
		if (train == null) return 0;

		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		int total = 0;

		Object carriages = getFieldValue(train, "carriages");
		if (carriages == null) carriages = invokeNoArg(train, "getCarriages");

		if (carriages instanceof Iterable<?> iterable) {
			for (Object carriage : iterable) {
				Object contraption = getContraption(carriage);

				if (contraption != null) {
					total += countItemsInObjectGraph(contraption, filter, countStacks, seen, 0);
				} else {
					total += countItemsInObjectGraph(carriage, filter, countStacks, seen, 0);
				}
			}
		} else if (carriages != null && carriages.getClass().isArray()) {
			int length = Array.getLength(carriages);

			for (int i = 0; i < length; i++) {
				Object carriage = Array.get(carriages, i);
				Object contraption = getContraption(carriage);

				if (contraption != null) {
					total += countItemsInObjectGraph(contraption, filter, countStacks, seen, 0);
				} else {
					total += countItemsInObjectGraph(carriage, filter, countStacks, seen, 0);
				}
			}
		} else {
			total += countItemsInObjectGraph(train, filter, countStacks, seen, 0);
		}

		return total;
	}

	private int countFluidsInTrainCarriages(@Nullable Train train, Object filter) {
		if (train == null) return 0;

		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		int total = 0;

		Object carriages = getFieldValue(train, "carriages");
		if (carriages == null) carriages = invokeNoArg(train, "getCarriages");

		if (carriages instanceof Iterable<?> iterable) {
			for (Object carriage : iterable) {
				Object contraption = getContraption(carriage);

				if (contraption != null) {
					total += countFluidsInObjectGraph(contraption, filter, seen, 0);
				} else {
					total += countFluidsInObjectGraph(carriage, filter, seen, 0);
				}
			}
		} else if (carriages != null && carriages.getClass().isArray()) {
			int length = Array.getLength(carriages);

			for (int i = 0; i < length; i++) {
				Object carriage = Array.get(carriages, i);
				Object contraption = getContraption(carriage);

				if (contraption != null) {
					total += countFluidsInObjectGraph(contraption, filter, seen, 0);
				} else {
					total += countFluidsInObjectGraph(carriage, filter, seen, 0);
				}
			}
		} else {
			total += countFluidsInObjectGraph(train, filter, seen, 0);
		}

		return total;
	}

	private Object getContraption(Object carriage) {
		if (carriage == null) return null;

		Object contraption = invokeNoArg(carriage, "getContraption");
		if (contraption != null) return contraption;

		return getFieldValue(carriage, "contraption");
	}

	private int countItemsInObjectGraph(Object obj, Object filter, boolean countStacks, Set<Object> seen, int depth) {
		if (obj == null || depth > MAX_OBJECT_GRAPH_DEPTH) return 0;

		if (obj instanceof ItemStack stack) {
			if (!stack.isEmpty() && itemMatches(filter, stack)) {
				return countStacks ? 1 : stack.getCount();
			}

			return 0;
		}

		if (!seen.add(obj)) return 0;

		if (obj instanceof IItemHandler handler) {
			return countItemsHandler(handler, filter, countStacks);
		}

		if (obj instanceof Container container) {
			return countContainer(container, filter, countStacks);
		}

		int total = 0;

		if (obj instanceof Map<?, ?> map) {
			for (Object value : map.values()) {
				total += countItemsInObjectGraph(value, filter, countStacks, seen, depth + 1);
			}

			return total;
		}

		if (obj instanceof Iterable<?> iterable) {
			for (Object value : iterable) {
				total += countItemsInObjectGraph(value, filter, countStacks, seen, depth + 1);
			}

			return total;
		}

		if (obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				total += countItemsInObjectGraph(Array.get(obj, i), filter, countStacks, seen, depth + 1);
			}

			return total;
		}

		String name = obj.getClass().getSimpleName().toLowerCase();

		if (depth == 0 || shouldDeepScanItemObject(name)) {
			Class<?> cls = obj.getClass();

			while (cls != null && cls != Object.class) {
				for (Field field : cls.getDeclaredFields()) {
					int modifiers = field.getModifiers();

					if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;

					trySetAccessible(field);

					try {
						total += countItemsInObjectGraph(field.get(obj), filter, countStacks, seen, depth + 1);
					} catch (Throwable ignored) {
					}
				}

				cls = cls.getSuperclass();
			}
		}

		return total;
	}

	private int countFluidsInObjectGraph(Object obj, Object filter, Set<Object> seen, int depth) {
		if (obj == null || depth > MAX_OBJECT_GRAPH_DEPTH) return 0;

		if (obj instanceof FluidStack stack) {
			if (!stack.isEmpty() && fluidMatches(filter, stack)) {
				return stack.getAmount();
			}

			return 0;
		}

		if (!seen.add(obj)) return 0;

		if (obj instanceof IFluidHandler handler) {
			return countFluidsHandler(handler, filter);
		}

		int total = 0;

		if (obj instanceof Map<?, ?> map) {
			for (Object value : map.values()) {
				total += countFluidsInObjectGraph(value, filter, seen, depth + 1);
			}

			return total;
		}

		if (obj instanceof Iterable<?> iterable) {
			for (Object value : iterable) {
				total += countFluidsInObjectGraph(value, filter, seen, depth + 1);
			}

			return total;
		}

		if (obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				total += countFluidsInObjectGraph(Array.get(obj, i), filter, seen, depth + 1);
			}

			return total;
		}

		String name = obj.getClass().getSimpleName().toLowerCase();

		if (depth == 0 || shouldDeepScanFluidObject(name)) {
			Class<?> cls = obj.getClass();

			while (cls != null && cls != Object.class) {
				for (Field field : cls.getDeclaredFields()) {
					int modifiers = field.getModifiers();

					if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;

					trySetAccessible(field);

					try {
						total += countFluidsInObjectGraph(field.get(obj), filter, seen, depth + 1);
					} catch (Throwable ignored) {
					}
				}

				cls = cls.getSuperclass();
			}
		}

		return total;
	}

	private boolean shouldDeepScanItemObject(String name) {
		return name.contains("contraption")
				|| name.contains("carriage")
				|| name.contains("storage")
				|| name.contains("inventory")
				|| name.contains("vault")
				|| name.contains("handler")
				|| name.contains("mounted")
				|| name.contains("chest")
				|| name.contains("barrel")
				|| name.contains("crate")
				|| name.contains("cargo")
				|| name.contains("item")
				|| name.contains("smart")
				|| name.contains("wrapper")
				|| name.contains("package")
				|| name.contains("stack")
				|| name.contains("sublevel");
	}

	private boolean shouldDeepScanFluidObject(String name) {
		return name.contains("contraption")
				|| name.contains("carriage")
				|| name.contains("storage")
				|| name.contains("inventory")
				|| name.contains("vault")
				|| name.contains("tank")
				|| name.contains("fluid")
				|| name.contains("handler")
				|| name.contains("mounted")
				|| name.contains("cargo")
				|| name.contains("smart")
				|| name.contains("wrapper")
				|| name.contains("package")
				|| name.contains("stack")
				|| name.contains("sublevel");
	}

	private int countItemsInTrainSublevels(Set<PhysicsBogeyBlockEntity> consist, Object filter, boolean countStacks) {
		Set<Object> seenSubLevels = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> seenObjects = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> countedVaults = new HashSet<>();

		int total = 0;

		for (PhysicsBogeyBlockEntity bogey : consist) {
			Level beLevel = bogey.getLevel();
			if (beLevel == null) continue;

			Object subLevel = getSubLevel(beLevel, bogey.getBlockPos(), bogey);

			total += countItemsInSubLevelRecursive(
					subLevel,
					filter,
					countStacks,
					seenSubLevels,
					seenObjects,
					countedVaults,
					0
			);
		}

		return total;
	}

	private int countFluidsInTrainSublevels(Set<PhysicsBogeyBlockEntity> consist, Object filter) {
		Set<Object> seenSubLevels = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> seenObjects = Collections.newSetFromMap(new IdentityHashMap<>());

		int total = 0;

		for (PhysicsBogeyBlockEntity bogey : consist) {
			Level beLevel = bogey.getLevel();
			if (beLevel == null) continue;

			Object subLevel = getSubLevel(beLevel, bogey.getBlockPos(), bogey);

			total += countFluidsInSubLevelRecursive(
					subLevel,
					filter,
					seenSubLevels,
					seenObjects,
					0
			);
		}

		return total;
	}

	private int countItemsInSubLevelRecursive(Object subLevel, Object filter, boolean countStacks,
											  Set<Object> seenSubLevels, Set<Object> seenObjects,
											  Set<Object> countedVaults, int depth) {
		if (subLevel == null || depth > SUBLEVEL_SCAN_DEPTH || !seenSubLevels.add(subLevel)) {
			return 0;
		}

		int total = 0;

		for (BlockEntity be : getSubLevelBlockEntities(subLevel)) {
			if (!isCargoBlockEntity(be)) continue;
			if (seenObjects.contains(be)) continue;

			seenObjects.add(be);

			if (isVaultBlockEntity(be)) {
				Object vaultKey = getVaultKey(be);

				if (vaultKey != null && !countedVaults.add(vaultKey)) {
					continue;
				}

				total += countVaultBlockEntity(be, filter, countStacks);
				continue;
			}

			int found = countItemsViaCapabilities(be, filter, countStacks);

			if (found == 0) {
				found = countItemsInObjectGraph(be, filter, countStacks, seenObjects, depth + 1);
			}

			total += found;
		}

		for (Object linked : getLinkedSubLevels(subLevel)) {
			total += countItemsInSubLevelRecursive(
					linked,
					filter,
					countStacks,
					seenSubLevels,
					seenObjects,
					countedVaults,
					depth + 1
			);
		}

		return total;
	}

	private int countFluidsInSubLevelRecursive(Object subLevel, Object filter,
											   Set<Object> seenSubLevels, Set<Object> seenObjects,
											   int depth) {
		if (subLevel == null || depth > SUBLEVEL_SCAN_DEPTH || !seenSubLevels.add(subLevel)) {
			return 0;
		}

		int total = 0;

		for (BlockEntity be : getSubLevelBlockEntities(subLevel)) {
			if (!isCargoBlockEntity(be)) continue;
			if (seenObjects.contains(be)) continue;

			int found = countFluidsViaCapabilities(be, filter);

			if (found == 0) {
				found = countFluidsInObjectGraph(be, filter, seenObjects, depth + 1);
			}

			seenObjects.add(be);
			total += found;
		}

		for (Object linked : getLinkedSubLevels(subLevel)) {
			total += countFluidsInSubLevelRecursive(
					linked,
					filter,
					seenSubLevels,
					seenObjects,
					depth + 1
			);
		}

		total += countFluidsInObjectGraph(subLevel, filter, seenObjects, 0);

		return total;
	}

	private boolean isVaultBlockEntity(BlockEntity be) {
		if (be == null) return false;

		if (be instanceof ItemVaultBlockEntity) {
			return true;
		}

		String name = be.getClass().getName().toLowerCase();
		return name.contains("vault")
				|| name.contains("itemvault")
				|| name.contains("storagevault");
	}

	private int countVaultBlockEntity(BlockEntity be, Object filter, boolean countStacks) {
		if (be instanceof ItemVaultBlockEntity vault) {
			Level level = vault.getLevel();
			BlockPos controller = vault.getController();

			if (level != null && controller != null) {
				IItemHandler handler = level.getCapability(
						Capabilities.ItemHandler.BLOCK,
						controller,
						null
				);

				if (handler != null) {
					return countItemsHandler(handler, filter, countStacks);
				}
			}

			Object inventory = unwrapOptional(invokeNoArg(vault, "getInventoryOfBlock"));
			return countInventoryLike(inventory, filter, countStacks);
		}

		if (be instanceof IItemHandler handler) {
			return countItemsHandler(handler, filter, countStacks);
		}

		if (be instanceof Container container) {
			return countContainer(container, filter, countStacks);
		}

		String[] getters = {
				"getInventory",
				"getItemInventory",
				"getHandler",
				"getItemHandler",
				"getStorage",
				"getItems",
				"getVaultInventory",
				"getInventoryHandler",
				"getContainedInventory",
				"getStorageHandler",
				"getItemStorage"
		};

		for (String getter : getters) {
			Object inventory = unwrapOptional(invokeNoArg(be, getter));
			int found = countInventoryLike(inventory, filter, countStacks);
			if (found > 0) {
				return found;
			}
		}

		String[] fields = {
				"inventory",
				"itemInventory",
				"handler",
				"itemHandler",
				"storage",
				"items",
				"vaultInventory",
				"inventoryHandler",
				"itemStorage"
		};

		for (String field : fields) {
			Object inventory = getFieldValue(be, field);
			int found = countInventoryLike(inventory, filter, countStacks);
			if (found > 0) {
				return found;
			}
		}

		return countItemsInObjectGraph(
				be,
				filter,
				countStacks,
				Collections.newSetFromMap(new IdentityHashMap<>()),
				0
		);
	}

	@Nullable
	private Object getVaultKey(BlockEntity be) {
		if (be instanceof ItemVaultBlockEntity vault) {
			BlockPos controller = vault.getController();

			if (controller == null) {
				controller = vault.getBlockPos();
			}

			Level level = vault.getLevel();
			String dim = level != null ? level.dimension().location().toString() : "unknown";

			return dim + ":" + controller.asLong();
		}

		return null;
	}

	private int countInventoryLike(Object inventory, Object filter, boolean countStacks) {
		if (inventory == null) return 0;

		if (inventory instanceof IItemHandler handler) {
			return countItemsHandler(handler, filter, countStacks);
		}

		if (inventory instanceof Container container) {
			return countContainer(container, filter, countStacks);
		}

		return countItemsInObjectGraph(
				inventory,
				filter,
				countStacks,
				Collections.newSetFromMap(new IdentityHashMap<>()),
				0
		);
	}

	private int countItemsViaCapabilities(BlockEntity be, Object filter, boolean countStacks) {
		Level level = be.getLevel();
		if (level == null) return 0;

		for (Direction side : CAPABILITY_SIDES) {
			try {
				IItemHandler handler = level.getCapability(
						Capabilities.ItemHandler.BLOCK,
						be.getBlockPos(),
						side
				);

				if (handler != null && handler.getSlots() > 0) {
					return countItemsHandler(handler, filter, countStacks);
				}
			} catch (Throwable ignored) {
			}
		}

		return 0;
	}

	private int countFluidsViaCapabilities(BlockEntity be, Object filter) {
		Level level = be.getLevel();
		if (level == null) return 0;

		for (Direction side : CAPABILITY_SIDES) {
			try {
				IFluidHandler handler = level.getCapability(
						Capabilities.FluidHandler.BLOCK,
						be.getBlockPos(),
						side
				);

				if (handler != null && handler.getTanks() > 0) {
					return countFluidsHandler(handler, filter);
				}
			} catch (Throwable ignored) {
			}
		}

		return 0;
	}

	private int countItemsHandler(IItemHandler handler, Object filter, boolean countStacks) {
		int total = 0;

		for (int slot = 0; slot < handler.getSlots(); slot++) {
			ItemStack stack = handler.getStackInSlot(slot);

			if (!stack.isEmpty() && itemMatches(filter, stack)) {
				total += countStacks ? 1 : stack.getCount();
			}
		}

		return total;
	}

	private int countFluidsHandler(IFluidHandler handler, Object filter) {
		int total = 0;

		for (int tank = 0; tank < handler.getTanks(); tank++) {
			FluidStack stack = handler.getFluidInTank(tank);

			if (!stack.isEmpty() && fluidMatches(filter, stack)) {
				total += stack.getAmount();
			}
		}

		return total;
	}

	private int countContainer(Container container, Object filter, boolean countStacks) {
		int total = 0;

		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);

			if (stack != null && !stack.isEmpty() && itemMatches(filter, stack)) {
				total += countStacks ? 1 : stack.getCount();
			}
		}

		return total;
	}

	private boolean itemMatches(Object filter, ItemStack stack) {
		if (filter == null) {
			return true;
		}

		if (stack == null || stack.isEmpty()) {
			return false;
		}

		if (filter instanceof FilterItemStack createFilter) {
			try {
				if (createFilter.isEmpty()) {
					return true;
				}

				if (level != null) {
					return createFilter.test(level, stack);
				}
			} catch (Throwable ignored) {
			}
		}

		Boolean createMatch = tryCreateFilterMatch(filter, stack);
		if (createMatch != null) {
			return createMatch;
		}

		if (filter instanceof ItemStack filterStack) {
			if (filterStack.isEmpty()) {
				return true;
			}

			Object wrapped = tryWrapAsCreateFilter(filterStack);
			if (wrapped != null) {
				Boolean wrappedMatch = tryCreateFilterMatch(wrapped, stack);

				if (wrappedMatch != null) {
					return wrappedMatch;
				}
			}

			Boolean staticMatch = tryCreateFilterStaticMatch(filterStack, stack);
			if (staticMatch != null) {
				return staticMatch;
			}

			if (!ItemStack.isSameItem(filterStack, stack)) {
				return false;
			}

			try {
				Object patch = filterStack.getComponentsPatch();
				Method isEmpty = patch.getClass().getMethod("isEmpty");
				Object empty = isEmpty.invoke(patch);

				if (empty instanceof Boolean b && b) {
					return true;
				}
			} catch (Throwable ignored) {
			}

			return ItemStack.isSameItemSameComponents(filterStack, stack);
		}

		Object underlying = getUnderlyingFilterStack(filter);

		if (underlying instanceof ItemStack underlyingStack && underlying != filter) {
			return itemMatches(underlyingStack, stack);
		}

		return false;
	}

	private boolean fluidMatches(Object filter, Object fluid) {
		if (filter == null) {
			return true;
		}

		if (fluid == null) {
			return false;
		}

		if (filter instanceof FilterItemStack createFilter && fluid instanceof FluidStack fluidStack) {
			try {
				if (createFilter.isEmpty()) {
					return true;
				}

				if (level != null) {
					return createFilter.test(level, fluidStack);
				}
			} catch (Throwable ignored) {
			}
		}

		if (fluid instanceof FluidStack fluidStack) {
			if (filter instanceof FluidStack filterStack) {
				if (filterStack.isEmpty()) {
					return true;
				}

				return filterStack.isFluidEqual(fluidStack);
			}
		}

		try {
			Object empty = invokeNoArg(filter, "isEmpty");

			if (empty instanceof Boolean b && b) {
				return true;
			}
		} catch (Throwable ignored) {
		}

		try {
			for (Method method : filter.getClass().getMethods()) {
				if (method.getName().equals("isFluidEqual") && method.getParameterCount() == 1) {
					Object result = method.invoke(filter, fluid);

					if (result instanceof Boolean b) {
						return b;
					}
				}
			}
		} catch (Throwable ignored) {
		}

		Object filterFluid = invokeNoArg(filter, "getFluid");
		Object stackFluid = invokeNoArg(fluid, "getFluid");

		if (filterFluid != null && stackFluid != null) {
			return filterFluid.equals(stackFluid);
		}

		return false;
	}

	private boolean isCargoBlockEntity(BlockEntity be) {
		if (be == null) return false;
		if (be instanceof NavigationControllerBlockEntity) return false;

		String name = be.getClass().getName().toLowerCase();

		if (name.contains("navigation")) return false;
		if (name.contains("station")) return false;
		if (name.contains("depot")) return false;
		if (name.contains("dockingconnector")) return false;
		if (name.contains("controller")) return false;

		if (name.contains("vault")
				|| name.contains("tank")
				|| name.contains("storage")
				|| name.contains("container")
				|| name.contains("inventory")
				|| name.contains("chest")
				|| name.contains("barrel")
				|| name.contains("crate")
				|| name.contains("cargo")
				|| name.contains("smart")
				|| name.contains("package")
				|| name.contains("item")
				|| name.contains("fluid")) {
			return true;
		}

		Level level = be.getLevel();
		if (level == null) return false;

		try {
			IItemHandler itemHandler = level.getCapability(
					Capabilities.ItemHandler.BLOCK,
					be.getBlockPos(),
					(Direction) null
			);

			if (itemHandler != null && itemHandler.getSlots() > 0) return true;
		} catch (Throwable ignored) {
		}

		try {
			IFluidHandler fluidHandler = level.getCapability(
					Capabilities.FluidHandler.BLOCK,
					be.getBlockPos(),
					(Direction) null
			);

			if (fluidHandler != null && fluidHandler.getTanks() > 0) return true;
		} catch (Throwable ignored) {
		}

		return false;
	}

	@Nullable
	private Object getSableHelper() {
		String[] classNames = {
				"dev.ryanhcode.sable.Sable",
				"dev.ryanhcode.sable.SableAPI",
				"dev.ryanhcode.sable.api.SableAPI",
				"dev.ryanhcode.sable.SableHelper",
				"dev.ryanhcode.sable.api.SableHelper"
		};

		String[] fieldNames = {
				"HELPER",
				"API",
				"INSTANCE",
				"HELPER_INSTANCE",
				"SABLE_HELPER",
				"SABLE_API"
		};

		String[] methodNames = {
				"getHelper",
				"getInstance",
				"getAPI",
				"get",
				"helper"
		};

		for (String className : classNames) {
			try {
				Class<?> clazz = Class.forName(className);

				for (String fieldName : fieldNames) {
					try {
						Field field = clazz.getDeclaredField(fieldName);
						trySetAccessible(field);

						Object value = field.get(null);
						if (value != null) return value;
					} catch (Throwable ignored) {
					}
				}

				for (String methodName : methodNames) {
					try {
						Method method = clazz.getMethod(methodName);
						Object value = method.invoke(null);

						if (value != null) return value;
					} catch (Throwable ignored) {
					}

					try {
						Method method = clazz.getDeclaredMethod(methodName);
						trySetAccessible(method);

						Object value = method.invoke(null);
						if (value != null) return value;
					} catch (Throwable ignored) {
					}
				}
			} catch (Throwable ignored) {
			}
		}

		return null;
	}

	@Nullable
	private Object findSubLevelLikeInObjectGraph(Object obj, int depth) {
		if (obj == null || depth > 3) return null;
		if (obj instanceof Level) return null;
		if (obj instanceof ItemStack) return null;
		if (obj instanceof FluidStack) return null;

		if (isSubLevelLike(obj)) {
			return obj;
		}

		if (obj instanceof Iterable<?> iterable) {
			for (Object value : iterable) {
				Object found = findSubLevelLikeInObjectGraph(value, depth + 1);
				if (found != null) return found;
			}

			return null;
		}

		if (obj instanceof Map<?, ?> map) {
			for (Object value : map.values()) {
				Object found = findSubLevelLikeInObjectGraph(value, depth + 1);
				if (found != null) return found;
			}

			return null;
		}

		if (obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				Object found = findSubLevelLikeInObjectGraph(Array.get(obj, i), depth + 1);
				if (found != null) return found;
			}

			return null;
		}

		String name = obj.getClass().getName().toLowerCase();

		if (depth < 2
				|| name.contains("bogey")
				|| name.contains("sable")
				|| name.contains("sublevel")
				|| name.contains("entity")
				|| name.contains("carriage")
				|| name.contains("controller")) {
			Class<?> cls = obj.getClass();

			while (cls != null && cls != Object.class) {
				for (Field field : cls.getDeclaredFields()) {
					int modifiers = field.getModifiers();

					if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;

					trySetAccessible(field);

					try {
						Object value = field.get(obj);
						if (value == null || value == obj) continue;

						Object found = findSubLevelLikeInObjectGraph(value, depth + 1);
						if (found != null) return found;
					} catch (Throwable ignored) {
					}
				}

				cls = cls.getSuperclass();
			}
		}

		return null;
	}

	private Object getSubLevel(Level level, BlockPos pos, @Nullable PhysicsBogeyBlockEntity bogey) {
		Object helper = getSableHelper();

		if (helper != null) {
			String[] names = {
					"getContaining",
					"getContainingSubLevel",
					"getSubLevelAt",
					"getSubLevelContaining",
					"getSubLevel",
					"getSubLevelFor",
					"getSubLevelFrom",
					"getSubLevelByPosition"
			};

			for (String name : names) {
				Object ret = invokeByName(helper, name, level, pos);
				Object unwrapped = unwrapSubLevelCandidate(ret);

				if (unwrapped != null && !(unwrapped instanceof Level) && !(unwrapped instanceof BlockEntity)) {
					return unwrapped;
				}

				if (bogey != null) {
					ret = invokeByName(helper, name, bogey);
					unwrapped = unwrapSubLevelCandidate(ret);

					if (unwrapped != null && !(unwrapped instanceof Level) && !(unwrapped instanceof BlockEntity)) {
						return unwrapped;
					}

					ret = invokeByName(helper, name, bogey.getLevel(), bogey.getBlockPos());
					unwrapped = unwrapSubLevelCandidate(ret);

					if (unwrapped != null && !(unwrapped instanceof Level) && !(unwrapped instanceof BlockEntity)) {
						return unwrapped;
					}
				}

				ret = invokeByName(helper, name, pos);
				unwrapped = unwrapSubLevelCandidate(ret);

				if (unwrapped != null && !(unwrapped instanceof Level) && !(unwrapped instanceof BlockEntity)) {
					return unwrapped;
				}
			}
		}

		if (bogey != null) {
			for (String name : new String[]{
					"getSubLevel",
					"getContainingSubLevel",
					"getSableSubLevel",
					"getAttachedSubLevel"
			}) {
				Object ret = unwrapOptional(invokeNoArg(bogey, name));
				Object unwrapped = unwrapSubLevelCandidate(ret);

				if (unwrapped != null && !(unwrapped instanceof Level) && !(unwrapped instanceof BlockEntity)) {
					return unwrapped;
				}
			}

			Object found = findSubLevelLikeInObjectGraph(bogey, 0);
			if (found != null) {
				return found;
			}
		}

		return null;
	}

	private Collection<BlockEntity> getSubLevelBlockEntities(Object subLevel) {
		List<BlockEntity> result = new ArrayList<>();

		String[] methodNames = {
				"getBlockEntities",
				"getAllBlockEntities",
				"getBlockEntityList",
				"getLoadedBlockEntities",
				"getBlockEntityMap",
				"getBlockEntityLookup",
				"getBlockEntitySet",
				"getBlockEntityCollection",
				"getTileEntities",
				"blockEntities"
		};

		for (String name : methodNames) {
			Object obj = unwrapOptional(invokeByName(subLevel, name));
			addBlockEntities(obj, result);

			if (!result.isEmpty()) {
				return result;
			}
		}

		String[] fieldNames = {
				"blockEntityList",
				"blockEntities",
				"blockEntityMap",
				"blockEntitiesById",
				"loadedBlockEntities",
				"blockEntityLookup",
				"blockEntitySet",
				"blockEntityCollection"
		};

		for (String name : fieldNames) {
			Object obj = getFieldValue(subLevel, name);
			addBlockEntities(obj, result);

			if (!result.isEmpty()) {
				return result;
			}
		}

		if (result.isEmpty()) {
			collectBlockEntitiesFromObjectGraph(
					subLevel,
					result,
					Collections.newSetFromMap(new IdentityHashMap<>()),
					0
			);
		}

		return result;
	}

	private void collectBlockEntitiesFromObjectGraph(Object obj, List<BlockEntity> out, Set<Object> seen, int depth) {
		if (obj == null || depth > 4 || !seen.add(obj)) return;

		if (obj instanceof BlockEntity be) {
			out.add(be);
			return;
		}

		if (obj instanceof Map<?, ?> map) {
			for (Object value : map.values()) {
				collectBlockEntitiesFromObjectGraph(value, out, seen, depth + 1);
			}

			return;
		}

		if (obj instanceof Iterable<?> iterable) {
			for (Object value : iterable) {
				collectBlockEntitiesFromObjectGraph(value, out, seen, depth + 1);
			}

			return;
		}

		if (obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				collectBlockEntitiesFromObjectGraph(Array.get(obj, i), out, seen, depth + 1);
			}

			return;
		}

		String name = obj.getClass().getSimpleName().toLowerCase();

		if (depth == 0
				|| name.contains("sublevel")
				|| name.contains("sable")
				|| name.contains("level")
				|| name.contains("manager")
				|| name.contains("storage")
				|| name.contains("blockentity")) {
			Class<?> cls = obj.getClass();

			while (cls != null && cls != Object.class) {
				for (Field field : cls.getDeclaredFields()) {
					int modifiers = field.getModifiers();

					if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;

					trySetAccessible(field);

					try {
						collectBlockEntitiesFromObjectGraph(field.get(obj), out, seen, depth + 1);
					} catch (Throwable ignored) {
					}
				}

				cls = cls.getSuperclass();
			}
		}
	}

	private void addBlockEntities(Object obj, List<BlockEntity> result) {
		if (obj instanceof Collection<?> collection) {
			for (Object o : collection) {
				if (o instanceof BlockEntity be) {
					result.add(be);
				}
			}
		} else if (obj instanceof Map<?, ?> map) {
			for (Object o : map.values()) {
				if (o instanceof BlockEntity be) {
					result.add(be);
				}
			}
		} else if (obj != null && obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				Object o = Array.get(obj, i);

				if (o instanceof BlockEntity be) {
					result.add(be);
				}
			}
		}
	}

	private List<Object> getLinkedSubLevels(Object subLevel) {
		List<Object> result = new ArrayList<>();

		String[] methodNames = {
				"getConnectedSubLevels",
				"getLinkedSubLevels",
				"getConnections",
				"getConnected",
				"getLinked",
				"getConnectionDependencies",
				"getDependencies",
				"getAdjacentSubLevels",
				"getAttachedSubLevels",
				"getChildren",
				"getConnectedLevels"
		};

		for (String name : methodNames) {
			addSubLevelLikeObjects(invokeByName(subLevel, name), result);
		}

		String[] fieldNames = {
				"connectedSubLevels",
				"linkedSubLevels",
				"connections",
				"linked",
				"connected",
				"dependencies",
				"adjacentSubLevels",
				"attachedSubLevels",
				"children"
		};

		for (String name : fieldNames) {
			addSubLevelLikeObjects(getFieldValue(subLevel, name), result);
		}

		Object helper = getSableHelper();

		if (helper != null) {
			String[] helperNames = {
					"getConnectedSubLevels",
					"getLinkedSubLevels",
					"getConnections",
					"getConnected",
					"getLinked",
					"getAdjacentSubLevels",
					"getAttachedSubLevels",
					"getConnectedLevels",
					"getNetwork",
					"getGraph"
			};

			for (String name : helperNames) {
				addSubLevelLikeObjects(invokeOneArg(helper, name, subLevel), result);
			}
		}

		for (String ownerGetter : new String[]{
				"getOwner",
				"getEntity",
				"getNetwork",
				"getGraph",
				"getStructure",
				"getTrain",
				"getCarriage",
				"getBogey"
		}) {
			Object owner = unwrapOptional(invokeByName(subLevel, ownerGetter));

			if (owner != null) {
				for (String subGetter : new String[]{
						"getSubLevels",
						"getAllSubLevels",
						"getConnectedSubLevels",
						"getLinkedSubLevels",
						"getAttachedSubLevels",
						"subLevels"
				}) {
					addSubLevelLikeObjects(invokeByName(owner, subGetter), result);
				}
			}
		}

		Class<?> cls = subLevel.getClass();

		while (cls != null && cls != Object.class) {
			for (Field field : cls.getDeclaredFields()) {
				int modifiers = field.getModifiers();

				if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;

				trySetAccessible(field);

				try {
					Object value = field.get(subLevel);
					if (value == null || value == subLevel) continue;

					addSubLevelLikeObjects(value, result);

					Object nested = findSubLevelLikeInObjectGraph(value, 1);
					if (nested != null && nested != subLevel) {
						result.add(nested);
					}
				} catch (Throwable ignored) {
				}
			}

			cls = cls.getSuperclass();
		}

		return result;
	}

	private void addSubLevelLikeObjects(Object obj, List<Object> result) {
		addSubLevelLikeObjects(obj, result, 0);
	}

	private void addSubLevelLikeObjects(Object obj, List<Object> result, int depth) {
		if (obj == null || depth > 6) return;

		if (obj instanceof Optional<?> optional) {
			if (optional.isPresent()) {
				addSubLevelLikeObjects(optional.get(), result, depth + 1);
			}

			return;
		}

		if (obj instanceof Iterable<?> iterable) {
			for (Object o : iterable) {
				addSubLevelLikeObjects(o, result, depth + 1);
			}

			return;
		}

		if (obj instanceof Map<?, ?> map) {
			for (Object o : map.values()) {
				addSubLevelLikeObjects(o, result, depth + 1);
			}

			return;
		}

		if (obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				addSubLevelLikeObjects(Array.get(obj, i), result, depth + 1);
			}

			return;
		}

		for (String getter : new String[]{
				"getSubLevel",
				"getValue",
				"get"
		}) {
			Object ret = unwrapOptional(invokeByName(obj, getter));

			if (ret != null && ret != obj) {
				addSubLevelLikeObjects(ret, result, depth + 1);
				return;
			}
		}

		if (isSubLevelLike(obj)) {
			result.add(obj);
		}
	}

	private Object unwrapSubLevelCandidate(Object obj) {
		return unwrapSubLevelCandidate(obj, 0);
	}

	private Object unwrapSubLevelCandidate(Object obj, int depth) {
		if (obj == null || depth > 4) return null;

		if (obj instanceof Optional<?> optional) {
			return optional.isPresent()
					? unwrapSubLevelCandidate(optional.get(), depth + 1)
					: null;
		}

		if (obj instanceof Iterable<?> iterable) {
			for (Object o : iterable) {
				Object unwrapped = unwrapSubLevelCandidate(o, depth + 1);
				if (unwrapped != null) return unwrapped;
			}

			return null;
		}

		if (obj instanceof Map<?, ?> map) {
			for (Object o : map.values()) {
				Object unwrapped = unwrapSubLevelCandidate(o, depth + 1);
				if (unwrapped != null) return unwrapped;
			}

			return null;
		}

		if (obj.getClass().isArray()) {
			int length = Array.getLength(obj);

			for (int i = 0; i < length; i++) {
				Object unwrapped = unwrapSubLevelCandidate(Array.get(obj, i), depth + 1);
				if (unwrapped != null) return unwrapped;
			}

			return null;
		}

		if (isSubLevelLike(obj)) {
			return obj;
		}

		for (String getter : new String[]{
				"getSubLevel",
				"getValue",
				"get"
		}) {
			Object ret = unwrapOptional(invokeByName(obj, getter));

			if (ret != null && ret != obj) {
				Object unwrapped = unwrapSubLevelCandidate(ret, depth + 1);
				if (unwrapped != null) return unwrapped;
			}
		}

		return null;
	}

	private boolean isSubLevelLike(Object obj) {
		if (obj == null) return false;
		if (obj instanceof Level) return false;
		if (obj instanceof BlockEntity) return false;

		String name = obj.getClass().getName().toLowerCase();

		if (name.contains("sublevel")) {
			return true;
		}

		if (hasNoArgMethod(obj, "getBlockEntities")
				|| hasNoArgMethod(obj, "getAllBlockEntities")
				|| hasNoArgMethod(obj, "getBlockEntityList")
				|| hasNoArgMethod(obj, "getBlockEntityMap")
				|| hasNoArgMethod(obj, "getBlockEntityLookup")) {
			return true;
		}

		if (name.contains("sable")) {
			return getFieldValue(obj, "blockEntities") != null
					|| getFieldValue(obj, "blockEntityMap") != null
					|| getFieldValue(obj, "blockEntityList") != null
					|| getFieldValue(obj, "blockEntityLookup") != null;
		}

		return false;
	}

	private boolean hasNoArgMethod(Object obj, String name) {
		for (Method method : obj.getClass().getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == 0) {
				return true;
			}
		}

		return false;
	}

	private Object invokeOneArg(Object target, String methodName, Object arg) {
		return invokeByName(target, methodName, arg);
	}

	private Object invokeByName(Object target, String methodName, Object... args) {
		if (target == null) return null;

		Class<?> c = target.getClass();

		while (c != null && c != Object.class) {
			for (Method method : c.getMethods()) {
				Object result = tryInvokeReflected(method, target, methodName, args);
				if (result != null) return result;
			}

			for (Method method : c.getDeclaredMethods()) {
				Object result = tryInvokeReflected(method, target, methodName, args);
				if (result != null) return result;
			}

			c = c.getSuperclass();
		}

		return null;
	}

	private Object tryInvokeReflected(Method method, Object target, String methodName, Object[] args) {
		if (!method.getName().equals(methodName)) return null;
		if (method.getParameterCount() != args.length) return null;

		Class<?>[] params = method.getParameterTypes();

		boolean ok = true;

		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				if (params[i].isPrimitive()) {
					ok = false;
					break;
				}
			} else if (!params[i].isAssignableFrom(args[i].getClass())) {
				ok = false;
				break;
			}
		}

		if (!ok) return null;

		try {
			trySetAccessible(method);
			return unwrapOptional(method.invoke(target, args));
		} catch (Throwable ignored) {
			return null;
		}
	}

	private Object getFieldValue(Object obj, String fieldName) {
		Class<?> c = obj.getClass();

		while (c != null && c != Object.class) {
			try {
				Field field = c.getDeclaredField(fieldName);
				trySetAccessible(field);

				return field.get(obj);
			} catch (Throwable ignored) {
			}

			c = c.getSuperclass();
		}

		return null;
	}

	private Object invokeNoArg(Object target, String methodName) {
		try {
			Method method = findMethod(target.getClass(), methodName, 0);
			if (method == null) return null;

			return unwrapOptional(method.invoke(target));
		} catch (Throwable ignored) {
			return null;
		}
	}

	private Method findMethod(Class<?> clazz, String name, int parameterCount) {
		for (Method method : clazz.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
				return method;
			}
		}

		return null;
	}

	private boolean compare(int value, int target, String operator) {
		return switch (operator) {
			case ">" -> value > target;
			case "<" -> value < target;
			case "<=" -> value <= target;
			case "==" -> value == target;
			default -> value >= target;
		};
	}

	private void trySetAccessible(java.lang.reflect.AccessibleObject object) {
		try {
			object.trySetAccessible();
		} catch (Throwable ignored) {
		}
	}

	private void advanceEntry(Schedule schedule, HolderLookup.Provider registries) {
		currentEntry = schedule.cyclic
				? Math.floorMod(currentEntry + 1, schedule.entries.size())
				: Math.min(currentEntry + 1, schedule.entries.size() - 1);

		arrivedAtDestination = false;
		hasDeparted = true;
		departureHoldTicks = DEPARTURE_HOLD_TICKS;

		resetConditionProgress();

		lastDistance = Double.NaN;
		currentPath = Collections.emptyList();

		debugInfo = "";

		schedule.savedProgress = currentEntry;
		scheduleStack.set(AllDataComponents.TRAIN_SCHEDULE, schedule.write(registries));

		setChanged();
		sendData();
	}

	private void resetConditionProgress() {
		conditionProgress.clear();
		conditionContext.clear();
	}

	@Nullable
	private PhysicsBogeyBlockEntity findAdjacentBogey() {
		for (Direction dir : Direction.values()) {
			if (level.getBlockEntity(getBlockPos().relative(dir)) instanceof PhysicsBogeyBlockEntity bogey) {
				return bogey;
			}
		}

		return null;
	}

	@Nullable
	private PhysicsBogeyAxle findAnchorAxle(PhysicsBogeyBlockEntity bogey) {
		for (boolean front : new boolean[]{true, false}) {
			PhysicsBogeyAxle axle = bogey.getAxle(front);

			if (axle.getTrackGraph() != null && axle.getTrackPoint() != null && axle.getTrackPoint().edge != null) {
				return axle;
			}
		}

		return null;
	}

	private boolean sameEdge(@Nullable TrackEdge a, @Nullable TrackEdge b) {
		if (a == null || b == null) return false;

		return (a.node1 == b.node1 && a.node2 == b.node2)
				|| (a.node1 == b.node2 && a.node2 == b.node1);
	}

	@Nullable
	private TrackEdge getStationEdge(TrackGraph graph, GlobalStation station) {
		if (station == null || station.edgeLocation == null) return null;

		TrackNode n1 = graph.locateNode(station.edgeLocation.getFirst());
		TrackNode n2 = graph.locateNode(station.edgeLocation.getSecond());

		if (n1 == null || n2 == null) return null;

		return graph.getConnection(Couple.create(n1, n2));
	}

	private List<StationApproach> getStationApproaches(TrackGraph graph, GlobalStation station) {
		List<StationApproach> approaches = new ArrayList<>();

		if (station.edgeLocation == null) return approaches;

		TrackNode n1 = graph.locateNode(station.edgeLocation.getFirst());
		TrackNode n2 = graph.locateNode(station.edgeLocation.getSecond());

		if (n1 == null || n2 == null) return approaches;

		boolean canApproachTowardsN1 = station.canApproachFrom(n1);
		boolean canApproachTowardsN2 = station.canApproachFrom(n2);

		if (canApproachTowardsN2) {
			approaches.add(new StationApproach(n1, n2));
		}

		if (canApproachTowardsN1) {
			approaches.add(new StationApproach(n2, n1));
		}

		if (approaches.isEmpty() || station.assembling) {
			boolean hasN1ToN2 = false;
			boolean hasN2ToN1 = false;

			for (StationApproach approach : approaches) {
				if (approach.approach == n1 && approach.depart == n2) hasN1ToN2 = true;
				if (approach.approach == n2 && approach.depart == n1) hasN2ToN1 = true;
			}

			if (!hasN1ToN2) approaches.add(new StationApproach(n1, n2));
			if (!hasN2ToN1) approaches.add(new StationApproach(n2, n1));
		}

		return approaches;
	}

	private List<TrackNode> withStationEdge(List<TrackNode> path, TrackNode approach, TrackNode depart) {
		if (path.isEmpty() || depart == null) return path;

		if (path.get(path.size() - 1) != approach) return path;

		if (path.size() >= 2 && path.get(path.size() - 2) == depart) {
			return path;
		}

		List<TrackNode> out = new ArrayList<>(path);
		out.add(depart);

		return out;
	}

	private boolean isCurrentEdgeOnPath(List<TrackNode> path, TrackEdge edge, boolean desiredTowardsNode2) {
		if (path.isEmpty() || edge == null) return false;

		int idx1 = path.indexOf(edge.node1);
		int idx2 = path.indexOf(edge.node2);

		if (idx1 == -1 && idx2 == -1) return false;

		if (idx1 != -1 && idx2 != -1) {
			return true;
		}

		TrackNode approachedNode = desiredTowardsNode2 ? edge.node2 : edge.node1;

		if (idx1 != -1) {
			return edge.node1 == approachedNode;
		}

		return edge.node2 == approachedNode;
	}

	private int computeDirectionSign(Vec3 edgeForward, Vec3 axleForward, boolean movingTowardsNode2, double sourceSign) {
		Vec3 desiredDir = movingTowardsNode2 ? edgeForward : edgeForward.scale(-1);

		int desiredBogeyRotation = desiredDir.dot(axleForward) >= 0 ? 1 : -1;

		int src = (int) sourceSign;
		if (src == 0) src = 1;

		return desiredBogeyRotation * src;
	}

	private Candidate evaluatePathCandidate(TrackGraph graph, TravellingPoint point,
											List<TrackNode> path,
											GlobalStation targetStation,
											@Nullable GlobalStation currentStation,
											int directionPreference) {
		if (path.isEmpty()) {
			return new Candidate(false, false, Double.MAX_VALUE, path);
		}

		EdgePathDirection dir = getEdgePathDirection(path, point.edge);
		if (dir.startIdx() < 0) {
			return new Candidate(false, false, Double.MAX_VALUE, path);
		}

		boolean towardsNode2 = dir.towardsNode2();

		boolean matchesDirection = directionPreference == 0
				|| (directionPreference == 1 ? !towardsNode2 : towardsNode2);

		boolean valid = pathRespectsAllStationDirections(
				graph,
				path,
				point,
				towardsNode2,
				targetStation,
				currentStation
		);

		List<TrackNode> scoringPath = path;
		if (dir.startIdx() > 0 && dir.startIdx() < path.size()) {
			scoringPath = path.subList(dir.startIdx(), path.size());
		}

		double score = calculatePathDistance(
				graph,
				scoringPath,
				point,
				towardsNode2,
				targetStation,
				currentStation
		);

		if (directionPreference == 1) {
			if (towardsNode2) {
				score += 100000.0;
			} else {
				score *= 0.9;
			}
		} else if (directionPreference == 2) {
			if (!towardsNode2) {
				score += 100000.0;
			} else {
				score *= 0.9;
			}
		}

		if (targetStation != null && targetStation == currentStation && !arrivedAtDestination) {
			if (path.size() <= 3 || scoringPath.size() <= 2) {
				score += 1000000.0;
			}
		}

		return new Candidate(valid, matchesDirection, score, path);
	}

	private boolean acceptCandidate(Candidate candidate, boolean requireDirection, boolean requireValid) {
		if (candidate.path().isEmpty()) return false;
		if (candidate.score() >= Double.MAX_VALUE) return false;
		if (requireDirection && !candidate.matchesDirection()) return false;
		if (requireValid && !candidate.valid()) return false;

		return true;
	}

	private List<TrackNode> tryBuildBestStationPath(TrackGraph graph, TravellingPoint point,
													TrackNode n1, TrackNode n2,
													GlobalStation targetStation,
													@Nullable GlobalStation currentStation,
													int directionPreference,
													List<StationApproach> approaches,
													boolean requireDirection,
													boolean requireValid) {
		List<TrackNode> bestPath = Collections.emptyList();
		double bestScore = Double.MAX_VALUE;

		TrackNode path1Disallow = (requireDirection && directionPreference == 1) ? n2 : null;
		TrackNode path2Disallow = (requireDirection && directionPreference == 2) ? n1 : null;

		if (!approaches.isEmpty()) {
			for (StationApproach approach : approaches) {
				List<TrackNode> path1 = withStationEdge(
						findPathAvoidingEdge(
								graph,
								n1,
								approach.approach,
								approach.approach,
								approach.depart,
								path1Disallow
						),
						approach.approach,
						approach.depart
				);

				List<TrackNode> path2 = withStationEdge(
						findPathAvoidingEdge(
								graph,
								n2,
								approach.approach,
								approach.approach,
								approach.depart,
								path2Disallow
						),
						approach.approach,
						approach.depart
				);

				if (path1.isEmpty() && path2.isEmpty()) {
					path1 = withStationEdge(
							findPath(
									graph,
									n1,
									approach.approach,
									path1Disallow
							),
							approach.approach,
							approach.depart
					);

					path2 = withStationEdge(
							findPath(
									graph,
									n2,
									approach.approach,
									path2Disallow
							),
							approach.approach,
							approach.depart
					);
				}

				Candidate c1 = evaluatePathCandidate(
						graph,
						point,
						path1,
						targetStation,
						currentStation,
						directionPreference
				);

				if (acceptCandidate(c1, requireDirection, requireValid) && c1.score() < bestScore) {
					bestScore = c1.score();
					bestPath = c1.path();
				}

				Candidate c2 = evaluatePathCandidate(
						graph,
						point,
						path2,
						targetStation,
						currentStation,
						directionPreference
				);

				if (acceptCandidate(c2, requireDirection, requireValid) && c2.score() < bestScore) {
					bestScore = c2.score();
					bestPath = c2.path();
				}
			}

			return bestPath;
		}

		TrackNode targetNode = findNearestNodeToStation(graph, targetStation);
		if (targetNode == null) return bestPath;

		List<TrackNode> path1 = findPath(graph, n1, targetNode, path1Disallow);
		List<TrackNode> path2 = findPath(graph, n2, targetNode, path2Disallow);

		Candidate c1 = evaluatePathCandidate(
				graph,
				point,
				path1,
				targetStation,
				currentStation,
				directionPreference
		);

		if (acceptCandidate(c1, requireDirection, requireValid) && c1.score() < bestScore) {
			bestScore = c1.score();
			bestPath = c1.path();
		}

		Candidate c2 = evaluatePathCandidate(
				graph,
				point,
				path2,
				targetStation,
				currentStation,
				directionPreference
		);

		if (acceptCandidate(c2, requireDirection, requireValid) && c2.score() < bestScore) {
			bestScore = c2.score();
			bestPath = c2.path();
		}

		return bestPath;
	}

	private List<TrackNode> buildBestStationPath(TrackGraph graph, TravellingPoint point,
												 TrackNode n1, TrackNode n2,
												 GlobalStation targetStation,
												 @Nullable GlobalStation currentStation,
												 int directionPreference) {
		List<StationApproach> approaches = getStationApproaches(graph, targetStation);

		List<TrackNode> result;

		result = tryBuildBestStationPath(
				graph, point, n1, n2, targetStation, currentStation,
				directionPreference, approaches, true, true
		);

		if (!result.isEmpty()) return result;

		result = tryBuildBestStationPath(
				graph, point, n1, n2, targetStation, currentStation,
				directionPreference, approaches, false, true
		);

		if (!result.isEmpty()) return result;

		result = tryBuildBestStationPath(
				graph, point, n1, n2, targetStation, currentStation,
				directionPreference, approaches, true, false
		);

		if (!result.isEmpty()) return result;

		return tryBuildBestStationPath(
				graph, point, n1, n2, targetStation, currentStation,
				directionPreference, approaches, false, false
		);
	}

	private void setStoragePortsActive(ServerLevel level, Set<PhysicsBogeyBlockEntity> consist,
									   @Nullable GlobalStation station, boolean active) {
		if (active == storagePortsActive) return;

		storagePortsActive = active;

		forEachStorageBlockEntity(level, consist, station, be -> activateStoragePort(level, be, active));

		level.updateNeighborsAt(worldPosition, getBlockState().getBlock());

		setChanged();
		sendData();
	}

	private void forEachStorageBlockEntity(ServerLevel level, Set<PhysicsBogeyBlockEntity> consist,
										   @Nullable GlobalStation station, Consumer<BlockEntity> consumer) {
		List<BlockPos> centers = new ArrayList<>();

		centers.add(worldPosition);

		for (PhysicsBogeyBlockEntity bogey : consist) {
			centers.add(bogey.getBlockPos());
		}

		if (station != null) {
			centers.add(station.getBlockEntityPos());
		}

		Set<BlockPos> scanned = new HashSet<>();
		int radius = 16;

		for (BlockPos center : centers) {
			BlockPos min = center.offset(-radius, -radius, -radius);
			BlockPos max = center.offset(radius, radius, radius);

			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				BlockPos immutable = new BlockPos(pos.getX(), pos.getY(), pos.getZ());

				if (!scanned.add(immutable)) continue;

				BlockEntity be = level.getBlockEntity(immutable);
				if (be == null) continue;

				if (be instanceof DockingConnectorBlockEntity) continue;

				if (be instanceof IStoragePortActivatable || isStoragePortBlockEntity(be)) {
					consumer.accept(be);
				}
			}
		}
	}

	private boolean isStoragePortBlockEntity(BlockEntity be) {
		if (be instanceof DockingConnectorBlockEntity) {
			return false;
		}

		String name = be.getClass().getName().toLowerCase();

		if (name.contains("dockingconnector")
				|| name.contains("dockconnector")
				|| (name.contains("docking") && name.contains("connector"))) {
			return false;
		}

		return name.contains("portablestorageinterface")
				|| name.contains("portablefluidinterface")
				|| name.contains("airshipstation");
	}

	private void activateStoragePort(ServerLevel level, BlockEntity be, boolean active) {
		if (be instanceof IStoragePortActivatable activatable) {
			try {
				activatable.setStoragePortActive(active);
				return;
			} catch (Throwable ignored) {
			}
		}

		try {
			for (Method method : be.getClass().getMethods()) {
				if (method.getParameterCount() != 1) continue;

				Class<?> param = method.getParameterTypes()[0];
				if (param != boolean.class && param != Boolean.class) continue;

				String name = method.getName().toLowerCase();

				if (name.equals("setactive")
						|| name.equals("setpowered")
						|| name.equals("setactivated")
						|| name.equals("setenabled")
						|| name.equals("setworking")
						|| name.equals("settransfer")
						|| name.equals("settransferring")
						|| name.equals("setconnected")
						|| name.equals("setdocked")
						|| name.equals("setdocking")
						|| name.equals("setengaged")
						|| name.equals("setlinked")
						|| name.equals("setonline")
						|| name.equals("setoperating")
						|| name.equals("setrunning")) {
					method.invoke(be, active);
					finishStoragePortUpdate(level, be);
					return;
				}

				if (active && (name.equals("activate")
						|| name.equals("extend")
						|| name.equals("connect")
						|| name.equals("dock")
						|| name.equals("enable")
						|| name.equals("engage")
						|| name.equals("link")
						|| name.equals("start")
						|| name.equals("starttransfer"))) {
					method.invoke(be, true);
					finishStoragePortUpdate(level, be);
					return;
				}

				if (!active && (name.equals("deactivate")
						|| name.equals("release")
						|| name.equals("disconnect")
						|| name.equals("undock")
						|| name.equals("disable")
						|| name.equals("disengage")
						|| name.equals("unlink")
						|| name.equals("stop")
						|| name.equals("stoptransfer"))) {
					method.invoke(be, false);
					finishStoragePortUpdate(level, be);
					return;
				}
			}

			for (Method method : be.getClass().getMethods()) {
				if (method.getParameterCount() != 0) continue;

				String name = method.getName().toLowerCase();

				if (active && (name.equals("activate")
						|| name.equals("extend")
						|| name.equals("connect")
						|| name.equals("dock")
						|| name.equals("enable")
						|| name.equals("engage")
						|| name.equals("link")
						|| name.equals("start")
						|| name.equals("starttransfer"))) {
					method.invoke(be);
					finishStoragePortUpdate(level, be);
					return;
				}

				if (!active && (name.equals("deactivate")
						|| name.equals("release")
						|| name.equals("disconnect")
						|| name.equals("undock")
						|| name.equals("disable")
						|| name.equals("disengage")
						|| name.equals("unlink")
						|| name.equals("stop")
						|| name.equals("stoptransfer"))) {
					method.invoke(be);
					finishStoragePortUpdate(level, be);
					return;
				}
			}

			if (active) {
				for (Method method : be.getClass().getMethods()) {
					if (method.getParameterCount() != 0) continue;

					String name = method.getName().toLowerCase();

					if (name.equals("redstonepulse")
							|| name.equals("pulse")
							|| name.equals("onredstone")
							|| name.equals("onredstonesignal")
							|| name.equals("redstonechanged")
							|| name.equals("onpowered")) {
						method.invoke(be);
						finishStoragePortUpdate(level, be);
						return;
					}
				}
			}

			if (trySetBooleanProperty(level, be, active,
					"powered", "active", "activated", "enabled", "connected",
					"docked", "docking", "engaged", "linked", "online",
					"operating", "running", "working", "transfer", "transferring")) {
				finishStoragePortUpdate(level, be);
				return;
			}
		} catch (Throwable ignored) {
		}

		finishStoragePortUpdate(level, be);
	}

	private boolean trySetBooleanProperty(ServerLevel level, BlockEntity be, boolean active, String... names) {
		BlockState state = be.getBlockState();
		Set<String> wanted = new HashSet<>(Arrays.asList(names));

		boolean changed = false;

		for (Property<?> prop : state.getProperties()) {
			if (prop.getValueClass() == Boolean.class && wanted.contains(prop.getName().toLowerCase())) {
				@SuppressWarnings("unchecked")
				Property<Boolean> boolProp = (Property<Boolean>) prop;

				if (state.getValue(boolProp) != active) {
					level.setBlock(be.getBlockPos(), state.setValue(boolProp, active), 3);
					changed = true;
				}
			}
		}

		return changed;
	}

	private void finishStoragePortUpdate(ServerLevel level, BlockEntity be) {
		try {
			be.setChanged();

			BlockState state = be.getBlockState();
			level.sendBlockUpdated(be.getBlockPos(), state, state, 3);
			level.updateNeighborsAt(be.getBlockPos(), state.getBlock());
		} catch (Throwable ignored) {
		}
	}

	public Component getStatusLine() {
		if (scheduleStack.isEmpty()) {
			return Component.translatable("simurail.navigation_controller.no_schedule");
		}

		if (debugInfo != null && !debugInfo.isEmpty()) {
			return Component.literal(debugInfo);
		}

		if (lastMatchedStationName == null) {
			return Component.translatable("simurail.navigation_controller.unknown_destination");
		}

		return arrivedAtDestination
				? Component.translatable("simurail.navigation_controller.holding", lastMatchedStationName)
				: Component.translatable("simurail.navigation_controller.en_route", lastMatchedStationName);
	}

	public ItemStack getSchedule() {
		return scheduleStack;
	}

	public void setSchedule(ItemStack stack) {
		this.scheduleStack = stack;

		this.currentSpeedMultiplier = 0.0f;
		this.directionSign = 1;

		this.lastDistance = Double.NaN;
		this.currentPath = Collections.emptyList();

		this.currentStation = null;
		this.lastStationEdge = null;

		this.lastMovingTowardsNode2 = true;
		this.hasLastMovingDirection = false;

		this.lastTravelDir = Vec3.ZERO;
		this.hasDirectionSignBeenSet = false;
		this.lastSourceSign = 1.0;
		this.lastForwardSign = 0;
		this.departureHoldTicks = 0;
		this.hasDeparted = false;
		this.dockUndockDelayTicks = 0;
		this.lastRedstoneOutput = false;
		this.currentTarget = null;
		this.cachedTrain = null;
		this.lastConditionTickTime = -1;

		this.debugInfo = "";

		if (level instanceof ServerLevel sl) {
			deactivateDockingConnectors(sl);
			setStoragePortsActive(sl, Collections.<PhysicsBogeyBlockEntity>emptySet(), null, false);
			updateCustomStationPresence(sl, null);
		}

		this.storagePortsActive = false;
		this.activatedDockingConnectors.clear();
		this.dockingConnectorsActive = false;

		if (level != null) {
			CompoundTag tag = stack.get(AllDataComponents.TRAIN_SCHEDULE);
			Schedule schedule = tag != null ? Schedule.fromTag(level.registryAccess(), tag) : new Schedule();

			this.currentEntry = schedule.entries.isEmpty()
					? 0
					: Math.floorMod(schedule.savedProgress, schedule.entries.size());
		} else {
			this.currentEntry = 0;
		}

		resetConditionProgress();

		setChanged();
		sendData();
	}

	@Override
	public void remove() {
		if (level instanceof ServerLevel sl) {
			deactivateDockingConnectors(sl);

			if (storagePortsActive) {
				storagePortsActive = false;
				forEachStorageBlockEntity(sl, Collections.<PhysicsBogeyBlockEntity>emptySet(), null,
						be -> activateStoragePort(sl, be, false));
			}

			updateCustomStationPresence(sl, null);
		}

		super.remove();
	}

	/**
	 * Kept only for source compatibility with older renderers/packets.
	 * Storage overlay particles have been removed.
	 */
	public List<BlockPos> getStorageOverlayPositions() {
		return Collections.emptyList();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);

		if (!scheduleStack.isEmpty()) {
			tag.put("ScheduleStack", scheduleStack.save(registries));
		}

		tag.putInt("CurrentEntry", currentEntry);
		tag.putBoolean("Arrived", arrivedAtDestination);
		tag.putFloat("Multiplier", currentSpeedMultiplier);
		tag.putInt("DirectionSign", directionSign);
		tag.putBoolean("HasDeparted", hasDeparted);
		tag.putInt("DockUndockDelayTicks", dockUndockDelayTicks);
		tag.putBoolean("LastRedstoneOutput", lastRedstoneOutput);

		tag.putBoolean("LastMovingTowardsNode2", lastMovingTowardsNode2);
		tag.putBoolean("HasLastMovingDirection", hasLastMovingDirection);

		tag.putBoolean("HasDirectionSignBeenSet", hasDirectionSignBeenSet);
		tag.putDouble("LastSourceSign", lastSourceSign);
		tag.putInt("LastForwardSign", lastForwardSign);

		tag.putDouble("LastTravelDirX", lastTravelDir.x);
		tag.putDouble("LastTravelDirY", lastTravelDir.y);
		tag.putDouble("LastTravelDirZ", lastTravelDir.z);

		tag.putBoolean("StoragePortsActive", storagePortsActive);

		ListTag progressTag = new ListTag();
		for (int p : conditionProgress) {
			progressTag.add(IntTag.valueOf(p));
		}
		tag.put("ConditionProgress", progressTag);

		ListTag contextTag = new ListTag();
		for (CompoundTag c : conditionContext) {
			contextTag.add(c);
		}
		tag.put("ConditionContext", contextTag);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);

		scheduleStack = tag.contains("ScheduleStack")
				? ItemStack.parse(registries, tag.getCompound("ScheduleStack")).orElse(ItemStack.EMPTY)
				: ItemStack.EMPTY;

		currentEntry = tag.getInt("CurrentEntry");
		arrivedAtDestination = tag.getBoolean("Arrived");
		currentSpeedMultiplier = tag.getFloat("Multiplier");

		directionSign = tag.contains("DirectionSign") ? tag.getInt("DirectionSign") : 1;
		hasDeparted = tag.contains("HasDeparted") && tag.getBoolean("HasDeparted");

		if (tag.contains("HasLastMovingDirection")) {
			hasLastMovingDirection = tag.getBoolean("HasLastMovingDirection");
			lastMovingTowardsNode2 = !tag.contains("LastMovingTowardsNode2") || tag.getBoolean("LastMovingTowardsNode2");
		} else {
			hasLastMovingDirection = false;
			lastMovingTowardsNode2 = true;
		}

		hasDirectionSignBeenSet = tag.contains("HasDirectionSignBeenSet") && tag.getBoolean("HasDirectionSignBeenSet");
		lastSourceSign = tag.contains("LastSourceSign") ? tag.getDouble("LastSourceSign") : 1.0;
		lastForwardSign = tag.contains("LastForwardSign") ? tag.getInt("LastForwardSign") : 0;

		if (tag.contains("LastTravelDirX")) {
			lastTravelDir = new Vec3(
					tag.getDouble("LastTravelDirX"),
					tag.getDouble("LastTravelDirY"),
					tag.getDouble("LastTravelDirZ")
			);
		} else {
			lastTravelDir = Vec3.ZERO;
		}

		storagePortsActive = tag.contains("StoragePortsActive") && tag.getBoolean("StoragePortsActive");

		conditionProgress.clear();
		for (Tag t : tag.getList("ConditionProgress", Tag.TAG_INT)) {
			conditionProgress.add(((IntTag) t).getAsInt());
		}

		conditionContext.clear();
		for (Tag t : tag.getList("ConditionContext", Tag.TAG_COMPOUND)) {
			conditionContext.add((CompoundTag) t);
		}

		currentStation = null;
		lastStationEdge = null;

		activatedDockingConnectors.clear();
		activeDockingPairs.clear();
		dockingConnectorsActive = false;
		dockUndockDelayTicks = 0;
		lastRedstoneOutput = false;
		debugInfo = "";
	}
}