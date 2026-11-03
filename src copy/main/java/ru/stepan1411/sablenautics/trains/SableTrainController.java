package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.Create;
import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.api.behaviour.interaction.ConductorBlockInteractionBehavior;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.IEdgePointListener;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITurnListener;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.FakeTrackBlock;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stepan1411.sablenautics.content.entities.SableBogeyEntity;
import ru.stepan1411.sablenautics.content.entities.SablenauticsEntities;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * Represents a Create carriage as a Sable sub-level.
 * <p>
 * The carriage body is a free rigid body. The visible wheel model is a separate
 * {@link ru.stepan1411.sablenautics.content.entities.SableBogeyEntity} (not a Sable
 * sub-level); the carriage itself keeps an invisible bogey block so Create's train data
 * and disassembly still work. While on rails the body is held by a longitudinal spring
 * and an angular rail joint. If it is pushed more than {@link #DETACH_DISTANCE} blocks
 * away from the rail target it tears off and the train is marked as derailed. A carriage
 * that runs out of track while still moving leaves the rails the same way and keeps its
 * momentum, so the train flies off the end instead of stopping dead.
 * <p>
 * There are no {@code CarriageContraptionEntity} objects in the world. Create still owns the
 * track graph, travelling points and all scheduling; this class only maps each carriage's
 * anchors to the sub-level poses.
 */
public final class SableTrainController {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sablenautics");

    private static final String TRAIN_KEY = "sablenautics_train";
    private static final String CARRIAGE_KEY = "sablenautics_carriage";
    private static final String INITIAL_YAW_KEY = "sablenautics_initial_yaw";
    private static final String ASSEMBLY_KEY = "sablenautics_assembly_direction";
    private static final String GLUE_KEY = "sablenautics_superglue";
    private static final String ROLE_KEY = "sablenautics_role";
    private static final String DETACHED_KEY = "sablenautics_detached";
    private static final String FLEW_OFF_KEY = "sablenautics_flew_off";
    private static final String AUTO_ATTACH_KEY = "sablenautics_auto_attach";
    private static final String FLY_OFF_ANCHOR_X_KEY = "sablenautics_fly_off_anchor_x";
    private static final String FLY_OFF_ANCHOR_Y_KEY = "sablenautics_fly_off_anchor_y";
    private static final String FLY_OFF_ANCHOR_Z_KEY = "sablenautics_fly_off_anchor_z";
    private static final String APPROACH_START_TICK_KEY = "sablenautics_approach_start_tick";
    private static final String APPROACH_TARGET_X_KEY = "sablenautics_approach_target_x";
    private static final String APPROACH_TARGET_Y_KEY = "sablenautics_approach_target_y";
    private static final String APPROACH_TARGET_Z_KEY = "sablenautics_approach_target_z";
    private static final String APPROACH_TARGET_YAW_KEY = "sablenautics_approach_target_yaw";
    private static final String BOGIES_KEY = "sablenautics_bogies";
    private static final String RUN_OVER_KEY = "sablenautics_last_run_over";
    private static final int RUN_OVER_COOLDOWN = 10;

    private static final String ROLE_BODY = "body";

    /** Distance between the body and the wheel model at which the train tears off. */
    public static final double DETACH_DISTANCE = 3.0;
    /** Re-attach when the body is this close to its rail target... */
    public static final double REATTACH_DISTANCE = 2.0;
    /** ...and roughly aligned with the track. Turns need a wider tolerance. */
    public static final double REATTACH_ANGLE = Math.toRadians(35.0);
    /** Minimum speed (blocks per second) at which a train flies off the end of its track. */
    public static final double FLY_OFF_SPEED = 1.0;
    /** Ticks to wait after a failed relocation before probing the train again. */
    private static final int AUTO_ATTACH_RETRY_TICKS = 10;
    /** Ticks between cheap scans while no track was found under the bogeys yet. */
    private static final int AUTO_ATTACH_SCAN_INTERVAL = 2;
    /** How far from a bogey the nearest track edge is looked up. */
    private static final double TRACK_SEARCH_RADIUS = 4.0;
    /** How many bogies are checked against the graph per attempt. */
    private static final int MAX_ATTACH_PROBES = 6;
    /** How many distinct track points are tried before waiting for the next attempt. */
    private static final int MAX_ATTACH_ATTEMPTS = 8;
    /** How many ticks to wait before actually snapping onto the track. */
    private static final int ATTACH_DELAY_TICKS = 60;
    /** Track blocks this close to the edge the train flew off are not grabbed again. */
    private static final double FLY_OFF_GRAB_RADIUS = 2.5;
    /** Ticks over which a detached body is pulled onto the target track instead of teleporting. */
    private static final int APPROACH_DURATION_TICKS = 20;
    /** Max acceleration applied while approaching the target track (blocks per tick^2). */
    private static final double APPROACH_MAX_ACCELERATION = 15.0;
    /** How close (blocks) the body must be to the target before snapping onto the rail joint. */
    private static final double APPROACH_SNAP_DISTANCE = 0.35;

    // Longitudinal suspension is force based so it can stretch and eventually tear off.
    private static final double LINEAR_STIFFNESS = 90.0;
    private static final double LINEAR_DAMPING = 20.0;
    private static final double MAX_LINEAR_ACCELERATION = 120.0;
    private static final double MAX_TARGET_VELOCITY = 40.0;
    private static final double MAX_TARGET_ANGULAR_VELOCITY = 20.0;
    private static final double MAX_BODY_SPEED = 40.0;
    private static final double MAX_TARGET_STEP = 4.0;

    /** Body sub-level UUID -> carriage data. */
    private static final Map<UUID, SubLevelData> SUBLEVELS = new HashMap<>();
    /** Trains whose adjacent carriage bodies already have contacts disabled. */
    private static final Map<UUID, Boolean> LINKED_TRAINS = new HashMap<>();

    /**
     * Mutable state of one carriage. Accessor method names intentionally match the old
     * record so the rest of the mod does not have to change.
     */
    public static final class SubLevelData {
        private final ServerSubLevel subLevel;
        private final UUID trainId;
        private final int carriageIndex;
        private final float initialYaw;
        private final Direction assemblyDirection;
        private final List<AABB> superglue;
        private final List<BogieInfo> bogies;

        /**
         * Controls that have a Blaze Burner (or another conductor block) attached.
         * Stored per carriage so automatic scheduling does not need a carriage entity.
         */
        private Couple<Boolean> blockConductors = Couple.create(false, false);
        /**
         * Seat positions (relative to the plot center) that can act as driver seats,
         * with the forward/backward controls they are next to.
         */
        private final Map<BlockPos, Couple<Boolean>> conductorSeats = new HashMap<>();

        private boolean detached;
        private boolean derailedByDetach;
        /** Set when the carriage left the rails because the track ended, so it never snaps back. */
        private boolean flewOff;
        private boolean initialized;
        /** First tick at which snapping this carriage onto a nearby track may be retried. */
        private long nextAttachAttemptTick;
        /** World position of the track end the train flew off, excluded from auto-attach. */
        private @Nullable Vec3 flyOffAnchor;
        /** Track edges the train was on when it flew off. It must not latch back onto them. */
        private @Nullable Set<EdgeKey> flyOffEdges;
        /** Target position the detached body is being pulled towards during approach. */
        private @Nullable Vector3d approachTargetPosition;
        /** Target orientation the detached body is being rotated towards during approach. */
        private @Nullable Quaterniond approachTargetOrientation;
        /** Tick when the approach to the target started. */
        private long approachStartTick = -1L;
        /**
         * Only this carriage may start auto-attaching a fully detached train. After the train
         * flies off the end of a track, the carriages left behind are still over the old rails
         * and must not pull the train back onto them.
         */
        private boolean autoAttachTrigger = true;

        private boolean hasTarget;
        private final Vector3d targetPosition = new Vector3d();
        private final Quaterniond targetOrientation = new Quaterniond();
        private final Vector3d targetVelocity = new Vector3d();
        private final Vector3d targetAngularVelocity = new Vector3d();
        private final Vector3d lastTargetPosition = new Vector3d();
        private final Quaterniond lastTargetOrientation = new Quaterniond();
        private long lastTargetTick;
        /** Body linear velocity when no physics pipeline is available (chunks unloaded). */
        private final Vector3d bodyVelocity = new Vector3d();
        /** Body angular velocity when no physics pipeline is available (chunks unloaded). */
        private final Vector3d bodyAngularVelocity = new Vector3d();

        /** Body <-> rail-target orientation joint, active while the train is on the rails. */
        private @Nullable GenericConstraintHandle railJoint;
        /** Free joint to the next carriage, used only to disable body-body contacts. */
        private @Nullable GenericConstraintHandle nextContactJoint;

        private SubLevelData(ServerSubLevel subLevel, UUID trainId, int carriageIndex,
                             float initialYaw, Direction assemblyDirection,
                             List<AABB> superglue, List<BogieInfo> bogies) {
            this.subLevel = subLevel;
            this.trainId = trainId;
            this.carriageIndex = carriageIndex;
            this.initialYaw = initialYaw;
            this.assemblyDirection = assemblyDirection;
            this.superglue = superglue;
            this.bogies = bogies;
        }

        public ServerSubLevel subLevel() {
            return subLevel;
        }

        public List<BogieInfo> bogies() {
            return bogies;
        }

        public UUID trainId() {
            return trainId;
        }

        public int carriageIndex() {
            return carriageIndex;
        }

        public float initialYaw() {
            return initialYaw;
        }

        public Direction assemblyDirection() {
            return assemblyDirection;
        }

        public List<AABB> superglue() {
            return superglue;
        }

        public boolean detached() {
            return detached;
        }

        public boolean flewOff() {
            return flewOff;
        }

        /**
         * @param local seat position relative to the plot center
         * @return which controls this seat can drive, or {@code null} if it is not a driver seat
         */
        public @Nullable Couple<Boolean> conductorSidesAt(BlockPos local) {
            return conductorSeats.get(local);
        }
    }

    private SableTrainController() {
    }

    public static void registerSubLevel(Train train, ServerSubLevel subLevel,
                                        CarriageContraption contraption, int carriageIndex,
                                        List<AABB> superglue, List<BogieInfo> bogies) {
        if (train == null || subLevel == null) return;

        Direction assemblyDirection = contraption != null ? contraption.getAssemblyDirection() : Direction.NORTH;
        float initialYaw = assemblyDirection.getClockWise().toYRot();
        List<BogieInfo> safeBogies = bogies == null ? List.of() : List.copyOf(bogies);
        SubLevelData data = new SubLevelData(subLevel, train.id, carriageIndex, initialYaw,
                assemblyDirection, superglue == null ? List.of() : List.copyOf(superglue), safeBogies);
        applyConductors(data, subLevel);
        SUBLEVELS.put(subLevel.getUniqueId(), data);

        CompoundTag bodyData = subLevel.getUserDataTag();
        if (bodyData == null) bodyData = new CompoundTag();
        bodyData.putUUID(TRAIN_KEY, train.id);
        bodyData.putInt(CARRIAGE_KEY, carriageIndex);
        bodyData.putFloat(INITIAL_YAW_KEY, initialYaw);
        bodyData.putString(ASSEMBLY_KEY, assemblyDirection.getName());
        bodyData.put(GLUE_KEY, writeGlue(data.superglue()));
        bodyData.putString(ROLE_KEY, ROLE_BODY);
        bodyData.putBoolean(DETACHED_KEY, false);
        bodyData.putBoolean(FLEW_OFF_KEY, false);
        bodyData.put(BOGIES_KEY, writeBogies(safeBogies));
        subLevel.setUserDataTag(bodyData);

        if (subLevel.getLevel() instanceof ServerLevel level) {
            for (int i = 0; i < safeBogies.size(); i++) {
                SableBogeyEntity entity = new SableBogeyEntity(SablenauticsEntities.SABLE_BOGEY.get(), level);
                entity.configure(train.id, carriageIndex, i, safeBogies.get(i));
                level.addFreshEntity(entity);
            }
        }

        LOGGER.info("Sablenautics: Registered sublevel {} for train {} carriage {} (bogey entities {}, yaw {}, direction {}, glue {})",
                subLevel.getUniqueId(), train.id, carriageIndex,
                safeBogies.size(), initialYaw, assemblyDirection, data.superglue().size());
    }

    /**
     * Called from {@code SubLevel.tick()} (before physics). Computes the rail target,
     * moves the wheel sub-level and handles detaching / re-attaching.
     */
    public static void tickSubLevel(SubLevel subLevel, Level level) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (subLevel.isRemoved()) {
            forget(serverSubLevel);
            return;
        }

        SubLevelData data = SUBLEVELS.get(subLevel.getUniqueId());
        if (data == null) {
            data = restore(serverSubLevel);
        }
        if (data == null || data.subLevel() != serverSubLevel) return;

        Train train = Create.RAILWAYS.trains.get(data.trainId());
        if (train == null) return;
        if (data.carriageIndex() < 0 || data.carriageIndex() >= train.carriages.size()) return;

        if (!LINKED_TRAINS.containsKey(train.id)) {
            ensureMappings(train);
            linkNeighbours(train);
        }
        Carriage carriage = train.carriages.get(data.carriageIndex());

        // There is no CarriageContraptionEntity running Create's collision/damage handling,
        // so apply the "train ran over an entity" damage from the sub-level pose.
        applyRunOverDamage(serverSubLevel, serverLevel, train, data);

        // Create used to refresh anchors in CarriageContraptionEntity#tick. There is no entity now,
        // so refresh them from the travelling points here.
        carriage.updateContraptionAnchors();

        // Ensure a DimensionalCarriageEntity exists so anchors can be read from travelling points.
        DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
        if (dce == null) {
            dce = carriage.getDimensional(level.dimension());
        }
        if (dce.positionAnchor == null) return;

        // Create stops the whole train when one of its travelling points runs out of track.
        // A Sable train that is still moving keeps its momentum instead and leaves the rails.
        // But if chunks are unloaded, travelling points may appear blocked even though the
        // track is fine — don't derail in that case.
        if (!data.detached && data.initialized && level.hasChunk(chunkCoord((int) dce.positionAnchor.x), chunkCoord((int) dce.positionAnchor.z))) {
            double endOfTrackSpeed = endOfTrackSpeed(serverSubLevel, train, data, carriage);
            if (endOfTrackSpeed > 0) {
                flyOffEndOfTrack(train, data.carriageIndex(), dce.positionAnchor, endOfTrackSpeed);
                return;
            }
        }

        Pose3d pose = serverSubLevel.logicalPose();
        Vec3 anchor = dce.positionAnchor;
        Quaterniond targetOrientation = computeTargetOrientation(dce, pose, data.initialYaw());
        Vector3d targetPosition = computeTargetPosition(serverSubLevel, anchor, targetOrientation);

        updateTargetVelocity(data, targetPosition, targetOrientation, serverLevel);

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(serverLevel);
        PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;

        // Align the freshly assembled / freshly loaded carriage once.
        if (!data.initialized) {
            if (!data.detached) {
                if (pipeline != null) {
                    teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
                    ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
                } else {
                    // No physics pipeline (chunks unloaded): set pose directly.
                    pose.position().set(targetPosition);
                    pose.orientation().set(targetOrientation);
                }
            }
            data.initialized = true;
            data.hasTarget = true;
            return;
        }

        double bodyError = pose.position().distance(targetPosition);

        if (!data.detached) {
            if (bodyError > DETACH_DISTANCE) {
                removeRailJoint(data);
                detach(data, train, bodyError);
            } else if (pipeline != null) {
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
            } else {
                // No physics pipeline (chunks unloaded): don't move the body —
                // just keep the current pose and wait for chunks to load.
            }
        } else {
            boolean snappedBack = false;
            if (!data.flewOff) {
                double angle = orientationAngle(pose.orientation(), targetOrientation);
                if (bodyError <= REATTACH_DISTANCE && angle <= REATTACH_ANGLE) {
                    ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
                    reattach(data, train);
                    snappedBack = true;
                }
            }
            if (!snappedBack) {
                // The old rail target is gone or out of reach. If the body came to rest on
                // some other track, hook the whole train up to that segment.
                tryAttachToNearbyTrack(serverSubLevel, serverLevel, train, data);
            }
        }

        data.hasTarget = true;
    }

    /**
     * Create marks a carriage as {@code blocked} when its travelling point reaches the end of
     * the track. At that moment the train has already been stopped, so the speed it arrived
     * with has to be taken from the body (or from last tick's rail target).
     *
     * @return speed in blocks per second if the train should leave the rails, otherwise 0
     */
    private static double endOfTrackSpeed(ServerSubLevel body, Train train, SubLevelData data,
                                          Carriage carriage) {
        if (!carriage.blocked) return 0;

        double speed = Math.max(body.latestLinearVelocity.length(),
                Math.max(data.targetVelocity.length(), Math.abs(train.speed) * 20.0));
        return speed >= FLY_OFF_SPEED ? speed : 0.0;
    }

    /**
     * Detaches every carriage of the train. Create only stops at the first blocked carriage,
     * so a train that runs out of track leaves the rails as a whole instead of hanging on
     * the last few blocks of track.
     */
    private static void flyOffEndOfTrack(Train train, int blockedIndex, Vec3 anchor, double speed) {
        Set<EdgeKey> oldEdges = new HashSet<>();
        train.forEachTravellingPoint(tp -> {
            if (tp.node1 == null || tp.node2 == null) return;
            TrackNodeLocation first = tp.node1.getLocation();
            TrackNodeLocation second = tp.node2.getLocation();
            oldEdges.add(new EdgeKey(first, second));
            oldEdges.add(new EdgeKey(second, first));
        });

        int detached = 0;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;

            other.flyOffAnchor = anchor;
            other.flyOffEdges = oldEdges;
            other.autoAttachTrigger = true;
            if (!other.detached) {
                removeRailJoint(other);
                markDetached(other, train);
                other.flewOff = true;
                detached++;
            }
            writeDetachedFlag(other);
        }
        if (detached == 0) return;

        LOGGER.warn("Sablenautics: train {} ran off the end of the track at carriage {} ({} blocks/s), detached {} carriages",
                train.id, blockedIndex, String.format("%.2f", speed), detached);
    }

    /**
     * Moves the carriage body towards {@code targetPosition} using a spring-damper model,
     * without requiring a physics pipeline. Used when chunks are unloaded and
     * {@link #applySuspension} cannot run. The body position and velocity are updated
     * directly on the sub-level pose.
     */
    private static void moveTowardsTarget(ServerSubLevel subLevel, SubLevelData data,
                                          Vector3d targetPosition, Quaterniond targetOrientation) {
        double mass = subLevel.getMassTracker().getMass();
        if (mass <= 0) return;

        Pose3d pose = subLevel.logicalPose();
        Vector3d currentPos = new Vector3d(pose.position());
        Vector3d diff = new Vector3d(targetPosition).sub(currentPos);
        double dist = diff.length();

        if (dist < 0.01) return;

        double massFactor = Math.max(0.6, Math.min(2.0, Math.sqrt(mass / 200.0)));
        double stiffness = LINEAR_STIFFNESS * massFactor;
        double damping = LINEAR_DAMPING * massFactor;
        double maxAcceleration = MAX_LINEAR_ACCELERATION * massFactor;

        Vector3d velocity = new Vector3d(data.bodyVelocity);
        Vector3d acceleration = diff.mul(stiffness)
                .fma(damping, new Vector3d(data.targetVelocity).sub(velocity));
        clampLength(acceleration, maxAcceleration);

        Vector3d gravity = DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), new Vector3d());
        acceleration.sub(gravity);

        double dt = 1.0 / 20.0;
        velocity.add(acceleration.mul(dt));
        clampLength(velocity, MAX_BODY_SPEED);
        data.bodyVelocity.set(velocity);

        currentPos.add(velocity.mul(dt));
        pose.position().set(currentPos);

        // Angular alignment towards target orientation.
        Quaterniond current = new Quaterniond(pose.orientation());
        Quaterniond delta = new Quaterniond(targetOrientation)
                .mul(new Quaterniond(current).conjugate())
                .normalize();
        double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(delta.w)));
        if (angle > 0.01) {
            Vector3d axis = new Vector3d(delta.x, delta.y, delta.z).normalize();
            double turnSpeed = Math.min(angle, 0.15);
            data.bodyAngularVelocity.set(axis.x * turnSpeed, axis.y * turnSpeed, axis.z * turnSpeed);
        }
        // Apply accumulated angular rotation.
        if (data.bodyAngularVelocity.lengthSquared() > 0.0) {
            double dtAng = dt;
            Quaterniond rotDelta = new Quaterniond()
                    .rotateX(data.bodyAngularVelocity.x * dtAng)
                    .rotateY(data.bodyAngularVelocity.y * dtAng)
                    .rotateZ(data.bodyAngularVelocity.z * dtAng);
            current.mul(rotDelta);
            pose.orientation().set(current);
            data.bodyAngularVelocity.mul(0.9); // decay
        }
    }

    /**
     * Hooks a train up to the nearest track edge to {@code point}. Works for straight rails and
     * for bezier curves (the graph edge is used directly, so no track block is required).
     *
     * @param direction horizontal direction used to align the train along the track
     * @return true when the train now sits on the rails
     */
    public static boolean relocateToNearestTrack(Train train, ServerLevel level, Vec3 point, Vec3 direction) {
        if (train == null || level == null || point == null) return false;
        TrackPoint nearest = findNearestTrackPoint(level, point, TRACK_SEARCH_RADIUS);
        if (nearest == null) return false;
        return relocateToTrackPoint(train, level, nearest, direction);
    }

    /** A point on a track graph edge, used for attaching a train to any rail segment. */
    private record TrackPoint(TrackGraph graph, Couple<TrackNodeLocation> edge, double position, Vec3 worldPos) {
    }

    /**
     * @return the closest point on any track graph edge within {@code maxDistance} blocks
     */
    private static @Nullable TrackPoint findNearestTrackPoint(ServerLevel level, Vec3 point, double maxDistance) {
        double bestDistanceSq = maxDistance * maxDistance;
        TrackPoint best = null;

        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation nodeLocation : graph.getNodes()) {
                if (!level.dimension().equals(nodeLocation.dimension)) continue;

                TrackNode node = graph.locateNode(nodeLocation);
                if (node == null) continue;

                for (TrackEdge edge : graph.getConnectionsFrom(node).values()) {
                    if (edge.node1 != node || edge.isInterDimensional()) continue;

                    AABB bounds = edge.isTurn()
                            ? edge.getTurn().getBounds()
                            : new AABB(edge.node1.getLocation().getLocation(),
                                    edge.node2.getLocation().getLocation());
                    if (!bounds.inflate(maxDistance).contains(point)) continue;

                    double length = edge.getLength();
                    if (length <= 1.0e-4) continue;

                    int samples = Math.min(64, Math.max(4, (int) Math.ceil(length)));
                    for (int i = 0; i <= samples; i++) {
                        double t = (double) i / samples;
                        Vec3 sample = edge.getPosition(graph, t);
                        double distanceSq = sample.distanceToSqr(point);
                        if (distanceSq >= bestDistanceSq) continue;

                        bestDistanceSq = distanceSq;
                        best = new TrackPoint(graph,
                                Couple.create(edge.node1.getLocation(), edge.node2.getLocation()),
                                t * length, sample);
                    }
                }
            }
        }

        return best;
    }

    /**
     * Rewrites every travelling point of the train from {@code candidate} onwards, mirroring
     * Create's train relocator but without needing a track block at the exact spot.
     */
    private static boolean relocateToTrackPoint(Train train, ServerLevel level, TrackPoint candidate,
                                                Vec3 direction) {
        TrackGraph graph = candidate.graph();
        TrackNode node1 = graph.locateNode(candidate.edge().getFirst());
        TrackNode node2 = graph.locateNode(candidate.edge().getSecond());
        if (node1 == null || node2 == null) return false;

        TrackEdge edge = graph.getConnectionsFrom(node1).get(node2);
        if (edge == null) return false;

        double position = candidate.position();

        // The train is laid out backwards from the chosen point, so order the edge along the
        // direction the carriage is lying in.
        Vec3 edgeDirection = edge.getPosition(graph, 1).subtract(edge.getPosition(graph, 0));
        if (direction != null && edgeDirection.lengthSqr() > 1.0e-6 && direction.dot(edgeDirection) < 0) {
            TrackNode swap = node1;
            node1 = node2;
            node2 = swap;
            edge = graph.getConnectionsFrom(node1).get(node2);
            if (edge == null) return false;
            position = edge.getLength() - position;
        }
        if (position < 0 || position > edge.getLength()) return false;

        TravellingPoint probe = new TravellingPoint(node1, node2, edge, position, false);
        IEdgePointListener ignoreSignals = probe.ignoreEdgePoints();
        ITurnListener ignoreTurns = probe.ignoreTurns();
        List<Pair<Couple<TrackNode>, Double>> recordedLocations = new ArrayList<>();
        List<Vec3> recordedVecs = new ArrayList<>();
        Consumer<TravellingPoint> recorder = tp -> {
            recordedLocations.add(Pair.of(Couple.create(tp.node1, tp.node2), tp.position));
            recordedVecs.add(tp.getPosition(graph));
        };
        ITrackSelector steer = probe.steer(TravellingPoint.SteerDirection.NONE, edge.getNormal(graph, 0));
        MutableBoolean blocked = new MutableBoolean(false);
        MutableBoolean portal = new MutableBoolean(false);
        MutableInt blockingIndex = new MutableInt(0);

        train.forEachTravellingPointBackwards((tp, d) -> {
            if (blocked.booleanValue()) return;
            probe.travel(graph, d, steer, ignoreSignals, ignoreTurns, $ -> {
                portal.setTrue();
                return true;
            });
            recorder.accept(probe);
            if (probe.blocked || portal.booleanValue()) {
                blocked.setTrue();
                return;
            }
            blockingIndex.increment();
        });

        for (int i = 0; i < recordedVecs.size() - 1; i++) {
            Vec3 vec1 = recordedVecs.get(i);
            Vec3 vec2 = recordedVecs.get(i + 1);
            boolean blocking = i >= blockingIndex.intValue() - 1;
            boolean collided = !blocked.booleanValue()
                    && train.findCollidingTrain(level, vec1, vec2, level.dimension()) != null;
            if (collided || blocking) return false;
        }
        if (blocked.booleanValue()) return false;

        train.leaveStation();
        train.derailed = false;
        train.navigation.waitingForSignal = null;
        train.occupiedSignalBlocks.clear();
        train.graph = graph;
        train.speed = 0;
        train.cancelStall();
        if (train.navigation.destination != null) {
            train.navigation.cancelNavigation();
        }

        train.forEachTravellingPoint(tp -> {
            Pair<Couple<TrackNode>, Double> last = recordedLocations.remove(recordedLocations.size() - 1);
            tp.node1 = last.getFirst().getFirst();
            tp.node2 = last.getFirst().getSecond();
            tp.position = last.getSecond();
            tp.edge = graph.getConnectionsFrom(tp.node1).get(tp.node2);
        });

        for (Carriage carriage : train.carriages) {
            carriage.updateContraptionAnchors();
        }

        train.status.successfulMigration();
        train.collectInitiallyOccupiedSignalBlocks();

        teleportToRails(train);
        return true;
    }

    /**
     * Snaps a fully detached train onto the nearest track edge, straight or curved, regardless
     * of how fast the body is moving. The track is looked up from the track graph, so curves
     * are found even in the middle of a bezier where there is no track block.
     * <p>
     * Attachment is not instant teleport: once a suitable track is found the body is pulled
     * towards it by physics over {@link #APPROACH_DURATION_TICKS} ticks, so the carriage and
     * its wheels roll onto the rails smoothly instead of snapping.
     */
    private static boolean tryAttachToNearbyTrack(ServerSubLevel body, ServerLevel level, Train train,
                                                  SubLevelData data) {
        if (!data.detached) return false;
        if (!data.autoAttachTrigger) return false;
        if (!allCarriagesDetached(train)) return false;

        // Already approaching a track: keep pulling until we reach it.
        if (data.approachTargetPosition != null) return false;

        long now = level.getGameTime();
        if (now < data.nextAttachAttemptTick) return false;

        List<BogieProbe> bogieProbes = new ArrayList<>();

        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id) || !other.detached) continue;
            ServerSubLevel otherBody = other.subLevel();
            if (otherBody.isRemoved()) continue;

            Pose3d pose = otherBody.logicalPose();
            for (BogieInfo bogie : other.bogies()) {
                Vector3d world = pose.transformPosition(bogie.localPlotCenter(), new Vector3d());
                bogieProbes.add(new BogieProbe(other, new Vec3(world.x, world.y, world.z)));
            }
        }

        // Lay the train out from its rear-most carriage so it extends forward over its own span.
        bogieProbes.sort(Comparator.comparingInt((BogieProbe probe) -> probe.data().carriageIndex()).reversed());

        List<TrackPoint> candidates = new ArrayList<>();
        for (BogieProbe probe : bogieProbes) {
            if (candidates.size() >= MAX_ATTACH_PROBES) break;
            if (!hasTrackNearby(level, probe.point(), TRACK_SEARCH_RADIUS)) continue;

            TrackPoint candidate = findNearestTrackPoint(level, probe.point(), TRACK_SEARCH_RADIUS);
            if (candidate == null) continue;
            if (isFlyOffAnchor(probe.data(), candidate.worldPos())) continue;
            if (isOnFlyOffEdge(probe.data(), candidate)) continue;
            candidates.add(candidate);
        }

        if (candidates.isEmpty()) {
            data.nextAttachAttemptTick = now + AUTO_ATTACH_SCAN_INTERVAL;
            return false;
        }

        // Deduplicate and pick the nearest candidate to the trigger carriage.
        candidates.sort(Comparator.comparingDouble(c -> c.worldPos().distanceToSqr(
                new Vec3(body.logicalPose().position().x, body.logicalPose().position().y, body.logicalPose().position().z))));
        List<TrackPoint> unique = new ArrayList<>();
        for (TrackPoint candidate : candidates) {
            boolean duplicate = false;
            for (TrackPoint previous : unique) {
                if (sameTrackPoint(previous, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(candidate);
        }

        if (unique.isEmpty()) {
            data.nextAttachAttemptTick = now + AUTO_ATTACH_SCAN_INTERVAL;
            return false;
        }

        TrackPoint chosen = unique.get(0);
        Vec3 targetPos = chosen.worldPos();
        data.approachTargetPosition = new Vector3d(targetPos.x, targetPos.y, targetPos.z());

        // Target orientation: align the carriage along the track edge direction.
        data.approachTargetOrientation = new Quaterniond();
        TrackGraph graph = chosen.graph();
        TrackNode node1 = graph.locateNode(chosen.edge().getFirst());
        TrackNode node2 = graph.locateNode(chosen.edge().getSecond());
        if (node1 != null && node2 != null) {
            TrackEdge edge = graph.getConnectionsFrom(node1).get(node2);
            if (edge != null) {
                Vec3 dir = edge.getDirection(true);
                data.approachTargetOrientation = new Quaterniond()
                        .rotateY((float) Math.atan2(dir.z, dir.x));
            }
        }
        data.approachStartTick = now;
        writeDetachedFlag(data);

        // Play a warning sound so the player knows the train is about to be pulled onto rails.
        Vector3dc pos = body.logicalPose().position();
        level.playSound(null, new BlockPos((int) pos.x(), (int) pos.y(), (int) pos.z()),
                SoundEvents.ARMOR_STAND_PLACE, SoundSource.NEUTRAL, 0.6f, 1.2f);
        LOGGER.info("Sablenautics: train {} approaching track at {}", train.id, chosen.worldPos());
        return false;
    }

    /**
     * Cheap check before walking the track graph: is there any track block (including the fake
     * blocks laid along curves) within {@code radius} of {@code point}?
     */
    private static boolean hasTrackNearby(ServerLevel level, Vec3 point, double radius) {
        BlockPos center = BlockPos.containing(point);
        int r = (int) Math.ceil(radius);

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ITrackBlock) return true;
            if (state.getBlock() instanceof FakeTrackBlock) return true;
        }
        return false;
    }

    private static boolean sameTrackPoint(TrackPoint first, TrackPoint second) {
        return first.graph() == second.graph()
                && first.edge().getFirst().equals(second.edge().getFirst())
                && first.edge().getSecond().equals(second.edge().getSecond())
                && Math.abs(first.position() - second.position()) < 2.0;
    }

    private record BogieProbe(SubLevelData data, Vec3 point) {
    }

    private record EdgeKey(TrackNodeLocation first, TrackNodeLocation second) {
    }

    private static boolean isOnFlyOffEdge(SubLevelData data, TrackPoint point) {
        Set<EdgeKey> edges = data.flyOffEdges;
        if (edges == null) return false;
        return edges.contains(new EdgeKey(point.edge().getFirst(), point.edge().getSecond()));
    }

    private static boolean isFlyOffAnchor(SubLevelData data, Vec3 pos) {
        if (data.flyOffAnchor == null) return false;
        return pos.distanceToSqr(data.flyOffAnchor) < FLY_OFF_GRAB_RADIUS * FLY_OFF_GRAB_RADIUS;
    }

    private static boolean allCarriagesDetached(Train train) {
        boolean found = false;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            found = true;
            if (!other.detached) return false;
        }
        return found;
    }

    /**
     * Best guess of the direction the carriage is lying in, used to pick the track axis.
     */
    private static Vec3 trackDirection(ServerSubLevel body, SubLevelData data) {
        Pose3d pose = body.logicalPose();

        // Preferred: direction from the trailing bogey to the leading bogey, i.e. the way
        // the train is facing. bogies[0] is leading, bogies[1] is trailing.
        List<BogieInfo> bogies = data.bogies();
        if (bogies.size() >= 2) {
            Vector3d first = pose.transformPosition(bogies.get(0).localPlotCenter(), new Vector3d());
            Vector3d second = pose.transformPosition(bogies.get(1).localPlotCenter(), new Vector3d());
            Vec3 forward = new Vec3(first.x - second.x, 0.0, first.z - second.z);
            if (forward.lengthSqr() > 1.0e-4) return forward.normalize();
        }

        // Fallback: use the body's own forward axis from the assembly direction.
        Direction assemblyDirection = data.assemblyDirection();
        Vector3d local = new Vector3d(assemblyDirection.getStepX(), 0.0, assemblyDirection.getStepZ());
        Vector3d rotated = pose.orientation().transform(local, new Vector3d());
        Vec3 fallback = new Vec3(rotated.x, 0.0, rotated.z);
        if (fallback.lengthSqr() > 1.0e-4) return fallback.normalize();

        // Last resort: current horizontal velocity (may be near zero when landed).
        Vector3d velocity = body.latestLinearVelocity;
        Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        if (horizontal.lengthSqr() > 1.0e-4) return horizontal.normalize();

        return new Vec3(0.0, 0.0, 1.0);
    }

    /**
     * Called before every physics sub-step from {@code ServerSubLevel#prePhysicsTick}.
     * The body is held by the longitudinal spring; the rail joint (hard angular lock)
     * keeps it aligned. The wheel sub-level only gets anti-gravity so it stays exactly
     * on its teleported rail pose.
     */
    public static void prePhysicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        SubLevelData data = SUBLEVELS.get(subLevel.getUniqueId());
        if (data == null || data.subLevel() != subLevel) return;

        ServerLevel level = subLevel.getLevel();
        if (level == null) return;

        // While approaching the target track, pull the body with physics instead of teleporting.
        // Don't move the body when chunks are unloaded — physics doesn't run there.
        if (data.approachTargetPosition != null) {
            Vector3dc pos = subLevel.logicalPose().position();
            if (level.hasChunk(chunkCoord((int) pos.x()), chunkCoord((int) pos.z()))) {
                applyApproach(subLevel, handle, data, timeStep);
            }
            return;
        }

        if (data.detached || !data.hasTarget) return;

        applySuspension(subLevel, handle, data, timeStep);
    }

    private static void applySuspension(ServerSubLevel subLevel, RigidBodyHandle handle,
                                        SubLevelData data, double timeStep) {
        double mass = subLevel.getMassTracker().getMass();
        if (mass <= 0) return;

        Pose3d pose = subLevel.logicalPose();
        Quaterniond inverseOrientation = new Quaterniond(pose.orientation()).conjugate();

        // Heavier carriages (more blocks) are held more strongly. This keeps short cars
        // soft enough to tear off while long/heavy cars are noticeably stiffer.
        double massFactor = Math.max(0.6, Math.min(2.0, Math.sqrt(mass / 200.0)));
        double stiffness = LINEAR_STIFFNESS * massFactor;
        double damping = LINEAR_DAMPING * massFactor;
        double maxAcceleration = MAX_LINEAR_ACCELERATION * massFactor;

        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        double speedSquared = velocity.lengthSquared();
        if (speedSquared > MAX_BODY_SPEED * MAX_BODY_SPEED) {
            Vector3d correction = new Vector3d(velocity)
                    .mul(MAX_BODY_SPEED / Math.sqrt(speedSquared) - 1.0);
            handle.addLinearAndAngularVelocity(correction, new Vector3d());
            velocity = handle.getLinearVelocity(new Vector3d());
        }

        Vector3d acceleration = new Vector3d(data.targetPosition)
                .sub(pose.position())
                .mul(stiffness)
                .fma(damping, new Vector3d(data.targetVelocity).sub(velocity));
        clampLength(acceleration, maxAcceleration);

        // Cancel gravity so the suspension only has to track the rail target.
        Vector3d gravity = DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), new Vector3d());
        acceleration.sub(gravity);

        double dt = Math.max(timeStep, 1.0 / 20.0 / 8.0);
        Vector3d impulseWorld = new Vector3d(acceleration).mul(mass * dt);
        Vector3d impulseLocal = inverseOrientation.transform(impulseWorld, new Vector3d());
        handle.applyLinearAndAngularImpulse(impulseLocal, new Vector3d(), true);
    }

    private static void detach(SubLevelData data, Train train, double error) {
        data.flewOff = false;
        data.flyOffAnchor = null;
        data.flyOffEdges = null;
        data.autoAttachTrigger = true;
        data.approachStartTick = -1L;
        data.approachTargetPosition = null;
        data.approachTargetOrientation = null;
        markDetached(data, train);
        LOGGER.warn("Sablenautics: carriage {} of train {} tore off the rails (offset {} blocks)",
                data.carriageIndex(), train.id, String.format("%.2f", error));
    }

    private static void markDetached(SubLevelData data, Train train) {
        data.detached = true;
        data.derailedByDetach = true;
        data.hasTarget = false;
        // A torn-off carriage must collide with the rest of the train again, so drop the
        // contact-less joints it shared with its neighbours.
        restoreNeighbourContacts(data);
        train.derailed = true;
        writeDetachedFlag(data);
    }

    private static void reattach(SubLevelData data, Train train) {
        data.detached = false;
        data.flewOff = false;
        data.flyOffAnchor = null;
        data.flyOffEdges = null;
        data.autoAttachTrigger = true;
        data.approachStartTick = -1L;
        data.approachTargetPosition = null;
        data.approachTargetOrientation = null;
        if (data.derailedByDetach) {
            data.derailedByDetach = false;
            train.derailed = false;
        }
        writeDetachedFlag(data);
        // Collisions between this carriage and its neighbours are disabled again.
        linkNeighbours(train);
        LOGGER.info("Sablenautics: carriage {} of train {} re-attached to the rails",
                data.carriageIndex(), train.id);
    }

    /**
     * Removes the contact-less joints between this carriage and both of its neighbours so
     * the physics engine starts resolving collisions for the detached body again.
     */
    private static void restoreNeighbourContacts(SubLevelData data) {
        removeConstraint(data.nextContactJoint);
        data.nextContactJoint = null;

        int previousIndex = data.carriageIndex() - 1;
        if (previousIndex >= 0) {
            for (SubLevelData other : SUBLEVELS.values()) {
                if (other != data
                        && other.trainId().equals(data.trainId())
                        && other.carriageIndex() == previousIndex) {
                    removeConstraint(other.nextContactJoint);
                    other.nextContactJoint = null;
                    break;
                }
            }
        }

        // Allow linkNeighbours to rebuild the remaining joints once this carriage is back.
        LINKED_TRAINS.remove(data.trainId());
    }

    private static void updateTargetVelocity(SubLevelData data, Vector3d targetPosition,
                                             Quaterniond targetOrientation, ServerLevel level) {
        long now = level.getGameTime();
        if (data.hasTarget) {
            double dtTicks = Math.max(1L, now - data.lastTargetTick);
            double dt = dtTicks / 20.0;

            // Track nodes and freshly loaded carriages can produce a one-tick target jump.
            // Feeding that jump into the spring would launch the carriage, so ignore it.
            double targetStep = targetPosition.distance(data.lastTargetPosition);
            if (targetStep <= MAX_TARGET_STEP) {
                data.targetVelocity.set(targetPosition).sub(data.lastTargetPosition).div(dt);
                clampLength(data.targetVelocity, MAX_TARGET_VELOCITY);
            } else {
                data.targetVelocity.zero();
            }

            Quaterniond delta = new Quaterniond(targetOrientation)
                    .mul(new Quaterniond(data.lastTargetOrientation).conjugate())
                    .normalize();
            double w = Math.max(-1.0, Math.min(1.0, delta.w));
            double sign = w < 0 ? -1.0 : 1.0;
            double sinHalf = Math.sqrt(Math.max(0.0, 1.0 - w * w));
            if (sinHalf < 1.0e-7) {
                data.targetAngularVelocity.zero();
            } else {
                double angle = 2.0 * Math.acos(Math.abs(w));
                data.targetAngularVelocity.set(delta.x, delta.y, delta.z)
                        .mul(sign * angle / (sinHalf * dt));
                clampLength(data.targetAngularVelocity, MAX_TARGET_ANGULAR_VELOCITY);
            }
        } else {
            data.targetVelocity.zero();
            data.targetAngularVelocity.zero();
        }

        data.lastTargetPosition.set(targetPosition);
        data.lastTargetOrientation.set(targetOrientation);
        data.lastTargetTick = now;
        data.targetPosition.set(targetPosition);
        data.targetOrientation.set(targetOrientation);
    }

    private static Quaterniond computeTargetOrientation(DimensionalCarriageEntity dce, Pose3d pose, float initialYaw) {
        if (dce.rotationAnchors.either(Objects::isNull)) {
            return new Quaterniond(pose.orientation());
        }

        Vec3 first = dce.rotationAnchors.getFirst();
        Vec3 second = dce.rotationAnchors.getSecond();
        double diffX = first.x - second.x;
        double diffY = first.y - second.y;
        double diffZ = first.z - second.z;
        double yaw = Math.atan2(diffZ, diffX) * 180 / Math.PI + 180;
        double pitch = -Math.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ)) * 180 / Math.PI;
        // OrientedContraptionEntity#getViewYRot returns -yaw, getViewXRot returns +pitch.
        return new Quaterniond()
                .rotateY(Math.toRadians(-yaw))
                .rotateZ(Math.toRadians(pitch))
                .rotateY(Math.toRadians(initialYaw));
    }

    /**
     * @return the pose position that places this sub-level's plot anchor on {@code anchor}.
     */
    private static Vector3d computeTargetPosition(ServerSubLevel subLevel, Vec3 anchor, Quaterniond orientation) {
        Vector3dc centerOfMass = subLevel.getMassTracker().getCenterOfMass();
        if (centerOfMass == null) {
            return new Vector3d(anchor.x, anchor.y, anchor.z);
        }

        Vector3d localOffset = localAnchorOffset(subLevel, centerOfMass);
        return new Vector3d(anchor.x, anchor.y, anchor.z)
                .sub(orientation.transform(localOffset, new Vector3d()));
    }

    /**
     * @return where a bogie sub-level has to be so that it stays rigidly attached to the body.
     */
    private static Vector3d localAnchorOffset(ServerSubLevel subLevel, Vector3dc centerOfMass) {
        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        return new Vector3d(plotAnchor.getX() + 0.5, plotAnchor.getY(), plotAnchor.getZ() + 0.5)
                .sub(centerOfMass);
    }

    /**
     * Body &lt;-&gt; moving rail frame. Only the angular axes are locked. This stops the
     * carriage from tumbling around the wheels without applying any linear reaction
     * forces to the wheel sub-level, so the wheels can never be pulled off the rails.
     */
    private static void ensureRailJoint(SubLevelData data, ServerSubLevel body,
                                        @Nullable PhysicsPipeline pipeline,
                                        Vector3d targetPosition, Quaterniond targetOrientation) {
        if (pipeline == null) return;

        Vector3d worldAnchor = worldJointAnchor(body, targetPosition, targetOrientation);
        GenericConstraintHandle joint = data.railJoint;

        if (joint == null || !joint.isValid()) {
            GenericConstraintConfiguration config = new GenericConstraintConfiguration(
                    worldAnchor,
                    plotCenter(body),
                    targetOrientation,
                    new Quaterniond(),
                    EnumSet.of(ConstraintJointAxis.ANGULAR_X,
                            ConstraintJointAxis.ANGULAR_Y,
                            ConstraintJointAxis.ANGULAR_Z));
            joint = pipeline.addConstraint(null, body, config);
            if (joint == null) return;

            data.railJoint = joint;
            pipeline.wakeUp(body);
        } else {
            // Keep the world frame on the current rail target.
            joint.setFrame1(worldAnchor, targetOrientation);
        }
    }

    private static void removeRailJoint(SubLevelData data) {
        removeConstraint(data.railJoint);
        data.railJoint = null;
    }

    private static void removeConstraint(@Nullable GenericConstraintHandle joint) {
        if (joint == null) return;
        try {
            if (joint.isValid()) {
                joint.remove();
            }
        } catch (Exception ignored) {
            // The pipeline may already have been disposed during server shutdown.
        }
    }

    private static Vector3d worldJointAnchor(ServerSubLevel body, Vector3d targetPosition,
                                             Quaterniond targetOrientation) {
        Vector3dc centerOfMass = body.getMassTracker().getCenterOfMass();
        if (centerOfMass == null) return new Vector3d(targetPosition);

        Vector3d localOffset = plotCenter(body).sub(centerOfMass);
        return new Vector3d(targetPosition).add(targetOrientation.transform(localOffset, new Vector3d()));
    }

    private static Vector3d plotCenter(ServerSubLevel subLevel) {
        BlockPos center = subLevel.getPlot().getCenterBlock();
        return new Vector3d(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5);
    }

    public record BogieTarget(Vec3 position, Quaterniond orientation, float wheelAngleDelta,
                              Vec3 bodyAttachment, boolean onRails) {
    }

    public static List<BogieInfo> bogiesFor(ServerSubLevel body) {
        SubLevelData data = SUBLEVELS.get(body.getUniqueId());
        if (data == null) data = restore(body);
        return data != null ? data.bogies() : List.of();
    }

    /**
     * Resolves where a wheel entity has to be placed for the current tick.
     */
    public static @Nullable BogieTarget bogieTarget(UUID trainId, int carriageIndex, int bogieIndex) {
        SubLevelData data = null;
        for (SubLevelData candidate : SUBLEVELS.values()) {
            if (candidate.trainId().equals(trainId) && candidate.carriageIndex() == carriageIndex) {
                data = candidate;
                break;
            }
        }
        if (data == null) return null;

        ServerSubLevel body = data.subLevel();
        if (body.isRemoved()) return null;
        if (bogieIndex < 0 || bogieIndex >= data.bogies().size()) return null;

        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null || carriageIndex < 0 || carriageIndex >= train.carriages.size()) return null;

        BogieInfo info = data.bogies().get(bogieIndex);
        Pose3d bodyPose = body.logicalPose();
        Vector3d bodyAttachment = bodyPose.transformPosition(info.localPlotCenter(), new Vector3d());
        Vec3 attachmentWorld = new Vec3(bodyAttachment.x, bodyAttachment.y, bodyAttachment.z);

        if (!data.detached()) {
            Carriage carriage = train.carriages.get(carriageIndex);
            carriage.updateContraptionAnchors();
            ServerLevel level = body.getLevel();
            DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
            Vector3dc centerOfMass = body.getMassTracker().getCenterOfMass();

            if (dce != null && dce.positionAnchor != null && centerOfMass != null) {
                // While the train is on the rails the wheels sit at the rail target,
                // independently from the (possibly lagging) carriage body.
                Quaterniond targetOrientation = computeTargetOrientation(dce, bodyPose, data.initialYaw());
                Vector3d targetPosition = computeTargetPosition(body, dce.positionAnchor, targetOrientation);
                Vector3d localOffset = new Vector3d(info.localPlotCenter()).sub(centerOfMass);
                Vector3d world = new Vector3d(targetPosition)
                        .add(targetOrientation.transform(localOffset, new Vector3d()));

                // Each Create bogey follows its own pair of travelling points, so on curves
                // it turns relative to the carriage. Apply the same relative yaw/pitch that
                // CarriageContraptionEntityRenderer.translateBogey uses for normal trains.
                Quaterniond bogieOrientation = new Quaterniond(targetOrientation);
                applyBogieAngles(bogieOrientation, carriageBogey(carriage, bogieIndex), train, dce);

                float angleDelta = (float) (360.0 * train.speed / (Math.PI * 2.0 * info.wheelRadius()));
                return new BogieTarget(new Vec3(world.x, world.y, world.z), bogieOrientation, angleDelta,
                        attachmentWorld, true);
            }
        }

        // Torn off (or no rail anchor available): the wheels travel with the body.
        Vector3d world = bodyPose.transformPosition(info.localPlotCenter(), new Vector3d());
        return new BogieTarget(new Vec3(world.x, world.y, world.z),
                new Quaterniond(bodyPose.orientation()), 0.0f, attachmentWorld, false);
    }

    private static @Nullable CarriageBogey carriageBogey(Carriage carriage, int bogieIndex) {
        if (bogieIndex == 0) return carriage.leadingBogey();
        if (bogieIndex == 1 && carriage.isOnTwoBogeys()) return carriage.trailingBogey();
        return null;
    }

    /**
     * Adds the bogey's own yaw/pitch relative to the carriage. The carriage already has
     * its chord orientation, so applying the bogey's absolute heading would double the
     * turn; only the difference between the bogey points and the rotation anchors is used.
     */
    private static void applyBogieAngles(Quaterniond orientation, @Nullable CarriageBogey bogey,
                                         Train train, DimensionalCarriageEntity dce) {
        if (bogey == null || train.graph == null) return;
        if (dce.rotationAnchors.either(Objects::isNull)) return;

        var leading = bogey.leading();
        var trailing = bogey.trailing();
        if (leading.edge == null || trailing.edge == null) return;

        Vec3 bogeyFirst = leading.getPosition(train.graph);
        Vec3 bogeySecond = trailing.getPosition(train.graph);
        Vec3 carriageFirst = dce.rotationAnchors.getFirst();
        Vec3 carriageSecond = dce.rotationAnchors.getSecond();

        double bogeyDiffX = bogeyFirst.x - bogeySecond.x;
        double bogeyDiffY = bogeyFirst.y - bogeySecond.y;
        double bogeyDiffZ = bogeyFirst.z - bogeySecond.z;
        double carriageDiffX = carriageFirst.x - carriageSecond.x;
        double carriageDiffY = carriageFirst.y - carriageSecond.y;
        double carriageDiffZ = carriageFirst.z - carriageSecond.z;

        double bogeyHorizontal = Math.sqrt(bogeyDiffX * bogeyDiffX + bogeyDiffZ * bogeyDiffZ);
        double carriageHorizontal = Math.sqrt(carriageDiffX * carriageDiffX + carriageDiffZ * carriageDiffZ);
        if (bogeyHorizontal < 1.0e-6 || carriageHorizontal < 1.0e-6) return;

        double bogeyYaw = Math.atan2(bogeyDiffZ, bogeyDiffX) * 180.0 / Math.PI;
        double carriageYaw = Math.atan2(carriageDiffZ, carriageDiffX) * 180.0 / Math.PI;
        double bogeyPitch = Math.atan2(bogeyDiffY, bogeyHorizontal) * 180.0 / Math.PI;
        double carriagePitch = Math.atan2(carriageDiffY, carriageHorizontal) * 180.0 / Math.PI;

        double deltaYaw = bogeyYaw - carriageYaw;
        double deltaPitch = bogeyPitch - carriagePitch;

        orientation.mul(new Quaterniond()
                .rotateY(Math.toRadians(-deltaYaw))
                .rotateZ(Math.toRadians(-deltaPitch)));
    }

    /**
     * Snaps every Sable carriage of the train back onto its current rail target.
     *
     * @return number of carriages that were teleported
     */
    public static int teleportToRails(Train train) {
        if (train == null) return 0;
        ensureMappings(train);
        linkNeighbours(train);

        int moved = 0;
        for (int index = 0; index < train.carriages.size(); index++) {
            Carriage carriage = train.carriages.get(index);

            SubLevelData data = null;
            for (SubLevelData candidate : SUBLEVELS.values()) {
                if (candidate.trainId().equals(train.id) && candidate.carriageIndex() == index) {
                    data = candidate;
                    break;
                }
            }
            if (data == null) continue;

            ServerSubLevel body = data.subLevel();
            if (body.isRemoved()) continue;

            ServerLevel level = body.getLevel();
            carriage.updateContraptionAnchors();
            DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
            if (dce == null || dce.positionAnchor == null) continue;

            SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
            if (physicsSystem == null) continue;
            PhysicsPipeline pipeline = physicsSystem.getPipeline();

            Quaterniond orientation = computeTargetOrientation(dce, body.logicalPose(), data.initialYaw());
            Vector3d targetPosition = computeTargetPosition(body, dce.positionAnchor, orientation);

            teleport(body, targetPosition, orientation, pipeline);
            ensureRailJoint(data, body, pipeline, targetPosition, orientation);

            data.detached = false;
            data.derailedByDetach = false;
            data.flewOff = false;
            data.flyOffAnchor = null;
            data.flyOffEdges = null;
            data.autoAttachTrigger = true;
            data.approachStartTick = -1L;
            data.approachTargetPosition = null;
            data.approachTargetOrientation = null;
            data.initialized = true;
            data.hasTarget = true;
            resetTargetTracking(data, targetPosition, orientation, level);
            writeDetachedFlag(data);
            moved++;
        }

        if (moved > 0) {
            train.derailed = false;
            train.speed = 0;
            // All carriages are attached again, so they must not collide with each other.
            linkNeighbours(train);
        }
        return moved;
    }

    private static void teleport(ServerSubLevel subLevel, Vector3d position, Quaterniond orientation,
                                 PhysicsPipeline pipeline) {
        Pose3d pose = subLevel.logicalPose();
        Vector3dc centerOfMass = subLevel.getMassTracker().getCenterOfMass();
        if (centerOfMass != null) {
            pose.rotationPoint().set(centerOfMass);
        }
        pose.position().set(position);
        pose.orientation().set(orientation);

        pipeline.teleport(subLevel, position, orientation);
        pipeline.resetVelocity(subLevel);
        subLevel.latestLinearVelocity.zero();
        subLevel.latestAngularVelocity.zero();
    }

    /**
     * Drops the cached rail-target velocity after a teleport or relocation. Otherwise the
     * spring would treat the jump to the new track as a huge target step and launch the
     * carriage (or, worse, make it think it just ran off the end of the track).
     */
    private static void resetTargetTracking(SubLevelData data, Vector3d targetPosition,
                                            Quaterniond targetOrientation, ServerLevel level) {
        data.targetVelocity.zero();
        data.targetAngularVelocity.zero();
        data.targetPosition.set(targetPosition);
        data.targetOrientation.set(targetOrientation);
        data.lastTargetPosition.set(targetPosition);
        data.lastTargetOrientation.set(targetOrientation);
        data.lastTargetTick = level.getGameTime();
    }

    /**
     * Pulls a detached carriage body towards {@link SubLevelData#approachTargetPosition} using
     * a spring-like force. When the body gets close enough, it is snapped onto the rail joint
     * and marked as attached again — no teleport, the body (and its wheels / passengers)
     * arrive by physics.
     */
    private static void applyApproach(ServerSubLevel subLevel, RigidBodyHandle handle,
                                      SubLevelData data, double timeStep) {
        double mass = subLevel.getMassTracker().getMass();
        if (mass <= 0) return;

        Vector3dc currentPos = subLevel.logicalPose().position();
        Vector3d diff = new Vector3d(data.approachTargetPosition).sub(currentPos);
        double dist = diff.length();

        // Close enough: snap onto the rail joint and stop the approach.
        if (dist <= APPROACH_SNAP_DISTANCE) {
            finishApproach(subLevel, handle, data);
            return;
        }

        // Spring + damping towards the target.
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        Vector3d impulse = new Vector3d(diff).mul(APPROACH_MAX_ACCELERATION)
                .sub(new Vector3d(velocity).mul(4.0));
        clampLength(impulse, APPROACH_MAX_ACCELERATION);

        double dt = Math.max(timeStep, 1.0 / 20.0 / 8.0);
        handle.applyLinearAndAngularImpulse(impulse.mul(mass * dt), new Vector3d(), true);

        // Slowly rotate the body towards the target orientation.
        if (data.approachTargetOrientation != null) {
            Quaterniond current = new Quaterniond(subLevel.logicalPose().orientation());
            Quaterniond target = new Quaterniond(data.approachTargetOrientation);
            Quaterniond delta = new Quaterniond(target).mul(new Quaterniond(current).conjugate()).normalize();
            double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(delta.w)));
            if (angle > 0.02) {
                Vector3d axis = new Vector3d(delta.x, delta.y, delta.z).normalize();
                double turnSpeed = Math.min(angle, 0.15);
                handle.applyLinearAndAngularImpulse(new Vector3d(),
                        new Vector3d(axis.x * turnSpeed, axis.y * turnSpeed, axis.z * turnSpeed), true);
            }
        }
    }

    /**
     * Completes the approach: attaches the rail joint, marks the carriage attached and lets
     * neighbours collide again.
     */
    private static void finishApproach(ServerSubLevel subLevel, RigidBodyHandle handle,
                                       SubLevelData data) {
        Train train = Create.RAILWAYS.trains.get(data.trainId());
        if (train == null) {
            data.approachTargetPosition = null;
            data.approachTargetOrientation = null;
            data.approachStartTick = -1L;
            writeDetachedFlag(data);
            return;
        }

        ServerLevel level = subLevel.getLevel();
        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;

        Vector3d targetPos = data.approachTargetPosition;
        Quaterniond targetOrient = data.approachTargetOrientation != null
                ? new Quaterniond(data.approachTargetOrientation)
                : new Quaterniond(subLevel.logicalPose().orientation());

        // Stop residual velocity so the carriage lands neatly on the rails.
        handle.applyLinearAndAngularImpulse(new Vector3d(), new Vector3d(), true);

        if (pipeline != null) {
            ensureRailJoint(data, subLevel, pipeline, targetPos, targetOrient);
            pipeline.wakeUp(subLevel);
        }

        data.approachTargetPosition = null;
        data.approachTargetOrientation = null;
        data.approachStartTick = -1L;
        data.detached = false;
        data.flewOff = false;
        data.initialized = true;
        data.hasTarget = true;
        train.derailed = false;
        linkNeighbours(train);
        writeDetachedFlag(data);

        Vector3dc finalPos = subLevel.logicalPose().position();
        level.playSound(null, new BlockPos((int) finalPos.x(), (int) finalPos.y(), (int) finalPos.z()),
                SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, 0.7f, 1.0f);
        LOGGER.info("Sablenautics: train {} smoothly attached to track", train.id);
    }

    private static int chunkCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    private static void clampLength(Vector3d vector, double maxLength) {
        double length = vector.length();
        if (length > maxLength && length > 1.0e-9) {
            vector.mul(maxLength / length);
        }
    }

    private static double orientationAngle(Quaterniondc first, Quaterniondc second) {
        Quaterniond delta = new Quaterniond(first).mul(new Quaterniond(second).conjugate()).normalize();
        return 2.0 * Math.acos(Math.min(1.0, Math.abs(delta.w)));
    }

    public static SubLevelData dataAt(ServerLevel level, BlockPos plotPos) {
        SubLevel subLevel = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, plotPos);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;

        SubLevelData data = SUBLEVELS.get(serverSubLevel.getUniqueId());
        if (data == null) data = restore(serverSubLevel);
        return data != null && data.subLevel() == serverSubLevel ? data : null;
    }

    public static @Nullable ServerSubLevel subLevelFor(Train train, int carriageIndex) {
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id) && data.carriageIndex() == carriageIndex) {
                return data.subLevel();
            }
        }
        return null;
    }

    /**
     * @return true if this carriage is represented by a Sable sub-level
     */
    /**
     * Adds contact-less free joints between adjacent Sable carriage bodies. Without this,
     * long trains can have neighbouring carriage bodies colliding inside the physics engine.
     */
    public static void linkNeighbours(Train train) {
        if (train == null || LINKED_TRAINS.containsKey(train.id)) return;

        int count = train.carriages.size();
        SubLevelData[] ordered = new SubLevelData[count];
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id)
                    && data.carriageIndex() >= 0 && data.carriageIndex() < count) {
                ordered[data.carriageIndex()] = data;
            }
        }

        boolean complete = true;
        for (int i = 0; i + 1 < count; i++) {
            SubLevelData first = ordered[i];
            SubLevelData second = ordered[i + 1];
            if (first == null || second == null) {
                complete = false;
                continue;
            }
            if (first.detached || second.detached) {
                // A detached body has to keep colliding with the train.
                complete = false;
                continue;
            }
            if (first.nextContactJoint != null && first.nextContactJoint.isValid()) continue;

            ServerSubLevel firstBody = first.subLevel();
            ServerSubLevel secondBody = second.subLevel();
            if (firstBody.isRemoved() || secondBody.isRemoved()) continue;
            if (firstBody.getLevel() != secondBody.getLevel()) continue;

            SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(firstBody.getLevel());
            if (physicsSystem == null) continue;

            GenericConstraintConfiguration config = new GenericConstraintConfiguration(
                    plotCenter(firstBody), plotCenter(secondBody),
                    new Quaterniond(), new Quaterniond(),
                    EnumSet.noneOf(ConstraintJointAxis.class));
            GenericConstraintHandle joint = physicsSystem.getPipeline()
                    .addConstraint(firstBody, secondBody, config);
            if (joint != null) {
                joint.setContactsEnabled(false);
                first.nextContactJoint = joint;
            }
        }

        if (complete) {
            LINKED_TRAINS.put(train.id, Boolean.TRUE);
        }
    }

    /**
     * Reads the driver-seat and Blaze-conductor layout from the carriage blocks. This is the
     * same information Create stores in {@code CarriageContraption}, but Sable carriages have
     * no contraption entity to read it from after a reload.
     */
    private static void applyConductors(SubLevelData data, ServerSubLevel subLevel) {
        data.blockConductors = Couple.create(false, false);
        data.conductorSeats.clear();

        if (!(subLevel.getLevel() instanceof ServerLevel level)) return;
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) return;

        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        Direction assemblyDirection = data.assemblyDirection();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos plotPos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(plotPos);
                    if (state.isAir()) continue;

                    boolean isSeat = AllBlockTags.SEATS.matches(state);
                    ConductorBlockInteractionBehavior conductor = null;
                    if (!isSeat) {
                        MovingInteractionBehaviour behaviour = MovingInteractionBehaviour.REGISTRY.get(state);
                        if (behaviour instanceof ConductorBlockInteractionBehavior candidate) {
                            conductor = candidate;
                        }
                    }
                    if (!isSeat && conductor == null) continue;

                    for (Direction direction : Direction.values()) {
                        if (direction.getAxis() != assemblyDirection.getAxis()) continue;
                        if (!isControlNextTo(level, plotPos, direction)) continue;

                        boolean secondSide = direction != assemblyDirection;
                        if (isSeat) {
                            BlockPos local = plotPos.subtract(plotAnchor);
                            data.conductorSeats
                                    .computeIfAbsent(local, p -> Couple.create(false, false))
                                    .set(secondSide, true);
                        } else {
                            // A Blaze Burner with no blaze cannot act as a conductor. Promoting
                            // it to level 1 makes it drivable without making it heat anything.
                            BlockState conductorState = promoteBlazeBurner(level, plotPos, state);
                            if (conductor.isValidConductor(conductorState)) {
                                data.blockConductors.set(secondSide, true);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void refreshConductors(SubLevelData data) {
        if (data == null) return;
        applyConductors(data, data.subLevel());
    }

    private static boolean isControlNextTo(ServerLevel level, BlockPos pos, Direction direction) {
        BlockState controlsState = level.getBlockState(pos.relative(direction));
        if (!(controlsState.getBlock() instanceof ControlsBlock)) return false;
        return controlsState.hasProperty(ControlsBlock.FACING)
                && controlsState.getValue(ControlsBlock.FACING) == direction.getOpposite();
    }

    /**
     * @return true if the block at {@code pos} is next to a Controls block of this carriage
     */
    public static boolean isConductorAdjacent(ServerLevel level, BlockPos pos, SubLevelData data) {
        Direction assemblyDirection = data.assemblyDirection();
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != assemblyDirection.getAxis()) continue;
            if (isControlNextTo(level, pos, direction)) return true;
        }
        return false;
    }

    /**
     * Blaze Burners without a blaze cannot be conductors. Level 1 (SMOULDERING) is enough for
     * driving, but it does not heat boilers or other blocks above the burner.
     */
    public static BlockState promoteBlazeBurner(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BlazeBurnerBlock)) return state;
        if (!state.hasProperty(BlazeBurnerBlock.HEAT_LEVEL)) return state;
        if (state.getValue(BlazeBurnerBlock.HEAT_LEVEL) != BlazeBurnerBlock.HeatLevel.NONE) return state;

        BlockState promoted = state.setValue(BlazeBurnerBlock.HEAT_LEVEL,
                BlazeBurnerBlock.HeatLevel.SMOULDERING);
        level.setBlock(pos, promoted, Block.UPDATE_ALL);
        return promoted;
    }

    private static void applyRunOverDamage(ServerSubLevel subLevel, ServerLevel level, Train train,
                                           SubLevelData data) {
        if (!AllConfigs.server().trains.trainsCauseDamage.get()) return;

        double speed = Math.abs(train.speed);
        if (speed <= 0.35) return;

        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) return;
        Pose3d pose = subLevel.logicalPose();

        AABB worldBounds = worldBounds(bounds, pose);
        if (worldBounds == null) return;

        Set<UUID> riders = collectRiders(level, bounds);
        long now = level.getGameTime();

        for (Entity entity : level.getEntitiesOfClass(Entity.class, worldBounds.inflate(1.5))) {
            if (!entity.isAlive() || entity.isSpectator()) continue;
            if (entity instanceof ItemEntity || entity instanceof SableBogeyEntity) continue;
            if (riders.contains(entity.getUUID())) continue;
            if (Sable.HELPER.getTrackingSubLevel(entity) == subLevel) continue;
            if (!entity.onGround()) continue;
            if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) continue;

            Vec3 local = pose.transformPositionInverse(entity.position());
            if (!insideBody(bounds, local)) continue;

            CompoundTag persistentData = entity.getPersistentData();
            long lastHit = persistentData.getLong(RUN_OVER_KEY);
            if (now - lastHit < RUN_OVER_COOLDOWN) continue;
            persistentData.putLong(RUN_OVER_KEY, now);

            double damage = speed;
            if (entity.getClassification(false) == MobCategory.MONSTER) damage *= 2;

            DamageSource source = CreateDamageSources.runOver(level, null);
            entity.hurt(source, (int) (damage * 16));
            level.playSound(null, entity.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT,
                    SoundSource.NEUTRAL, 1, 0.75f);
        }
    }

    private static AABB worldBounds(BoundingBox3ic bounds, Pose3d pose) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (int x : new int[]{bounds.minX(), bounds.maxX() + 1}) {
            for (int y : new int[]{bounds.minY(), bounds.maxY() + 1}) {
                for (int z : new int[]{bounds.minZ(), bounds.maxZ() + 1}) {
                    Vec3 world = pose.transformPosition(new Vec3(x, y, z));
                    minX = Math.min(minX, world.x);
                    minY = Math.min(minY, world.y);
                    minZ = Math.min(minZ, world.z);
                    maxX = Math.max(maxX, world.x);
                    maxY = Math.max(maxY, world.y);
                    maxZ = Math.max(maxZ, world.z);
                }
            }
        }

        if (minX > maxX) return null;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean insideBody(BoundingBox3ic bounds, Vec3 local) {
        return local.x >= bounds.minX() - 0.3 && local.x <= bounds.maxX() + 1.3
                && local.y >= bounds.minY() - 0.1 && local.y <= bounds.maxY() + 0.9
                && local.z >= bounds.minZ() - 0.3 && local.z <= bounds.maxZ() + 1.3;
    }

    private static Set<UUID> collectRiders(ServerLevel level, BoundingBox3ic bounds) {
        Set<UUID> riders = new HashSet<>();
        for (SeatEntity seat : level.getEntitiesOfClass(SeatEntity.class, bounds.toAABB())) {
            riders.add(seat.getUUID());
            for (Entity passenger : seat.getIndirectPassengers()) {
                riders.add(passenger.getUUID());
            }
        }
        return riders;
    }

    public static boolean isSableCarriage(Carriage carriage) {
        if (carriage == null) return false;
        if (SableCarriageRegistry.get(carriage) != null) return true;

        Train train = carriage.train;
        if (train == null) return false;
        int index = train.carriages.indexOf(carriage);
        if (index < 0) return false;

        ensureMappings(train);
        return subLevelFor(train, index) != null;
    }

    /**
     * Create reads conductor flags from the {@code CarriageContraptionEntity}, which Sable
     * carriages never have. Automatic schedules need a forward conductor (a Blaze Burner
     * next to the controls or a mob sitting in a driver seat), so compute the flags from
     * the sub-level instead.
     *
     * @return true when this carriage is a Sable carriage and was handled here
     */
    public static boolean updateConductors(Carriage carriage) {
        if (carriage == null) return false;
        Train train = carriage.train;
        if (train == null) return false;
        int index = train.carriages.indexOf(carriage);
        if (index < 0) return false;

        ServerSubLevel subLevel = subLevelFor(train, index);
        if (subLevel == null) return false;

        SubLevelData data = SUBLEVELS.get(subLevel.getUniqueId());
        if (data == null) data = restore(subLevel);
        if (data == null) return true;

        Couple<Boolean> sides = Couple.create(data.blockConductors.getFirst(),
                data.blockConductors.getSecond());

        if (!data.conductorSeats.isEmpty() && subLevel.getLevel() instanceof ServerLevel level) {
            BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
            for (Map.Entry<BlockPos, Couple<Boolean>> entry : data.conductorSeats.entrySet()) {
                BlockPos seatPos = plotAnchor.offset(entry.getKey());
                if (!hasNonPlayerPassenger(level, seatPos)) continue;

                Couple<Boolean> validSides = entry.getValue();
                sides.setFirst(sides.getFirst() || validSides.getFirst());
                sides.setSecond(sides.getSecond() || validSides.getSecond());
            }
        }

        carriage.presentConductors.setFirst(sides.getFirst());
        carriage.presentConductors.setSecond(sides.getSecond());
        return true;
    }

    private static boolean hasNonPlayerPassenger(ServerLevel level, BlockPos seatPos) {
        for (SeatEntity seat : level.getEntitiesOfClass(SeatEntity.class, new AABB(seatPos))) {
            for (Entity passenger : seat.getPassengers()) {
                if (!(passenger instanceof Player)) return true;
            }
        }
        return false;
    }

    public static boolean isDetached(ServerSubLevel body) {
        SubLevelData data = SUBLEVELS.get(body.getUniqueId());
        if (data == null) data = restore(body);
        return data != null && data.detached();
    }

    public record Alignment(float yaw, float pitch, float initialYaw, ServerSubLevel subLevel) {
    }

    public static @Nullable Alignment alignment(ServerLevel level, Carriage carriage) {
        Train train = carriage.train;
        if (train == null) return null;
        int index = train.carriages.indexOf(carriage);
        ServerSubLevel subLevel = subLevelFor(train, index);
        if (subLevel == null) return null;

        carriage.updateContraptionAnchors();
        DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
        if (dce == null || dce.positionAnchor == null || dce.rotationAnchors.either(Objects::isNull)) return null;

        Vec3 first = dce.rotationAnchors.getFirst();
        Vec3 second = dce.rotationAnchors.getSecond();
        double diffX = first.x - second.x;
        double diffY = first.y - second.y;
        double diffZ = first.z - second.z;
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180 / Math.PI + 180);
        float pitch = (float) (-Math.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ)) * 180 / Math.PI);

        SubLevelData data = SUBLEVELS.get(subLevel.getUniqueId());
        float initialYaw = data != null ? data.initialYaw() : 0;
        return new Alignment(yaw, pitch, initialYaw, subLevel);
    }

    public static void forget(ServerSubLevel subLevel) {
        SubLevelData data = SUBLEVELS.remove(subLevel.getUniqueId());
        if (data != null) {
            removeRailJoint(data);
            removeConstraint(data.nextContactJoint);
            data.nextContactJoint = null;
            LINKED_TRAINS.remove(data.trainId());
            SableBogeyEntity.discardForCarriage(data.trainId(), data.carriageIndex());
            SUBLEVELS.remove(data.subLevel().getUniqueId());
        }
    }

    public static int purgeSubLevels(ServerLevel level, UUID trainId) {
        SableBogeyEntity.discardForTrain(trainId);
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;

        int removed = 0;
        for (SubLevel subLevel : new java.util.ArrayList<>(container.getAllSubLevels())) {
            if (!(subLevel instanceof ServerSubLevel serverSubLevel)) continue;
            CompoundTag userData = serverSubLevel.getUserDataTag();
            if (userData == null || !userData.contains(TRAIN_KEY)) continue;
            if (!trainId.equals(userData.getUUID(TRAIN_KEY))) continue;

            SableTrainDisassembly.purgeSubLevel(level, serverSubLevel);
            removed++;
        }
        return removed;
    }

    /**
     * Rebuilds the sub-level mapping from persisted user data. Needed for disassembly when
     * the station asks before the plot had a chance to tick after a reload.
     */
    public static void ensureMappings(Train train) {
        int found = 0;
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id)) found++;
        }
        if (found >= train.carriages.size()) return;

        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel instanceof ServerSubLevel serverSubLevel
                        && !SUBLEVELS.containsKey(serverSubLevel.getUniqueId())) {
                    restore(serverSubLevel);
                }
            }
        }
    }

    public static Vec3 plotBlockCenter(ServerSubLevel subLevel, BlockPos plotPos) {
        return subLevel.logicalPose().transformPosition(
                new Vec3(plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5));
    }

    private static @Nullable SubLevelData restore(ServerSubLevel subLevel) {
        CompoundTag userData = subLevel.getUserDataTag();
        if (userData == null || !userData.contains(TRAIN_KEY)) return null;

        // Old builds used a separate Sable sub-level for the wheels. Those are no longer
        // needed; the sub-level is discarded and the model is provided by an entity.
        if ("bogie".equals(userData.getString(ROLE_KEY))) {
            subLevel.markRemoved();
            return null;
        }

        return restoreBody(subLevel);
    }

    private static @Nullable SubLevelData restoreBody(ServerSubLevel subLevel) {
        SubLevelData existing = SUBLEVELS.get(subLevel.getUniqueId());
        if (existing != null) return existing;

        CompoundTag userData = subLevel.getUserDataTag();
        if (userData == null || !userData.contains(TRAIN_KEY)) return null;

        UUID trainId = userData.getUUID(TRAIN_KEY);
        if (Create.RAILWAYS.trains.get(trainId) == null) return null;

        int carriageIndex = userData.getInt(CARRIAGE_KEY);
        float initialYaw = userData.getFloat(INITIAL_YAW_KEY);
        Direction assemblyDirection = Direction.byName(userData.getString(ASSEMBLY_KEY));
        if (assemblyDirection == null) assemblyDirection = Direction.NORTH;

        SubLevelData data = new SubLevelData(subLevel, trainId, carriageIndex, initialYaw,
                assemblyDirection, readGlue(userData), readBogies(subLevel, userData));
        applyConductors(data, subLevel);
        data.detached = userData.getBoolean(DETACHED_KEY);
        data.flewOff = userData.getBoolean(FLEW_OFF_KEY);
        data.autoAttachTrigger = !userData.contains(AUTO_ATTACH_KEY) || userData.getBoolean(AUTO_ATTACH_KEY);
        data.approachStartTick = userData.getLong(APPROACH_START_TICK_KEY);
        if (data.approachStartTick == 0L) data.approachStartTick = -1L;
        if (userData.contains(APPROACH_TARGET_X_KEY)) {
            data.approachTargetPosition = new Vector3d(
                    userData.getDouble(APPROACH_TARGET_X_KEY),
                    userData.getDouble(APPROACH_TARGET_Y_KEY),
                    userData.getDouble(APPROACH_TARGET_Z_KEY));
            float yaw = userData.getFloat(APPROACH_TARGET_YAW_KEY);
            data.approachTargetOrientation = new Quaterniond().rotateY(yaw);
        } else {
            data.approachTargetPosition = null;
            data.approachTargetOrientation = null;
        }
        if (userData.contains(FLY_OFF_ANCHOR_X_KEY)) {
            data.flyOffAnchor = new Vec3(userData.getDouble(FLY_OFF_ANCHOR_X_KEY),
                    userData.getDouble(FLY_OFF_ANCHOR_Y_KEY),
                    userData.getDouble(FLY_OFF_ANCHOR_Z_KEY));
        }
        // A detached carriage keeps the pose it was saved with; an attached one is aligned once.
        data.initialized = data.detached;

        SUBLEVELS.put(subLevel.getUniqueId(), data);
        LOGGER.info("Sablenautics: Restored sublevel {} for train {} carriage {} ({} bogey entity data, detached {}, flew off {})",
                subLevel.getUniqueId(), trainId, carriageIndex, data.bogies().size(), data.detached, data.flewOff);
        return data;
    }

    private static void writeDetachedFlag(SubLevelData data) {
        CompoundTag bodyData = data.subLevel().getUserDataTag();
        if (bodyData == null) bodyData = new CompoundTag();
        bodyData.putBoolean(DETACHED_KEY, data.detached);
        bodyData.putBoolean(FLEW_OFF_KEY, data.flewOff);
        bodyData.putBoolean(AUTO_ATTACH_KEY, data.autoAttachTrigger);
        if (data.approachStartTick != -1L) {
            bodyData.putLong(APPROACH_START_TICK_KEY, data.approachStartTick);
        } else {
            bodyData.remove(APPROACH_START_TICK_KEY);
        }
        if (data.approachTargetPosition != null) {
            bodyData.putDouble(APPROACH_TARGET_X_KEY, data.approachTargetPosition.x);
            bodyData.putDouble(APPROACH_TARGET_Y_KEY, data.approachTargetPosition.y);
            bodyData.putDouble(APPROACH_TARGET_Z_KEY, data.approachTargetPosition.z);
        } else {
            bodyData.remove(APPROACH_TARGET_X_KEY);
            bodyData.remove(APPROACH_TARGET_Y_KEY);
            bodyData.remove(APPROACH_TARGET_Z_KEY);
        }
        if (data.approachTargetOrientation != null) {
            Quaterniond q = data.approachTargetOrientation;
            double sinY = 2.0 * (q.w * q.y + q.x * q.z);
            double cosY = 1.0 - 2.0 * (q.y * q.y + q.z * q.z);
            bodyData.putFloat(APPROACH_TARGET_YAW_KEY, (float) Math.atan2(sinY, cosY));
        } else {
            bodyData.remove(APPROACH_TARGET_YAW_KEY);
        }

        Vec3 anchor = data.flyOffAnchor;
        if (anchor != null) {
            bodyData.putDouble(FLY_OFF_ANCHOR_X_KEY, anchor.x);
            bodyData.putDouble(FLY_OFF_ANCHOR_Y_KEY, anchor.y);
            bodyData.putDouble(FLY_OFF_ANCHOR_Z_KEY, anchor.z);
        } else {
            bodyData.remove(FLY_OFF_ANCHOR_X_KEY);
            bodyData.remove(FLY_OFF_ANCHOR_Y_KEY);
            bodyData.remove(FLY_OFF_ANCHOR_Z_KEY);
        }

        data.subLevel().setUserDataTag(bodyData);
    }

    public static List<AABB> superglueFor(ServerSubLevel subLevel) {
        SubLevelData data = SUBLEVELS.get(subLevel.getUniqueId());
        return data != null ? data.superglue() : List.of();
    }

    private static net.minecraft.nbt.ListTag writeGlue(List<AABB> boxes) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (AABB box : boxes) {
            CompoundTag tag = new CompoundTag();
            SuperGlueEntity.writeBoundingBox(tag, box);
            list.add(tag);
        }
        return list;
    }

    private static List<AABB> readGlue(CompoundTag userData) {
        List<AABB> boxes = new java.util.ArrayList<>();
        if (userData.contains(GLUE_KEY, Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag list = userData.getList(GLUE_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                boxes.add(SuperGlueEntity.readBoundingBox(list.getCompound(i)));
            }
        }
        return boxes;
    }

    private static net.minecraft.nbt.ListTag writeBogies(List<BogieInfo> bogies) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (BogieInfo bogie : bogies) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("LX", bogie.localPlotCenter().x);
            tag.putDouble("LY", bogie.localPlotCenter().y);
            tag.putDouble("LZ", bogie.localPlotCenter().z);
            tag.putDouble("IX", bogie.initialWorldPosition().x);
            tag.putDouble("IY", bogie.initialWorldPosition().y);
            tag.putDouble("IZ", bogie.initialWorldPosition().z);
            tag.putString("Style", bogie.styleId().toString());
            tag.putString("Size", bogie.sizeId().toString());
            tag.put("Data", bogie.bogeyData().copy());
            tag.putString("Axis", bogie.axis().getName());
            tag.putBoolean("UpsideDown", bogie.upsideDown());
            tag.putDouble("WheelRadius", bogie.wheelRadius());
            tag.put("State", NbtUtils.writeBlockState(bogie.originalState()));
            list.add(tag);
        }
        return list;
    }

    private static List<BogieInfo> readBogies(ServerSubLevel subLevel, CompoundTag userData) {
        List<BogieInfo> bogies = new java.util.ArrayList<>();
        if (!userData.contains(BOGIES_KEY, Tag.TAG_LIST)) return bogies;

        net.minecraft.nbt.ListTag list = userData.getList(BOGIES_KEY, Tag.TAG_COMPOUND);
        var blocks = subLevel.getLevel().holderLookup(Registries.BLOCK);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);

            ResourceLocation style = ResourceLocation.tryParse(tag.getString("Style"));
            if (style == null) style = ResourceLocation.fromNamespaceAndPath("create", "standard");
            ResourceLocation size = ResourceLocation.tryParse(tag.getString("Size"));
            if (size == null) size = ResourceLocation.fromNamespaceAndPath("create", "small");

            Direction.Axis axis = Direction.Axis.Z;
            for (Direction.Axis candidate : Direction.Axis.values()) {
                if (candidate.getName().equals(tag.getString("Axis"))) {
                    axis = candidate;
                    break;
                }
            }

            BlockState state = NbtUtils.readBlockState(blocks, tag.getCompound("State"));
            bogies.add(new BogieInfo(
                    new Vector3d(tag.getDouble("LX"), tag.getDouble("LY"), tag.getDouble("LZ")),
                    new Vec3(tag.getDouble("IX"), tag.getDouble("IY"), tag.getDouble("IZ")),
                    style, size, tag.getCompound("Data").copy(), axis,
                    tag.getBoolean("UpsideDown"), tag.getDouble("WheelRadius"), state));
        }
        return bogies;
    }

    public static void clear() {
        for (SubLevelData data : SUBLEVELS.values()) {
            removeRailJoint(data);
            removeConstraint(data.nextContactJoint);
            data.nextContactJoint = null;
        }
        SUBLEVELS.clear();
        LINKED_TRAINS.clear();
        SableBogeyEntity.clearTracked();
    }
}
