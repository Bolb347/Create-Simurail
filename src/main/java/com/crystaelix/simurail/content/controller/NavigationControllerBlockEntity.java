package com.crystaelix.simurail.content.controller;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import org.jetbrains.annotations.Nullable;

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
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import net.createmod.catnip.data.Couple;

import com.crystaelix.simurail.api.controller.ICustomStationPresence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class NavigationControllerBlockEntity extends SplitShaftBlockEntity {

	private static final int EVALUATE_INTERVAL = 5;
	private static final double BRAKE_START_DISTANCE = 128.0;
	private static final double FULL_STOP_DISTANCE = 1.0;
	private static final double ARRIVAL_HOLD_DISTANCE = 2.0;
	private static final double MAX_DECELERATION = 0.4;

	private ItemStack scheduleStack = ItemStack.EMPTY;

	private int tickCounter = 0;
	private int currentEntry = 0;

	private boolean arrivedAtDestination = false;

	private List<Integer> conditionProgress = new ArrayList<>();
	private List<CompoundTag> conditionContext = new ArrayList<>();

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
	@Nullable
	private Target currentTarget = null;

	@Nullable
	private Train cachedTrain = null;

	private long lastConditionTickTime = -1;

	private boolean storagePortsActive = false;

	private boolean dockingConnectorsActive = false;
	private final Set<ConnectorRef> activatedDockingConnectors = new HashSet<>();

	private record ConnectorRef(ServerLevel level, BlockPos pos) {}

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
		return (arrivedAtDestination || storagePortsActive) ? 15 : 0;
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

		if (cachedTrain == null || level.getGameTime() % 20 == 0) {
			cachedTrain = findTrain(level, currentTarget.station, anchorBogey);
		}

		if (tickConditionsOnce(level, currentTarget.entry, cachedTrain, currentTarget.station)) {
			advanceEntry(currentTarget.schedule, level.registryAccess());
			deactivateDockingConnectors(level);
		}
	}

	private boolean tickConditionsOnce(ServerLevel level, ScheduleEntry entry, @Nullable Train train, GlobalStation station) {
		long gameTime = level.getGameTime();

		if (gameTime == lastConditionTickTime) {
			return false;
		}

		lastConditionTickTime = gameTime;

		return tickConditions(level, entry, train, station);
	}

	private void evaluateAndDrive() {
		if (!(level instanceof ServerLevel serverLevel)) return;

		PhysicsBogeyBlockEntity anchorBogey = findAdjacentBogey();
		if (anchorBogey == null) {
			clearOverridesOnConsist();
			setSpeedMultiplier(0.0f);
			return;
		}

		Set<PhysicsBogeyBlockEntity> consist = traverseConsist(anchorBogey);

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
		boolean stationHoldActive = false;

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

						boolean shouldArrive = distance < FULL_STOP_DISTANCE
								|| (arrivedAtDestination && distance < ARRIVAL_HOLD_DISTANCE);

						if (shouldArrive) {
							currentStation = target.station;
							lastStationEdge = targetStationEdge != null ? targetStationEdge : point.edge;

							arrivedAtDestination = true;

							cachedTrain = findTrain(serverLevel, target.station, anchorBogey);

							if (tickConditionsOnce(serverLevel, target.entry, cachedTrain, target.station)) {
								advanceEntry(target.schedule, level.registryAccess());
								deactivateDockingConnectors(serverLevel);
								setStoragePortsActive(serverLevel, consist, target.station, false);
							}

							newMultiplier = 0.0f;
							brakeStrength = 1.0;
						} else {
							arrivedAtDestination = false;
							resetConditionProgress();

							double currentSpeed = Math.abs(anchorBogey.getMovementSpeed());
							double stoppingDistance = (currentSpeed * currentSpeed) / (2.0 * MAX_DECELERATION);
							double brakeStart = Math.min(stoppingDistance + FULL_STOP_DISTANCE, BRAKE_START_DISTANCE);

							double maxLinearSpeed = targetRPM / 16.0;
							double safeSpeed = Math.sqrt(2.0 * MAX_DECELERATION * Math.max(0.0, distance - FULL_STOP_DISTANCE));
							double desiredSpeed = Math.min(maxLinearSpeed, safeSpeed);

							double speedFraction = Math.clamp(desiredSpeed / maxLinearSpeed, 0.0, 1.0);

							newMultiplier = (float) (speedFraction * gearRatio * this.directionSign);

							if (Math.abs(newMultiplier) < 0.01f) {
								newMultiplier = 0.0f;
							}

							if (distance <= brakeStart) {
								double brakeRamp = (distance - FULL_STOP_DISTANCE)
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

						if (currentStation != null && lastStationEdge != null && sameEdge(point.edge, lastStationEdge)) {
							stationHoldActive = true;
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
		}

		boolean shouldDock = arrivedAtDestination;

		setStoragePortsActive(
				serverLevel,
				consist,
				target != null ? target.station : null,
				shouldDock
		);

		updateDockingConnectors(
				serverLevel,
				consist,
				target != null ? target.station : null,
				shouldDock
		);

		PhysicsBogeyBlockEntity frontBogey = null;

		if (hasValidPath && n1ForSteering != null && n2ForSteering != null) {
			Vec3 edgeForward = n2ForSteering.getLocation().getLocation()
					.subtract(n1ForSteering.getLocation().getLocation())
					.normalize();

			Vec3 travelDir = movingTowardsNode2ForSteering ? edgeForward : edgeForward.scale(-1);

			double maxDot = -Double.MAX_VALUE;

			for (PhysicsBogeyBlockEntity bogey : consist) {
				Vec3 bogeyPos = Vec3.atCenterOf(bogey.getBlockPos());
				double dot = bogeyPos.dot(travelDir);

				if (dot > maxDot) {
					maxDot = dot;
					frontBogey = bogey;
				}
			}
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
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
		}
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

			for (Train train : Create.RAILWAYS.trains.values()) {
				if (train == null) continue;

				try {
					if (train.graph == graph) {
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

	private void updateDockingConnectors(ServerLevel level, Set<PhysicsBogeyBlockEntity> consist,
										 @Nullable GlobalStation station, boolean active) {
		if (active) {
			Set<BlockPos> seen = new HashSet<>();
			List<BlockPos> centers = new ArrayList<>();

			centers.add(worldPosition);

			for (PhysicsBogeyBlockEntity bogey : consist) {
				centers.add(bogey.getBlockPos());
			}

			if (station != null) {
				centers.add(station.getBlockEntityPos());
			}

			int radius = 16;

			for (BlockPos center : centers) {
				BlockPos min = center.offset(-radius, -radius, -radius);
				BlockPos max = center.offset(radius, radius, radius);

				for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
					BlockPos immutable = new BlockPos(pos.getX(), pos.getY(), pos.getZ());

					if (!seen.add(immutable)) continue;

					if (level.getBlockEntity(immutable) instanceof DockingConnectorBlockEntity connector) {
						setDockingConnectorActive(connector, true);
						activatedDockingConnectors.add(new ConnectorRef(level, immutable));
					}
				}
			}

			dockingConnectorsActive = true;
		} else if (dockingConnectorsActive || !activatedDockingConnectors.isEmpty()) {
			deactivateDockingConnectors(level);
		}
	}

	private void deactivateDockingConnectors(ServerLevel level) {
		for (ConnectorRef ref : activatedDockingConnectors) {
			if (ref.level().getBlockEntity(ref.pos()) instanceof DockingConnectorBlockEntity connector) {
				setDockingConnectorActive(connector, false);
			}
		}

		activatedDockingConnectors.clear();
		dockingConnectorsActive = false;
	}

	private void setDockingConnectorActive(DockingConnectorBlockEntity connector, boolean active) {
		if (!(connector.getLevel() instanceof ServerLevel connectorLevel)) return;

		BlockPos pos = connector.getBlockPos();
		BlockState state = connectorLevel.getBlockState(pos);

		if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED) != active) {
			connectorLevel.setBlock(pos, state.setValue(BlockStateProperties.POWERED, active), 3);
			state = connectorLevel.getBlockState(pos);
		}

		connector.powered = active;

		if (!active) {
			try {
				connector.setVirtualLock(false);
			} catch (Throwable ignored) {
			}

			try {
				connector.unDock();
			} catch (Throwable ignored) {
			}
		}

		connector.setChanged();

		connectorLevel.sendBlockUpdated(pos, state, connectorLevel.getBlockState(pos), 3);
		connectorLevel.updateNeighborsAt(pos, state.getBlock());
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

	private boolean tickConditions(ServerLevel level, ScheduleEntry entry, @Nullable Train train, GlobalStation station) {
		List<List<ScheduleWaitCondition>> columns = entry.conditions;

		while (conditionProgress.size() < columns.size()) {
			conditionProgress.add(0);
			conditionContext.add(new CompoundTag());
		}

		for (int i = 0; i < columns.size(); i++) {
			List<ScheduleWaitCondition> column = columns.get(i);

			int progress = conditionProgress.get(i);
			if (progress >= column.size()) return true;

			if (evaluateCondition(column.get(progress), level, conditionContext.get(i), train, station)) {
				conditionProgress.set(i, progress + 1);

				if (progress + 1 >= column.size()) {
					return true;
				}
			}
		}

		return false;
	}

	private boolean evaluateCondition(ScheduleWaitCondition condition, ServerLevel level, CompoundTag context,
									  @Nullable Train train, GlobalStation station) {
		if (condition instanceof StationPoweredCondition) {
			return level.hasNeighborSignal(station.getBlockEntityPos());
		}

		try {
			return condition.tickCompletion(level, train, context);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void advanceEntry(Schedule schedule, HolderLookup.Provider registries) {
		currentEntry = schedule.cyclic
				? Math.floorMod(currentEntry + 1, schedule.entries.size())
				: Math.min(currentEntry + 1, schedule.entries.size() - 1);

		arrivedAtDestination = false;

		resetConditionProgress();

		lastDistance = Double.NaN;

		currentPath = Collections.emptyList();

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
		String name = be.getClass().getName().toLowerCase();

		return name.contains("dockingconnector")
				|| name.contains("dockconnector")
				|| name.contains("portablestorageinterface")
				|| name.contains("portablefluidinterface")
				|| name.contains("airshipstation")
				|| (name.contains("docking") && name.contains("connector"));
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

		this.currentTarget = null;
		this.cachedTrain = null;
		this.lastConditionTickTime = -1;

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

		if (level instanceof ServerLevel sl) {
			updateCustomStationPresence(sl, null);
		}

		resetConditionProgress();

		setChanged();
		sendData();
	}

	@Override
	public void remove() {
		if (level instanceof ServerLevel sl) {
			updateCustomStationPresence(sl, null);
		}

		super.remove();
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
		dockingConnectorsActive = false;
	}
}