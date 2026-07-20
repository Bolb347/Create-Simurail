package com.crystaelix.simurail.content.controller;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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

import net.minecraft.world.phys.Vec3;

import java.util.List;

import net.createmod.catnip.data.Couple;

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
import net.minecraft.world.level.block.state.BlockState;

public class NavigationControllerBlockEntity extends SplitShaftBlockEntity {

	private static final int EVALUATE_INTERVAL = 5;

	private static final double BRAKE_START_DISTANCE = 128.0;
	private static final double FULL_STOP_DISTANCE = 1.0;
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

	private GlobalStation cachedStation = null;
	private TrackNode cachedTargetNode = null;

	@Nullable
	private String lastMatchedStationName;

	@Nullable
	private GlobalStation currentStation = null;

	@Nullable
	private TrackEdge lastStationEdge = null;

	private boolean needsDirectionCorrection = false;

	public NavigationControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public float getRotationSpeedModifier(Direction face) {
		if (!hasSource()) return 0f;
		if (face == getSourceFacing()) return 1.0f;
		return currentSpeedMultiplier;
	}

	public int getRedstoneSignal() {
		return arrivedAtDestination ? 15 : 0;
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

	private double computeSteerForBogey(PhysicsBogeyBlockEntity bogey, TrackGraph graph,
										List<TrackNode> path, TrackNode n1, TrackNode n2,
										boolean movingTowardsNode2, GlobalStation targetStation) {
		PhysicsBogeyAxle axle = findAnchorAxle(bogey);
		if (axle == null) return 0.0;

		TravellingPoint point = axle.getTrackPoint();
		if (point == null || point.edge == null) return 0.0;

		int idx1 = path.indexOf(point.edge.node1);
		int idx2 = path.indexOf(point.edge.node2);

		if (idx1 == -1 && idx2 == -1) return 0.0;

		boolean bogeyMovingTowardsNode2 = idx2 > idx1 || (idx1 == -1 && idx2 != -1);

		int startIdx;
		if (bogeyMovingTowardsNode2) {
			startIdx = idx2;
		} else {
			startIdx = idx1;
		}

		if (startIdx < 0) {
			startIdx = Math.max(idx1, idx2);
		}

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

		tickCounter++;
		if (tickCounter < EVALUATE_INTERVAL) return;

		tickCounter = 0;
		evaluateAndDrive();
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

		Target target = resolveDestination(serverLevel);

		double targetRPM = (maxSpeedScroll != null) ? Math.min(maxSpeedScroll.getValue(), 256.0) : 32.0;

		double inputSpeed = getSpeed();
		double absInputSpeed = Math.abs(inputSpeed);

		// Use baseline speed so gearRatio is never 0 when the train is stopped.
		double baselineSpeed = Math.max(absInputSpeed, 16.0);
		double gearRatio = targetRPM / baselineSpeed;
		double maxSafeRatio = 256.0 / baselineSpeed;
		gearRatio = Math.min(gearRatio, maxSafeRatio);

		double brakeStrength = 0.0;
		float newMultiplier = 0.0f;

		boolean hasValidPath = false;

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
					} else {
						movingTowardsNode2 = this.directionSign > 0;
					}

					int directionPreference = 0;
					if (Math.abs(trackSpeed) > 0.05) {
						directionPreference = ((trackSpeed > 0.0) ^ trackReversed) ? 2 : 1;
					}

					TrackEdge targetStationEdge = getStationEdge(graph, target.station);

					// Clear currentStation only when we are no longer on that station's actual edge.
					if (currentStation != null) {
						TrackEdge oldStationEdge = getStationEdge(graph, currentStation);
						if (oldStationEdge == null || !sameEdge(point.edge, oldStationEdge)) {
							currentStation = null;
							lastStationEdge = null;
						}
					}

					boolean onTargetStationEdge = targetStationEdge != null && sameEdge(point.edge, targetStationEdge);

					// If we are already on the target station edge, force the path to point toward
					// the actual station stop. This is especially important for bidirectional stations
					// and dead-end stations.
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
					} else if (currentPath.isEmpty() || !isCurrentEdgeOnPath(currentPath, point.edge)) {
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

					boolean usablePath = !currentPath.isEmpty() && isCurrentEdgeOnPath(currentPath, point.edge);

					if (usablePath) {
						int idx1 = currentPath.indexOf(n1);
						int idx2 = currentPath.indexOf(n2);

						if (idx1 != -1 && idx2 != -1) {
							movingTowardsNode2 = idx2 > idx1;
						} else {
							movingTowardsNode2 = (idx2 != -1);
						}

						// If we are on the target station edge, make absolutely sure we are heading
						// toward the station stop, not away from it.
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

						int desiredSign = computeDirectionSign(
								edgeForward,
								axleForward,
								movingTowardsNode2,
								sourceSign
						);

						boolean hasActualDirection = Math.abs(trackSpeed) > 0.05;
						if (hasActualDirection) {
							boolean actualTowardsNode2 = (trackSpeed > 0.0) ^ trackReversed;
							if (actualTowardsNode2 != movingTowardsNode2) {
								mustStopToReverse = true;
							}
						}

						this.directionSign = desiredSign;
						this.needsDirectionCorrection = false;

						int startIdx = movingTowardsNode2 ? idx2 : idx1;
						if (startIdx < 0) startIdx = Math.max(idx1, idx2);
						if (startIdx < 0) startIdx = 0;

						List<TrackNode> remainingPath = currentPath.subList(startIdx, currentPath.size());

						double distance = calculatePathDistance(
								graph,
								remainingPath,
								point,
								movingTowardsNode2,
								target.station
						);

						if (distance < FULL_STOP_DISTANCE) {
							currentStation = target.station;
							lastStationEdge = targetStationEdge != null ? targetStationEdge : point.edge;

							arrivedAtDestination = true;

							Train presentTrain = findTrain(serverLevel, target.station, anchorBogey);

							if (tickConditions(serverLevel, target.entry, presentTrain, target.station)) {
								advanceEntry(target.schedule, level.registryAccess());
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

						n1ForSteering = n1;
						n2ForSteering = n2;
						pathForSteering = currentPath;

						hasValidPath = true;
					}
				}
			}

			if (!hasValidPath) {
				newMultiplier = 0.0f;
				brakeStrength = 1.0;
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
		}

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

		if (level != null && !level.isClientSide()) {
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
		}
	}

