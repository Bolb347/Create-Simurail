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

	@Nullable private String lastMatchedStationName;

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
		int startIdx = bogeyMovingTowardsNode2 ? Math.max(idx2, idx1) : Math.max(idx1, idx2);

		Vec3 requiredDir = bogeyMovingTowardsNode2
				? point.edge.node2.getLocation().getLocation().subtract(point.edge.node1.getLocation().getLocation()).normalize()
				: point.edge.node1.getLocation().getLocation().subtract(point.edge.node2.getLocation().getLocation()).normalize();

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

		double gearRatio = 0.0;
		if (absInputSpeed >= 1.0) {
			gearRatio = targetRPM / absInputSpeed;
			double maxSafeRatio = 256.0 / absInputSpeed;
			gearRatio = Math.min(gearRatio, maxSafeRatio);
		}

		double brakeStrength = 0.0;
		float newMultiplier = 0.0f;
		boolean hasValidPath = false;
		TrackGraph graph = null;
		List<TrackNode> pathForSteering = currentPath;
		TrackNode n1ForSteering = null;
		TrackNode n2ForSteering = null;
		boolean movingTowardsNode2ForSteering = false;
		boolean movingTowardsNode2 = false;

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
					movingTowardsNode2 = (trackSpeed > 0.05) ^ trackReversed;
					boolean movingTowardsNode1 = (trackSpeed < -0.05) ^ trackReversed;

					if (Math.abs(trackSpeed) < 0.01) {
						movingTowardsNode2 = this.directionSign > 0;
					}

					Vec3 edgeForward = n2.getLocation().getLocation().subtract(n1.getLocation().getLocation()).normalize();
					Vec3 travelDir = movingTowardsNode2 ? edgeForward : edgeForward.scale(-1);
					Vec3 axleForward = trackReversed ? edgeForward.scale(-1) : edgeForward;

					double sourceSign = Math.signum(inputSpeed);
					if (sourceSign == 0) sourceSign = 1;

					this.directionSign = (travelDir.dot(axleForward) * sourceSign > 0) ? 1 : -1;

					TrackNode targetNode = getTargetNode(graph, target.station);
					if (targetNode != null) {
						TrackNode primaryNode = null, secondaryNode = null;
						if (target.station.edgeLocation != null) {
							TrackNode sN1 = graph.locateNode(target.station.edgeLocation.getFirst());
							TrackNode sN2 = graph.locateNode(target.station.edgeLocation.getSecond());
							if (sN1 != null && sN2 != null) {
								if (target.station.isPrimary(sN1)) {
									primaryNode = sN1;
									secondaryNode = sN2;
								} else if (target.station.isPrimary(sN2)) {
									primaryNode = sN2;
									secondaryNode = sN1;
								}
							}
						}
						if (secondaryNode == null) secondaryNode = targetNode;

						int idx1 = currentPath.indexOf(n1);
						int idx2 = currentPath.indexOf(n2);
						boolean onPath = (idx1 != -1 || idx2 != -1);

						if (currentPath.isEmpty() || !onPath) {
							List<TrackNode> path1, path2;

							if (primaryNode != null && secondaryNode != null) {
								path1 = findPathAvoidingEdge(graph, n1, secondaryNode, primaryNode, secondaryNode);
								path2 = findPathAvoidingEdge(graph, n2, secondaryNode, primaryNode, secondaryNode);
								if (path1.isEmpty() && path2.isEmpty()) {
									path1 = findPath(graph, n1, secondaryNode);
									path2 = findPath(graph, n2, secondaryNode);
								}
							} else {
								path1 = findPath(graph, n1, targetNode);
								path2 = findPath(graph, n2, targetNode);
							}

							boolean path1Valid = pathRespectsAllStationDirections(graph, path1, point, movingTowardsNode1, target.station);
							boolean path2Valid = pathRespectsAllStationDirections(graph, path2, point, movingTowardsNode2, target.station);

							if (movingTowardsNode2 && path2Valid && !path2.isEmpty()) {
								currentPath = path2;
							} else if (movingTowardsNode1 && path1Valid && !path1.isEmpty()) {
								currentPath = path1;
							} else if (path2Valid && !path2.isEmpty()) {
								currentPath = path2;
							} else if (path1Valid && !path1.isEmpty()) {
								currentPath = path1;
							} else {
								currentPath = (calculatePathDistance(graph, path1, point, movingTowardsNode1, target.station, secondaryNode)
										<= calculatePathDistance(graph, path2, point, movingTowardsNode2, target.station, secondaryNode))
										? path1 : path2;
							}

							this.lastDistance = Double.NaN;
							idx1 = currentPath.indexOf(n1);
							idx2 = currentPath.indexOf(n2);
						}

						if (!currentPath.isEmpty() && (idx1 != -1 || idx2 != -1)) {
							if (idx1 != -1 && idx2 != -1) {
								movingTowardsNode2 = idx2 > idx1;
							} else {
								movingTowardsNode2 = (idx2 != -1);
							}
							movingTowardsNode2ForSteering = movingTowardsNode2;

							int startIdx = movingTowardsNode2 ? idx2 : idx1;
							List<TrackNode> remainingPath = currentPath.subList(startIdx, currentPath.size());
							double distance = calculatePathDistance(graph, remainingPath, point, movingTowardsNode2, target.station, secondaryNode);

							if (distance < FULL_STOP_DISTANCE) {
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
									double brakeRamp = (distance - FULL_STOP_DISTANCE) / Math.max(0.01, brakeStart - FULL_STOP_DISTANCE);
									brakeStrength = Math.clamp(1.0 - brakeRamp, 0.0, 1.0);
								} else {
									brakeStrength = 0.0;
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
			}
		} else {
			arrivedAtDestination = false;
			lastDistance = Double.NaN;
			currentPath = Collections.emptyList();
			newMultiplier = 0.0f;
			brakeStrength = 1.0;
			this.directionSign = 1;
		}

		PhysicsBogeyBlockEntity frontBogey = null;
		if (hasValidPath && n1ForSteering != null && n2ForSteering != null) {
			Vec3 edgeForward = n2ForSteering.getLocation().getLocation().subtract(n1ForSteering.getLocation().getLocation()).normalize();
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
				double steer = computeSteerForBogey(bogey, graph, pathForSteering,
						n1ForSteering, n2ForSteering,
						movingTowardsNode2ForSteering, target.station);
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
				if (station.isPrimary(n1)) return n2;
				if (station.isPrimary(n2)) return n1;
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
			if (distSq < minDistSq) { minDistSq = distSq; nearest = node; }
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
				while (step != null) { path.add(0, step); step = cameFrom.get(step); }
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
						if (!traversesStationCorrectly(edge, movingTowards, station)) {
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

	private List<TrackNode> findPathAvoidingEdge(TrackGraph graph, TrackNode startNode, TrackNode targetNode, TrackNode avoidA, TrackNode avoidB) {
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
				while (step != null) { path.add(0, step); step = cameFrom.get(step); }
				return path;
			}
			Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(current);
			if (connections != null) {
				for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
					TrackNode nextNode = entry.getKey();
					TrackEdge edge = entry.getValue();

					if ((current == avoidA && nextNode == avoidB) || (current == avoidB && nextNode == avoidA))
						continue;

					GlobalStation station = findStationOnEdge(graph, edge, null);
					if (station != null) {
						boolean movingTowards = edge.node2.equals(nextNode);
						if (!traversesStationCorrectly(edge, movingTowards, station)) {
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
										 boolean movingTowardsNode2, @Nullable GlobalStation targetStation,
										 @Nullable TrackNode secondaryNode) {
		if (path.isEmpty() || start.edge == null) return 0;

		double distance;
		TrackEdge stationEdge = null;
		double stationPosOnEdge = 0;

		if (targetStation != null && targetStation.edgeLocation != null) {
			TrackNode sN1 = graph.locateNode(targetStation.edgeLocation.getFirst());
			TrackNode sN2 = graph.locateNode(targetStation.edgeLocation.getSecond());
			if (sN1 != null && sN2 != null) {
				stationEdge = graph.getConnection(Couple.create(sN1, sN2));
				if (stationEdge != null) {
					stationPosOnEdge = targetStation.getLocationOn(stationEdge);
				}
			}
		}

		if (stationEdge != null && start.edge != null &&
				((start.edge.node1 == stationEdge.node1 && start.edge.node2 == stationEdge.node2) ||
						(start.edge.node1 == stationEdge.node2 && start.edge.node2 == stationEdge.node1))) {
			double startPos = start.position;
			if (start.edge.node1 != stationEdge.node1) {
				startPos = start.edge.getLength() - start.position;
			}
			return Math.abs(startPos - stationPosOnEdge);
		}

		distance = movingTowardsNode2 ? (start.edge.getLength() - start.position) : start.position;

		for (int i = 0; i < path.size() - 1; i++) {
			TrackNode p1 = path.get(i);
			TrackNode p2 = path.get(i + 1);
			TrackEdge edge = graph.getConnection(Couple.create(p1, p2));
			double edgeLength = (edge != null) ? edge.getLength() : p1.getLocation().getLocation().distanceTo(p2.getLocation().getLocation());

			if (i == path.size() - 2 && stationEdge != null && edge != null &&
					((edge.node1 == stationEdge.node1 && edge.node2 == stationEdge.node2) ||
							(edge.node1 == stationEdge.node2 && edge.node2 == stationEdge.node1))) {
				if (p1 == stationEdge.node1) {
					edgeLength = stationPosOnEdge;
				} else {
					edgeLength = edge.getLength() - stationPosOnEdge;
				}
			}

			distance += edgeLength;
		}

		if (secondaryNode != null && stationEdge != null && path.get(path.size() - 1) == secondaryNode) {
			boolean alreadyOnStationEdge = false;
			if (path.size() >= 2) {
				TrackNode p1 = path.get(path.size() - 2);
				TrackNode p2 = path.get(path.size() - 1);
				TrackEdge lastEdge = graph.getConnection(Couple.create(p1, p2));
				if (lastEdge != null && ((lastEdge.node1 == stationEdge.node1 && lastEdge.node2 == stationEdge.node2) ||
						(lastEdge.node1 == stationEdge.node2 && lastEdge.node2 == stationEdge.node1))) {
					alreadyOnStationEdge = true;
				}
			}
			if (!alreadyOnStationEdge) {
				if (secondaryNode == stationEdge.node1) {
					distance += stationPosOnEdge;
				} else {
					distance += stationEdge.getLength() - stationPosOnEdge;
				}
			}
		}

		return Math.max(0.0, distance);
	}

	private Vec3 getLookaheadTangent(TrackGraph graph, TravellingPoint start, List<TrackNode> path,
									 int startIdx, boolean movingTowardsNode2, double lookaheadDistance) {
		if (start.edge == null || path.isEmpty()) return Vec3.ZERO;

		if (startIdx < path.size() - 1) {
			TrackNode p1 = path.get(startIdx);
			TrackNode p2 = path.get(startIdx + 1);
			return p2.getLocation().getLocation()
					.subtract(p1.getLocation().getLocation())
					.normalize();
		}

		return movingTowardsNode2
				? start.edge.node2.getLocation().getLocation()
				.subtract(start.edge.node1.getLocation().getLocation()).normalize()
				: start.edge.node1.getLocation().getLocation()
				.subtract(start.edge.node2.getLocation().getLocation()).normalize();
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

	/**
	 * FIXED: Removed the check for start.edge (currentEdge).
	 * The train is already parked on this edge. It doesn't need to "approach" it correctly;
	 * it just needs to leave it. Checking it caused the path to be falsely invalidated
	 * because leaving a station looks like an "incorrect approach" to the validator.
	 */
	private boolean pathRespectsAllStationDirections(TrackGraph graph, List<TrackNode> path,
													 TravellingPoint start, boolean movingTowardsNode2,
													 GlobalStation targetStation) {
		if (path.isEmpty() || start.edge == null) return true;

		TrackNode prevNode = movingTowardsNode2 ? start.edge.node2 : start.edge.node1;

		for (TrackNode nextNode : path) {
			if (prevNode.equals(nextNode)) continue;
			TrackEdge edge = graph.getConnection(Couple.create(prevNode, nextNode));
			if (edge == null) continue;

			GlobalStation station = findStationOnEdge(graph, edge, targetStation);
			if (station != null) {
				boolean movingTowards = edge.node2.equals(nextNode);
				if (!traversesStationCorrectly(edge, movingTowards, station)) {
					return false;
				}
			}
			prevNode = nextNode;
		}
		return true;
	}

	private boolean traversesStationCorrectly(TrackEdge edge, boolean movingTowardsNode2, GlobalStation station) {
		if (station.isPrimary(edge.node1)) {
			return movingTowardsNode2;
		} else if (station.isPrimary(edge.node2)) {
			return !movingTowardsNode2;
		}
		return true;
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

	private record Target(Schedule schedule, ScheduleEntry entry, DestinationInstruction instruction, GlobalStation station) {}

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
		Pattern pattern;
		try { pattern = Pattern.compile(destination.getFilterForRegex(), Pattern.CASE_INSENSITIVE); }
		catch (PatternSyntaxException e) { return null; }
		GlobalStation nearest = null;
		double nearestDistSq = Double.MAX_VALUE;
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
			for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
				if (!station.getBlockEntityDimension().equals(level.dimension())) continue;
				if (station.name == null || !pattern.matcher(station.name).matches()) continue;
				double distSq = station.getBlockEntityPos().distSqr(getBlockPos());
				if (distSq < nearestDistSq) { nearestDistSq = distSq; nearest = station; lastMatchedStationName = station.name; }
			}
		}
		return nearest;
	}

	private boolean tickConditions(ServerLevel level, ScheduleEntry entry, @Nullable Train train, GlobalStation station) {
		List<List<ScheduleWaitCondition>> columns = entry.conditions;
		while (conditionProgress.size() < columns.size()) { conditionProgress.add(0); conditionContext.add(new CompoundTag()); }
		for (int i = 0; i < columns.size(); i++) {
			List<ScheduleWaitCondition> column = columns.get(i);
			int progress = conditionProgress.get(i);
			if (progress >= column.size()) return true;
			if (evaluateCondition(column.get(progress), level, conditionContext.get(i), train, station)) {
				conditionProgress.set(i, progress + 1);
				if (progress + 1 >= column.size()) return true;
			}
		}
		return false;
	}

	private boolean evaluateCondition(ScheduleWaitCondition condition, ServerLevel level, CompoundTag context, @Nullable Train train, GlobalStation station) {
		if (condition instanceof StationPoweredCondition) {
			return level.hasNeighborSignal(station.getBlockEntityPos());
		}
		return condition.tickCompletion(level, train, context);
	}

	private void advanceEntry(Schedule schedule, HolderLookup.Provider registries) {
		currentEntry = schedule.cyclic ? Math.floorMod(currentEntry + 1, schedule.entries.size()) : Math.min(currentEntry + 1, schedule.entries.size() - 1);
		arrivedAtDestination = false;
		resetConditionProgress();
		lastDistance = Double.NaN;
		directionSign = 1;
		currentPath = Collections.emptyList();
		cachedStation = null;
		cachedTargetNode = null;
		schedule.savedProgress = currentEntry;
		scheduleStack.set(AllDataComponents.TRAIN_SCHEDULE, schedule.write(registries));
		setChanged();
		sendData();
	}

	private void resetConditionProgress() { conditionProgress.clear(); conditionContext.clear(); }

	@Nullable private PhysicsBogeyBlockEntity findAdjacentBogey() {
		for (Direction dir : Direction.values())
			if (level.getBlockEntity(getBlockPos().relative(dir)) instanceof PhysicsBogeyBlockEntity bogey) return bogey;
		return null;
	}

	@Nullable private PhysicsBogeyAxle findAnchorAxle(PhysicsBogeyBlockEntity bogey) {
		for (boolean front : new boolean[]{true, false}) {
			PhysicsBogeyAxle axle = bogey.getAxle(front);
			if (axle.getTrackGraph() != null && axle.getTrackPoint() != null && axle.getTrackPoint().edge != null) return axle;
		}
		return null;
	}

	public Component getStatusLine() {
		if (scheduleStack.isEmpty()) return Component.translatable("simurail.navigation_controller.no_schedule");
		if (lastMatchedStationName == null) return Component.translatable("simurail.navigation_controller.unknown_destination");
		return arrivedAtDestination ? Component.translatable("simurail.navigation_controller.holding", lastMatchedStationName) : Component.translatable("simurail.navigation_controller.en_route", lastMatchedStationName);
	}

	public ItemStack getSchedule() { return scheduleStack; }

	public void setSchedule(ItemStack stack) {
		this.scheduleStack = stack;
		this.currentSpeedMultiplier = 0.0f;
		this.directionSign = 1;
		this.lastDistance = Double.NaN;
		this.currentPath = Collections.emptyList();
		this.cachedStation = null;
		this.cachedTargetNode = null;
		if (level != null) {
			CompoundTag tag = stack.get(AllDataComponents.TRAIN_SCHEDULE);
			Schedule schedule = tag != null ? Schedule.fromTag(level.registryAccess(), tag) : new Schedule();
			this.currentEntry = schedule.entries.isEmpty() ? 0 : Math.floorMod(schedule.savedProgress, schedule.entries.size());
		} else this.currentEntry = 0;
		resetConditionProgress();
		setChanged();
		sendData();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		if (!scheduleStack.isEmpty()) tag.put("ScheduleStack", scheduleStack.save(registries));
		tag.putInt("CurrentEntry", currentEntry);
		tag.putBoolean("Arrived", arrivedAtDestination);
		tag.putFloat("Multiplier", currentSpeedMultiplier);
		tag.putInt("DirectionSign", directionSign);
		ListTag progressTag = new ListTag(); for (int p : conditionProgress) progressTag.add(IntTag.valueOf(p)); tag.put("ConditionProgress", progressTag);
		ListTag contextTag = new ListTag(); for (CompoundTag c : conditionContext) contextTag.add(c); tag.put("ConditionContext", contextTag);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		scheduleStack = tag.contains("ScheduleStack") ? ItemStack.parse(registries, tag.getCompound("ScheduleStack")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
		currentEntry = tag.getInt("CurrentEntry");
		arrivedAtDestination = tag.getBoolean("Arrived");
		currentSpeedMultiplier = tag.getFloat("Multiplier");
		directionSign = tag.contains("DirectionSign") ? tag.getInt("DirectionSign") : 1;
		conditionProgress.clear(); for (Tag t : tag.getList("ConditionProgress", Tag.TAG_INT)) conditionProgress.add(((IntTag) t).getAsInt());
		conditionContext.clear(); for (Tag t : tag.getList("ConditionContext", Tag.TAG_COMPOUND)) conditionContext.add((CompoundTag) t);
	}
}