	@Nullable
	private Train findTrain(ServerLevel level, GlobalStation station, PhysicsBogeyBlockEntity anchorBogey) {
		Train present = station.getPresentTrain();
		if (present != null) return present;

		TrackGraph graph = null;

		PhysicsBogeyAxle axle = findAnchorAxle(anchorBogey);
		if (axle != null) graph = axle.getTrackGraph();

		if (graph != null) {
			for (Train train : Create.RAILWAYS.trains.values()) {
				if (train.graph == graph && train.navigation.destination == station) {
					return train;
				}
			}

			for (Train train : Create.RAILWAYS.trains.values()) {
				if (train.graph == graph) return train;
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

	private TrackNode getTargetNode(TrackGraph graph, GlobalStation station) {
		if (station == cachedStation && cachedTargetNode != null) return cachedTargetNode;

		cachedStation = station;
		cachedTargetNode = findNearestNodeToStation(graph, station);

		return cachedTargetNode;
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

				// canApproachFrom(n2) -> train heads towards n2, must arrive at n1 first.
				// canApproachFrom(n1) -> train heads towards n1, must arrive at n2 first.
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

	private List<TrackNode> findPath(TrackGraph graph, TrackNode startNode, TrackNode targetNode) {
		if (startNode == targetNode) return List.of(startNode);

		Map<TrackNode, TrackNode> cameFrom = new HashMap<>();
		Queue<TrackNode> queue = new LinkedList<>();

		queue.add(startNode);
		cameFrom.put(startNode, null);

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
												 TrackNode avoidA, TrackNode avoidB) {
		if (startNode == targetNode) return List.of(startNode);

		Map<TrackNode, TrackNode> cameFrom = new HashMap<>();
		Queue<TrackNode> queue = new LinkedList<>();

		queue.add(startNode);
		cameFrom.put(startNode, null);

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
										 boolean movingTowardsNode2, @Nullable GlobalStation targetStation) {
		if (path.isEmpty() || start.edge == null) return 0;

		TrackEdge stationEdge = null;
		double stationPosOnEdge = 0;

		if (targetStation != null) {
			stationEdge = getStationEdge(graph, targetStation);

			if (stationEdge != null) {
				stationPosOnEdge = targetStation.getLocationOn(stationEdge);
			}
		}

		// If we are already on the target station edge, distance is simply the distance
		// along that edge to the station stop.
		if (stationEdge != null && sameEdge(start.edge, stationEdge)) {
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

			// If the final edge is the target station edge, only count distance up to the station.
			if (i == path.size() - 2 && stationEdge != null && sameEdge(edge, stationEdge)) {
				if (p1 == stationEdge.node1) {
					edgeLength = stationPosOnEdge;
				} else {
					edgeLength = edge.getLength() - stationPosOnEdge;
				}
			}

			distance += edgeLength;
		}

		// If the path ends at one end of the station edge but does not include the station edge itself,
		// add the remaining distance from that end to the actual station stop.
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

	@Nullable
	private Target resolveDestination(ServerLevel level) {
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

		GlobalStation station = findMatchingStation(level, destination);
		return station == null ? null : new Target(schedule, entry, destination, station);
	}

	@Nullable
	private GlobalStation findMatchingStation(ServerLevel level, DestinationInstruction destination) {
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

				double distSq = station.getBlockEntityPos().distSqr(getBlockPos());

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

		return condition.tickCompletion(level, train, context);
	}

	private void advanceEntry(Schedule schedule, HolderLookup.Provider registries) {
		currentEntry = schedule.cyclic
				? Math.floorMod(currentEntry + 1, schedule.entries.size())
				: Math.min(currentEntry + 1, schedule.entries.size() - 1);

		arrivedAtDestination = false;

		resetConditionProgress();

		lastDistance = Double.NaN;
		directionSign = 1;

		currentPath = Collections.emptyList();

		cachedStation = null;
		cachedTargetNode = null;

		needsDirectionCorrection = true;

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
		if (station.edgeLocation == null) return null;

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

		// canApproachFrom(n2) means the train heads towards n2, so approach node is n1.
		if (canApproachTowardsN2) {
			approaches.add(new StationApproach(n1, n2));
		}

		// canApproachFrom(n1) means the train heads towards n1, so approach node is n2.
		if (canApproachTowardsN1) {
			approaches.add(new StationApproach(n2, n1));
		}

		// Fallback for assembling stations or unusual station states.
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

		if (path.contains(depart)) {
			return path;
		}

		List<TrackNode> out = new ArrayList<>(path);
		out.add(depart);

		return out;
	}

	private boolean isCurrentEdgeOnPath(List<TrackNode> path, TrackEdge edge) {
		if (path.isEmpty() || edge == null) return false;

		int idx1 = path.indexOf(edge.node1);
		int idx2 = path.indexOf(edge.node2);

		if (idx1 == -1 && idx2 == -1) return false;

		if (idx1 != -1 && idx2 != -1) {
			return Math.abs(idx1 - idx2) == 1;
		}

		int idx = idx1 != -1 ? idx1 : idx2;

		// Allow a single-node path boundary. This is useful when the train is on a station edge
		// and the path starts at the node it needs to move toward.
		return idx == 0 || idx == path.size() - 1;
	}

	private int computeDirectionSign(Vec3 edgeForward, Vec3 axleForward, boolean movingTowardsNode2, double sourceSign) {
		Vec3 desiredDir = movingTowardsNode2 ? edgeForward : edgeForward.scale(-1);

		int desiredBogeyRotation = desiredDir.dot(axleForward) >= 0 ? 1 : -1;

		int src = (int) sourceSign;
		if (src == 0) src = 1;

		return desiredBogeyRotation * src;
	}

	private List<TrackNode> buildBestStationPath(TrackGraph graph, TravellingPoint point,
												 TrackNode n1, TrackNode n2,
												 GlobalStation targetStation,
												 @Nullable GlobalStation currentStation,
												 int directionPreference) {
		List<StationApproach> approaches = getStationApproaches(graph, targetStation);

		List<TrackNode> bestValidPath = Collections.emptyList();
		double bestValidScore = Double.MAX_VALUE;

		List<TrackNode> bestInvalidPath = Collections.emptyList();
		double bestInvalidScore = Double.MAX_VALUE;

		if (!approaches.isEmpty()) {
			for (StationApproach approach : approaches) {
				List<TrackNode> path1 = withStationEdge(
						findPathAvoidingEdge(graph, n1, approach.approach, approach.approach, approach.depart),
						approach.approach,
						approach.depart
				);

				List<TrackNode> path2 = withStationEdge(
						findPathAvoidingEdge(graph, n2, approach.approach, approach.approach, approach.depart),
						approach.approach,
						approach.depart
				);

				if (path1.isEmpty() && path2.isEmpty()) {
					path1 = withStationEdge(
							findPath(graph, n1, approach.approach),
							approach.approach,
							approach.depart
					);

					path2 = withStationEdge(
							findPath(graph, n2, approach.approach),
							approach.approach,
							approach.depart
					);
				}

				if (!path1.isEmpty()) {
					boolean valid = pathRespectsAllStationDirections(
							graph,
							path1,
							point,
							false,
							targetStation,
							currentStation
					);

					double score = calculatePathDistance(
							graph,
							path1,
							point,
							false,
							targetStation
					);

					if (directionPreference == 1) {
						score *= 0.9;
					}

					if (valid) {
						if (score < bestValidScore) {
							bestValidScore = score;
							bestValidPath = path1;
						}
					} else {
						if (score < bestInvalidScore) {
							bestInvalidScore = score;
							bestInvalidPath = path1;
						}
					}
				}

				if (!path2.isEmpty()) {
					boolean valid = pathRespectsAllStationDirections(
							graph,
							path2,
							point,
							true,
							targetStation,
							currentStation
					);

					double score = calculatePathDistance(
							graph,
							path2,
							point,
							true,
							targetStation
					);

					if (directionPreference == 2) {
						score *= 0.9;
					}

					if (valid) {
						if (score < bestValidScore) {
							bestValidScore = score;
							bestValidPath = path2;
						}
					} else {
						if (score < bestInvalidScore) {
							bestInvalidScore = score;
							bestInvalidPath = path2;
						}
					}
				}
			}

			if (!bestValidPath.isEmpty()) return bestValidPath;
			if (!bestInvalidPath.isEmpty()) return bestInvalidPath;

			return Collections.emptyList();
		}

		TrackNode targetNode = findNearestNodeToStation(graph, targetStation);
		if (targetNode == null) return Collections.emptyList();

		List<TrackNode> path1 = findPath(graph, n1, targetNode);
		List<TrackNode> path2 = findPath(graph, n2, targetNode);

		if (!path1.isEmpty()) {
			boolean valid = pathRespectsAllStationDirections(
					graph,
					path1,
					point,
					false,
					targetStation,
					currentStation
			);

			double score = calculatePathDistance(
					graph,
					path1,
					point,
					false,
					targetStation
			);

			if (directionPreference == 1) {
				score *= 0.9;
			}

			if (valid) {
				if (score < bestValidScore) {
					bestValidScore = score;
					bestValidPath = path1;
				}
			} else {
				if (score < bestInvalidScore) {
					bestInvalidScore = score;
					bestInvalidPath = path1;
				}
			}
		}

		if (!path2.isEmpty()) {
			boolean valid = pathRespectsAllStationDirections(
					graph,
					path2,
					point,
					true,
					targetStation,
					currentStation
			);

			double score = calculatePathDistance(
					graph,
					path2,
					point,
					true,
					targetStation
			);

			if (directionPreference == 2) {
				score *= 0.9;
			}

			if (valid) {
				if (score < bestValidScore) {
					bestValidScore = score;
					bestValidPath = path2;
				}
			} else {
				if (score < bestInvalidScore) {
					bestInvalidScore = score;
					bestInvalidPath = path2;
				}
			}
		}

		if (!bestValidPath.isEmpty()) return bestValidPath;
		if (!bestInvalidPath.isEmpty()) return bestInvalidPath;

		return Collections.emptyList();
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

		this.cachedStation = null;
		this.cachedTargetNode = null;

		this.currentStation = null;
		this.lastStationEdge = null;

		this.needsDirectionCorrection = false;

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
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);

		if (!scheduleStack.isEmpty()) {
			tag.put("ScheduleStack", scheduleStack.save(registries));
		}

		tag.putInt("CurrentEntry", currentEntry);
		tag.putBoolean("Arrived", arrivedAtDestination);
		tag.putFloat("Multiplier", currentSpeedMultiplier);
		tag.putInt("DirectionSign", directionSign);

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
		needsDirectionCorrection = false;
	}
}