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
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.IEdgePointListener;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITurnListener;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
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
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
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
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

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
 * The carriage body is a free rigid body. The visible wheel model is rendered by the
 * Sable bogey block entity inside the body sub-level; the carriage itself keeps an
 * invisible bogey block so Create's train data and disassembly still work. While on rails
 * the body is held by a spring and an angular rail joint. When nobody drives the train the
 * spring only holds it laterally and vertically, so any physical push rolls it along the
 * rails; the logical rail target follows the pushed body. If the body is pushed sideways
 * more than {@link #DETACH_DISTANCE} blocks away from the rail target it tears off and the
 * train is marked as derailed. A carriage that runs out of track while still moving leaves
 * the rails the same way and keeps its momentum, so the train flies off the end instead of
 * stopping dead.
 * <p>
 * Detaching always affects the whole train. Its travelling points are suspended so Create's
 * collision scan cannot make other trains crash into a body that is no longer on the rails.
 * A detached train automatically hooks onto the nearest free track once it comes to rest;
 * a wrench can also relocate it to any rail section at any time. Trains that lost their
 * physical bodies are hidden from collisions and cleaned up so they never become phantoms.
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
    public static final String SABLE_TRAIN_KEY = "SablenauticsSableTrain";
    private static final String BOGIES_KEY = "sablenautics_bogies";
    private static final String RUN_OVER_KEY = "sablenautics_last_run_over";
    private static final int RUN_OVER_COOLDOWN = 10;

    private static final String ROLE_BODY = "body";

    /** Distance between the body and the wheel model at which the train tears off. */
    public static final double DETACH_DISTANCE = 3.0;
    /** Automatic re-attachment is only allowed when every body is this close to the rails. */
    public static final double AUTO_ATTACH_DISTANCE = 2.0;
    /** ...and when every body is within this angle of the rail direction. */
    public static final double AUTO_ATTACH_ANGLE = Math.toRadians(30.0);
    /**
     * If a bogie is practically touching a rail but the whole train is lying at an angle,
     * snap the train onto the track anyway. This is the "crashed train lying across the
     * rails" case; without it a scattered/rotated train can never be re-railed by itself.
     */
    private static final double AUTO_ATTACH_FORCE_DISTANCE = 2.5;
    /** Bodies this close/aligned can be attached without any approach animation. */
    private static final double DIRECT_ATTACH_DISTANCE = 0.35;
    private static final double DIRECT_ATTACH_ANGLE = Math.toRadians(8.0);
    /** The physics approach finishes once the body is this close/aligned. */
    private static final double APPROACH_SNAP_DISTANCE = 0.4;
    private static final double APPROACH_SNAP_ANGLE = Math.toRadians(8.0);
    /** Hard cap so a stuck train cannot stay in approach forever. */
    private static final int APPROACH_TIMEOUT_TICKS = 80;
    /** Pull/rotation used while smoothly moving a detached body onto the new track. */
    private static final double APPROACH_STIFFNESS = 120.0;
    private static final double APPROACH_DAMPING = 12.0;
    private static final double APPROACH_MAX_ACCELERATION = 180.0;
    private static final double APPROACH_MAX_ANGULAR_SPEED = 4.0;
    /** Minimum speed (blocks per second) at which a train flies off the end of its track. */
    public static final double FLY_OFF_SPEED = 1.0;
    /** Ticks over which a detached bogey model slides back under its Sable bogey block. */
    private static final int BOGEY_ATTRACT_TICKS = 8;
    /** Ticks to wait after a failed relocation before probing the train again. */
    private static final int AUTO_ATTACH_RETRY_TICKS = 10;
    /** Cheap re-probe interval while the train has not found any nearby track yet. */
    private static final int AUTO_ATTACH_SCAN_INTERVAL = 2;
    /** How far from a bogey the nearest track edge is looked up. */
    private static final double TRACK_SEARCH_RADIUS = 5.0;

    /** How many bogies are checked against the graph per attempt. */
    private static final int MAX_ATTACH_PROBES = 6;
    /** How many distinct track points are tried before waiting for the next attempt. */
    private static final int MAX_ATTACH_ATTEMPTS = 8;
    /** Track blocks this close to the edge the train flew off are not grabbed again. */
    private static final double FLY_OFF_GRAB_RADIUS = 2.5;
    /** How long a Sable train may exist without physical bodies before it is treated as a phantom. */
    private static final long PHANTOM_CONFIRM_TICKS = 200L;
    /** How long a Sable sub-level may outlive its train before it is cleaned up. */
    private static final long ORPHAN_SUBLEVEL_TIMEOUT_TICKS = 200L;

    // Longitudinal suspension is force based so it can stretch and eventually tear off.
    private static final double LINEAR_STIFFNESS = 90.0;
    private static final double LINEAR_DAMPING = 20.0;
    private static final double MAX_LINEAR_ACCELERATION = 120.0;
    private static final double MAX_TARGET_VELOCITY = 40.0;
    private static final double MAX_TARGET_ANGULAR_VELOCITY = 20.0;
    private static final double MAX_BODY_SPEED = 40.0;
    private static final double MAX_TARGET_STEP = 4.0;

    /**
     * How far a carriage may be pushed along the track before it is considered torn off.
     * Lateral/vertical offsets still use {@link #DETACH_DISTANCE}: a train may roll along
     * its rails, but it must not drift sideways off them.
     */
    private static final double MAX_LONGITUDINAL_OFFSET = 6.0;
    /**
     * While nobody drives, the body that was actually pushed is not held along the track at
     * all; it only has rolling resistance. The remaining carriages follow it through a
     * critically damped spring (see {@link #FREE_ROLL_DAMPING}).
     */
    private static final double FREE_ROLL_STIFFNESS = 25.0;
    private static final double FREE_ROLL_DAMPING = 10.0;
    /** Constant deceleration (blocks per second squared) of an unpowered train. */
    private static final double ROLLING_RESISTANCE = 1.0;
    /**
     * How much of the body's offset from its wheel target is turned into logical train
     * movement each tick. The target chases the body, so this naturally reaches zero when
     * the body stops and cannot run away.
     */
    private static final double FREE_ROLL_TARGET_GAIN = 1.0;
    /** Body offset (blocks) below which a physics-driven train is considered stopped. */
    private static final double FREE_ROLL_OFFSET_DEADZONE = 0.002;

    /** Body sub-level UUID -> carriage data. */
    private static final Map<UUID, SubLevelData> SUBLEVELS = new HashMap<>();
    /** Trains whose adjacent carriage bodies already have contacts disabled. */
    private static final Map<UUID, Boolean> LINKED_TRAINS = new HashMap<>();
    /** Tick at which a Sable train was first seen without any physical body. */
    private static final Map<UUID, Long> PHANTOM_SINCE = new HashMap<>();
    /** Tick at which a Sable sub-level was first seen without its owning train. */
    private static final Map<UUID, Long> ORPHAN_SINCE = new HashMap<>();
    /** Last non-zero logical speed (blocks per tick) of a Sable train, captured before Create stops it. */
    private static final Map<UUID, Double> LAST_TRAIN_SPEED = new HashMap<>();
    /** Track graph a detached train was on, used to put it back on its own rails first. */
    private static final Map<UUID, TrackGraph> DETACHED_GRAPHS = new HashMap<>();
    /** Logical speed (blocks/tick) the train had before it left the rails. */
    private static final Map<UUID, Double> DETACHED_TRAIN_SPEED = new HashMap<>();

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
        /** Per-bogie render/attraction state used to sync the wheel model into the bogey blocks. */
        private final List<BogieSync> bogieSyncs = new ArrayList<>();

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
        /**
         * True for the carriage whose body currently drives the logical train while it is
         * being pushed by the world. That body is not spring-loaded along the track.
         */
        private boolean physicsDriver;
        /** First tick at which snapping this carriage onto a nearby track may be retried. */
        private long nextAttachAttemptTick;
        /** Last on-rail travelling points, kept so we can first try the original track. */
        private final List<SavedPoint> savedPoints = new ArrayList<>();
        /** Smooth-attach target for this carriage, active while it is still detached. */
        private @Nullable Vector3d attachTargetPosition;
        private @Nullable Quaterniond attachTargetOrientation;
        private long attachStartTick = -1L;
        /** World position of the track end the train flew off, excluded from auto-attach. */
        private @Nullable Vec3 flyOffAnchor;
        /** Track edges the train was on when it flew off. It must not latch back onto them. */
        private @Nullable Set<EdgeKey> flyOffEdges;
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

    /**
     * Mutable render state of one bogey. Mirrors the old wheel entity: while on rails the
     * model follows the rail target; after detachment it eases back to the body attachment.
     */
    private static final class BogieSync {
        private boolean initialized;
        private Vec3 position = Vec3.ZERO;
        private final Quaterniond orientation = new Quaterniond();
        private @Nullable Vec3 attractFrom;
        private @Nullable Quaterniond attractFromOrientation;
        private int attractTick = -1;
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
        markSableTrain(train);

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

        LOGGER.info("Sablenautics: Registered sublevel {} for train {} carriage {} (bogies {}, yaw {}, direction {}, glue {})",
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

        // Remember the train's speed before Create zeroes it on an end-of-track block, so
        // a train that arrives at the buffer with momentum still flies off the rails.
        if (!data.detached && !carriage.blocked && train.graph != null) {
            LAST_TRAIN_SPEED.put(train.id, train.speed);
        }

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

        // A detached train is completely free of the track. Suspend its travelling points so
        // Create's collision system cannot make other trains crash into an invisible body,
        // then let one carriage look for a new track for the whole train.
        if (data.detached) {
            if (data.attachTargetPosition != null) {
                // Smooth attachment is being driven by prePhysicsTick; keep the restored
                // travelling points and do not start yet another relocation.
                syncBogieBlocks(serverSubLevel, data);
                return;
            }

            suspendCarriagePoints(train, data);
            data.hasTarget = false;
            if (isAutoAttachLeader(train, data)) {
                if (!tryAttachToSavedTrack(train, serverLevel, data)) {
                    tryAttachToNearbyTrack(serverSubLevel, serverLevel, train, data);
                }
            }
            syncBogieBlocks(serverSubLevel, data);
            return;
        }

        // A Sable train without a track graph cannot follow its rails anymore (Create crashed
        // it, or the graph was lost). Release the whole train so it can be re-railed instead
        // of staying frozen and invisible to the collision system.
        if (train.graph == null) {
            detachWholeTrain(train, -1.0);
            syncBogieBlocks(serverSubLevel, data);
            return;
        }

        if (dce.positionAnchor == null) {
            syncBogieBlocks(serverSubLevel, data);
            return;
        }

        // Create stops the whole train when one of its travelling points runs out of track.
        // A Sable train that is still moving keeps its momentum instead and leaves the rails.
        // But if chunks are unloaded, travelling points may appear blocked even though the
        // track is fine — don't derail in that case.
        if (data.initialized && level.hasChunk(chunkCoord((int) dce.positionAnchor.x), chunkCoord((int) dce.positionAnchor.z))) {
            double endOfTrackSpeed = endOfTrackSpeed(serverSubLevel, train, data, carriage);
            if (endOfTrackSpeed > 0) {
                flyOffEndOfTrack(train, data.carriageIndex(), dce.positionAnchor, endOfTrackSpeed);
                syncBogieBlocks(serverSubLevel, data);
                return;
            }
        }

        Pose3d pose = serverSubLevel.logicalPose();
        Vec3 anchor = dce.positionAnchor;
        Quaterniond targetOrientation = computeTargetOrientation(dce, pose, data.initialYaw());
        Vector3d targetPosition = computeTargetPosition(serverSubLevel, anchor, targetOrientation);
        double targetStep = data.hasTarget ? targetPosition.distance(data.lastTargetPosition) : 0.0;

        updateTargetVelocity(data, targetPosition, targetOrientation, serverLevel);

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(serverLevel);
        PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;
        boolean bodyChunkLoaded = level.hasChunk(
                chunkCoord((int) pose.position().x()), chunkCoord((int) pose.position().z()));

        // Align the freshly assembled / freshly loaded carriage once.
        if (!data.initialized) {
            if (pipeline != null && bodyChunkLoaded) {
                teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
            } else {
                // No physics available (chunks unloaded): set pose directly.
                pose.position().set(targetPosition);
                pose.orientation().set(targetOrientation);
                serverSubLevel.latestLinearVelocity.zero();
                serverSubLevel.latestAngularVelocity.zero();
                resetTargetTracking(data, targetPosition, targetOrientation, serverLevel);
            }
            data.initialized = true;
            data.hasTarget = true;
            syncBogieBlocks(serverSubLevel, data);
            return;
        }

        // Without physics the body cannot follow the rail target. Move it directly so it does
        // not lag behind and tear off as soon as the chunks load again.
        if (pipeline == null || !bodyChunkLoaded) {
            pose.position().set(targetPosition);
            pose.orientation().set(targetOrientation);
            serverSubLevel.latestLinearVelocity.zero();
            serverSubLevel.latestAngularVelocity.zero();
            resetTargetTracking(data, targetPosition, targetOrientation, serverLevel);
            data.hasTarget = true;
            syncBogieBlocks(serverSubLevel, data);
            return;
        }

        // A train nobody is driving follows actual physics: anything that pushes the carriage
        // bodies (another contraption, a piston, an explosion, a thrown object) rolls the
        // train along its rails instead of being absorbed by the rail spring.
        updatePhysicsDrivenSpeed(train, data);

        double bodyError = pose.position().distance(targetPosition);
        if (isTornOff(train, data, pose, targetPosition, bodyError)) {
            if (targetStep > MAX_TARGET_STEP * 2.0) {
                // The rail target jumped (relocation / newly loaded anchor). Snap to it
                // instead of treating the jump as a derailment.
                teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
                resetTargetTracking(data, targetPosition, targetOrientation, serverLevel);
            } else {
                // A carriage that is pushed off the rails takes the whole train with it. This
                // keeps the train a single rigid unit and guarantees it can be re-railed.
                detachWholeTrain(train, bodyError);
            }
        } else {
            ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
        }

        data.hasTarget = !data.detached;
        syncBogieBlocks(serverSubLevel, data);
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

        double lastSpeed = Math.abs(LAST_TRAIN_SPEED.getOrDefault(train.id, 0.0));
        double speed = Math.max(body.latestLinearVelocity.length(),
                Math.max(data.targetVelocity.length(), Math.max(Math.abs(train.speed), lastSpeed) * 20.0));
        return speed >= FLY_OFF_SPEED ? speed : 0.0;
    }

    /**
     * Detaches every carriage of the train. Create only stops at the first blocked carriage,
     * so a train that runs out of track leaves the rails as a whole instead of hanging on
     * the last few blocks of track.
     */
    private static void flyOffEndOfTrack(Train train, int blockedIndex, Vec3 anchor, double speed) {
        Set<EdgeKey> oldEdges = collectTrainEdges(train);
        int detached = detachWholeTrain(train, -1.0);
        if (detached == 0) return;

        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            other.flyOffAnchor = anchor;
            other.flyOffEdges = oldEdges;
            other.flewOff = true;
            writeDetachedFlag(other);
        }

        LOGGER.warn("Sablenautics: train {} ran off the end of the track at carriage {} ({} blocks/s), detached {} carriages",
                train.id, blockedIndex, String.format("%.2f", speed), detached);
    }

    private static Set<EdgeKey> collectTrainEdges(Train train) {
        Set<EdgeKey> edges = new HashSet<>();
        train.forEachTravellingPoint(tp -> {
            if (tp.node1 == null || tp.node2 == null) return;
            TrackNodeLocation first = tp.node1.getLocation();
            TrackNodeLocation second = tp.node2.getLocation();
            edges.add(new EdgeKey(first, second));
            edges.add(new EdgeKey(second, first));
        });
        return edges;
    }

    /**
     * Takes the whole train off the rails.
     * <p>
     * Every carriage is detached and its travelling points are suspended. This is essential:
     * Create's collision detection is based on travelling points, so leaving them on the old
     * rails after the physical body flew away creates an invisible "phantom train" that other
     * trains crash into.
     *
     * @param error body offset that caused the detach, or a negative value for other reasons
     * @return number of carriages that were attached before the call
     */
    private static int detachWholeTrain(Train train, double error) {
        int detached = 0;
        for (SubLevelData data : SUBLEVELS.values()) {
            if (!data.trainId().equals(train.id)) continue;
            if (data.subLevel().isRemoved()) continue;

            if (!data.detached) {
                removeRailJoint(data);
                markDetached(data, train);
                detached++;
            }

            ServerLevel bodyLevel = data.subLevel().getLevel();
            SubLevelPhysicsSystem physicsSystem = bodyLevel != null ? SubLevelPhysicsSystem.get(bodyLevel) : null;
            if (physicsSystem != null && physicsSystem.getPipeline() != null) {
                physicsSystem.getPipeline().wakeUp(data.subLevel());
            }

            data.flyOffAnchor = null;
            data.flyOffEdges = null;
            data.flewOff = false;
            data.autoAttachTrigger = true;
            data.nextAttachAttemptTick = 0L;
            data.hasTarget = false;
            data.initialized = true;
            data.attachTargetPosition = null;
            data.attachTargetOrientation = null;
            data.attachStartTick = -1L;
            suspendCarriagePoints(train, data);
            writeDetachedFlag(data);
        }

        // Remember the graph before freezeTrain() nulls it. A train that merely fell off its
        // own rails can be put back with the exact saved points, without any layout shift.
        if (train.graph != null) {
            DETACHED_GRAPHS.put(train.id, train.graph);
        }
        DETACHED_TRAIN_SPEED.put(train.id,
                LAST_TRAIN_SPEED.getOrDefault(train.id, train.speed));

        freezeTrain(train);
        if (detached > 0 && error >= 0) {
            LOGGER.warn("Sablenautics: carriage of train {} tore off the rails (offset {} blocks), detached {} carriages",
                    train.id, String.format("%.2f", error), detached);
        }
        return detached;
    }

    /** Stops all logical train movement so a derailed train cannot keep driving invisibly. */
    private static void freezeTrain(Train train) {
        train.speed = 0;
        train.targetSpeed = 0;
        train.speedBeforeStall = null;
        train.derailed = true;
        train.manualTick = false;
        // Keeping a graph after detaching would let Create keep ticking the now-suspended
        // travelling points. A null graph stops all logical train movement until relocation.
        train.graph = null;
        LAST_TRAIN_SPEED.remove(train.id);
        if (train instanceof SableTrainAccess access) {
            access.sablenautics$clearMigrations();
        }
        train.leaveStation();
        train.cancelStall();
        train.navigation.cancelNavigation();
        train.navigation.waitingForSignal = null;
        train.occupiedSignalBlocks.clear();
        train.reservedSignalBlocks.clear();
        train.occupiedObservers.clear();
        train.cachedObserverFiltering.clear();
    }

    /**
     * Removes the rail data from one carriage's travelling points. Create's collision scan and
     * train movement skip points without an edge, so an invisible carriage can no longer be hit
     * by another train or move around on its own.
     */
    private static void suspendCarriagePoints(Train train, SubLevelData data) {
        int carriageIndex = data.carriageIndex();
        if (carriageIndex < 0 || carriageIndex >= train.carriages.size()) return;
        Carriage carriage = train.carriages.get(carriageIndex);

        boolean canSave = carriage.leadingBogey().leading().edge != null
                && carriage.leadingBogey().trailing().edge != null;
        if (canSave && carriage.isOnTwoBogeys()) {
            canSave = carriage.trailingBogey().leading().edge != null
                    && carriage.trailingBogey().trailing().edge != null;
        }
        if (data.savedPoints.isEmpty() && canSave) {
            savePoint(data, carriage.leadingBogey().leading());
            savePoint(data, carriage.leadingBogey().trailing());
            if (carriage.isOnTwoBogeys()) {
                savePoint(data, carriage.trailingBogey().leading());
                savePoint(data, carriage.trailingBogey().trailing());
            }
        }

        suspendPoint(carriage.leadingBogey().leading());
        suspendPoint(carriage.leadingBogey().trailing());
        if (carriage.isOnTwoBogeys()) {
            suspendPoint(carriage.trailingBogey().leading());
            suspendPoint(carriage.trailingBogey().trailing());
        }
    }

    private static void suspendCarriagePoints(Train train, int carriageIndex) {
        if (carriageIndex < 0 || carriageIndex >= train.carriages.size()) return;
        Carriage carriage = train.carriages.get(carriageIndex);
        suspendPoint(carriage.leadingBogey().leading());
        suspendPoint(carriage.leadingBogey().trailing());
        if (carriage.isOnTwoBogeys()) {
            suspendPoint(carriage.trailingBogey().leading());
            suspendPoint(carriage.trailingBogey().trailing());
        }
    }

    private static void savePoint(SubLevelData data, TravellingPoint point) {
        data.savedPoints.add(new SavedPoint(point.node1, point.node2, point.edge, point.position));
    }

    /**
     * Restores the travelling points captured when the train left the rails, so it can be
     * attached back to its own track without any layout shift.
     */
    private static boolean restoreCarriagePoints(Train train, SubLevelData data, TrackGraph graph) {
        int carriageIndex = data.carriageIndex();
        if (carriageIndex < 0 || carriageIndex >= train.carriages.size()) return false;
        Carriage carriage = train.carriages.get(carriageIndex);

        List<TravellingPoint> points = new ArrayList<>(4);
        points.add(carriage.leadingBogey().leading());
        points.add(carriage.leadingBogey().trailing());
        if (carriage.isOnTwoBogeys()) {
            points.add(carriage.trailingBogey().leading());
            points.add(carriage.trailingBogey().trailing());
        }

        List<SavedPoint> saved = data.savedPoints;
        if (saved.size() != points.size()) return false;

        for (SavedPoint point : saved) {
            if (point.node1() == null || point.node2() == null || point.edge() == null) {
                return false;
            }
        }

        for (int i = 0; i < points.size(); i++) {
            SavedPoint point = saved.get(i);
            TravellingPoint target = points.get(i);
            target.node1 = point.node1();
            target.node2 = point.node2();
            target.edge = point.edge();
            target.position = point.position();
            target.blocked = false;
        }
        return true;
    }

    private static void suspendPoint(TravellingPoint point) {
        point.edge = null;
        point.node1 = null;
        point.node2 = null;
        point.position = 0;
        point.blocked = false;
    }

    /**
     * Hooks a train up to the nearest track edge to {@code point}. Works for straight rails and
     * for bezier curves (the graph edge is used directly, so no track block is required).
     *
     * @param direction horizontal direction used to align the train along the track
     * @return true when the train now sits on the rails
     */
    public static boolean relocateToNearestTrack(Train train, ServerLevel level, Vec3 point, Vec3 direction) {
        return relocateToNearestTrack(train, level, point, direction, TRACK_SEARCH_RADIUS);
    }

    public static boolean relocateToNearestTrack(Train train, ServerLevel level, Vec3 point,
                                                 Vec3 direction, double searchRadius) {
        if (train == null || level == null || point == null) return false;
        TrackPoint nearest = findNearestTrackPoint(level, point, searchRadius);
        if (nearest == null) return false;
        return relocateToTrackPoint(train, level, nearest, direction, true);
    }

    /**
     * Moves a train onto the exact rail block the player clicked. Uses Create's graph lookup
     * first (which understands straight tracks and block-aligned curves) and falls back to a
     * nearest-edge search so beziers and slightly offset clicks still work.
     */
    public static boolean relocateToTrackBlock(Train train, ServerLevel level, BlockPos pos, Vec3 lookDirection) {
        if (train == null || level == null || pos == null) return false;
        ensureMappings(train);

        Vec3 direction = lookDirection == null ? Vec3.ZERO : lookDirection;
        TrackPoint exact = trackPointAtBlock(level, pos, direction);
        if (exact != null) {
            if (relocateToTrackPoint(train, level, exact, direction, true)) {
                return true;
            }

            // Some valid graph locations cannot be used as the exact rear anchor (for example
            // the very end of a curve or a blocked segment). Search a little around the
            // clicked rail before giving up, without wandering onto a parallel track.
            if (relocateToNearestTrack(train, level, exact.worldPos(), direction, 2.0)) {
                return true;
            }
        }

        boolean moved = relocateToNearestTrack(train, level, Vec3.atCenterOf(pos), direction,
                exact != null ? 2.0 : 6.0);
        if (!moved) {
            LOGGER.debug("Sablenautics: manual relocation failed for train {} at {}", train.id, pos);
        }
        return moved;
    }

    private static @Nullable TrackPoint trackPointAtBlock(ServerLevel level, BlockPos pos, Vec3 lookDirection) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return null;

        try {
            Pair<Vec3, AxisDirection> nearestAxis = track.getNearestTrackAxis(level, pos, state, lookDirection);
            TrackGraphLocation location = TrackGraphHelper.getGraphLocationAt(level, pos,
                    nearestAxis.getSecond(), nearestAxis.getFirst());
            if (location == null || location.graph == null) return null;

            TrackNode node1 = location.graph.locateNode(location.edge.getFirst());
            TrackNode node2 = location.graph.locateNode(location.edge.getSecond());
            if (node1 == null || node2 == null) return null;

            TrackEdge edge = location.graph.getConnectionsFrom(node1).get(node2);
            if (edge == null || edge.getLength() <= 1.0e-4) return null;

            double t = Math.max(0.0, Math.min(1.0, location.position / edge.getLength()));
            return new TrackPoint(location.graph, location.edge, location.position,
                    edge.getPosition(location.graph, t));
        } catch (RuntimeException ignored) {
            return null;
        }
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
        // Curved edges can be long, so keep a generous margin before walking the connections
        // of a node. This skips distant graphs/nodes without missing a nearby bezier.
        double nodeMargin = maxDistance + 64.0;
        double nodeMarginSq = nodeMargin * nodeMargin;

        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation nodeLocation : graph.getNodes()) {
                if (!level.dimension().equals(nodeLocation.dimension)) continue;
                if (nodeLocation.getLocation().distanceToSqr(point) > nodeMarginSq) continue;

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

                    int samples = Math.min(128, Math.max(8, (int) Math.ceil(length * 2)));
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
                                                Vec3 direction, boolean teleportBodies) {
        double preservedSpeed = train.speed;
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

        // Check collisions against trains on the *target* graph, not the graph the train is
        // currently on. Create's own relocator checks against the old graph, which misses
        // occupied rails when the train is moved to a separate track network.
        TrackGraph previousGraph = train.graph;
        try {
            train.graph = graph;
            for (int i = 0; i < recordedVecs.size() - 1; i++) {
                Vec3 vec1 = recordedVecs.get(i);
                Vec3 vec2 = recordedVecs.get(i + 1);
                boolean blocking = i >= blockingIndex.intValue() - 1;
                boolean collided = !blocked.booleanValue()
                        && train.findCollidingTrain(level, vec1, vec2, level.dimension()) != null;
                if (collided || blocking) return false;
            }
            if (blocked.booleanValue()) return false;
        } finally {
            train.graph = previousGraph;
        }

        train.leaveStation();
        train.derailed = false;
        train.navigation.waitingForSignal = null;
        train.occupiedSignalBlocks.clear();
        train.graph = graph;
        if (teleportBodies) {
            DETACHED_TRAIN_SPEED.putIfAbsent(train.id, preservedSpeed);
        }
        train.speed = 0;
        train.targetSpeed = 0;
        train.speedBeforeStall = null;
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

        if (teleportBodies) {
            teleportToRails(train);
        }
        return true;
    }

    /**
     * First re-attach attempt: restore the exact travelling points the train had when it left
     * the rails. This avoids any layout shift and handles the common case of a train that was
     * bumped off its own track. It is skipped after an end-of-track fly-off.
     */
    private static boolean isCandidateNearBogie(List<BogieProbe> probes, Vec3 candidate) {
        double maxDistanceSqr = AUTO_ATTACH_FORCE_DISTANCE * AUTO_ATTACH_FORCE_DISTANCE;
        for (BogieProbe probe : probes) {
            if (probe.point().distanceToSqr(candidate) <= maxDistanceSqr) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryAttachToSavedTrack(Train train, ServerLevel level, SubLevelData data) {
        long now = level.getGameTime();
        if (now < data.nextAttachAttemptTick) return false;

        TrackGraph graph = DETACHED_GRAPHS.get(train.id);
        if (graph == null) return false;

        boolean available = false;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            if (other.flewOff || other.savedPoints.isEmpty()) return false;
            available = true;
        }
        if (!available) return false;

        boolean restored = true;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            if (!restoreCarriagePoints(train, other, graph)) {
                restored = false;
                break;
            }
        }

        if (!restored) {
            for (SubLevelData other : SUBLEVELS.values()) {
                if (other.trainId().equals(train.id)) suspendCarriagePoints(train, other);
            }
            train.graph = null;
            return false;
        }

        train.graph = graph;
        if (beginAttachment(train, level, false)) {
            LOGGER.info("Sablenautics: train {} started attaching to its saved track", train.id);
            return true;
        }

        rejectRelocation(train, level);
        return false;
    }

    /**
     * Looks for a track near a fully detached train and puts the whole train back onto it.
     * <p>
     * The choice of track and the rewrite of every travelling point happen for the entire train
     * at once, because Create's train layout is a single chain of points. Attaching only the
     * carriage that happens to be closest would leave the other carriages on the old, suspended
     * layout and the train would immediately tear off again.
     */
    private static boolean tryAttachToNearbyTrack(ServerSubLevel body, ServerLevel level, Train train,
                                                  SubLevelData data) {
        if (!data.detached) return false;
        if (!data.autoAttachTrigger) return false;
        if (!allCarriagesDetached(train)) return false;

        long now = level.getGameTime();
        if (now < data.nextAttachAttemptTick) return false;
        data.nextAttachAttemptTick = now + AUTO_ATTACH_SCAN_INTERVAL;

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

        Vec3 direction = trackDirection(body, data);
        List<TrackPoint> candidates = new ArrayList<>();

        // Create's relocator starts the layout at the rear-most travelling point. If we used
        // a bogie near the front of the train as the anchor, the whole train would be laid
        // out behind that point and the strict body-distance check could never pass.
        // Shift the rear bogie centre to its actual trailing wheel point as well.
        SubLevelData rearData = null;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id) || !other.detached) continue;
            if (other.subLevel().isRemoved()) continue;
            if (rearData == null || other.carriageIndex() > rearData.carriageIndex()) {
                rearData = other;
            }
        }
        if (rearData != null && !rearData.bogies().isEmpty()) {
            BogieInfo rearBogie = rearData.bogies().get(rearData.bogies().size() - 1);
            Vector3d rearWorld = rearData.subLevel().logicalPose()
                    .transformPosition(rearBogie.localPlotCenter(), new Vector3d());
            Vec3 rearPoint = new Vec3(rearWorld.x, rearWorld.y, rearWorld.z)
                    .subtract(direction.scale(rearBogie.wheelPointSpacing() * 0.5));
            if (hasTrackNearby(level, rearPoint, TRACK_SEARCH_RADIUS)) {
                TrackPoint rearCandidate = findNearestTrackPoint(level, rearPoint, TRACK_SEARCH_RADIUS);
                if (rearCandidate != null
                        && !isFlyOffAnchor(rearData, rearCandidate.worldPos())
                        && !isOnFlyOffEdge(rearData, rearCandidate)) {
                    candidates.add(rearCandidate);
                }
            }
        }

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
            return false;
        }

        // Deduplicate, preserving the rear-first order.
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

        int attempts = Math.min(unique.size(), MAX_ATTACH_ATTEMPTS);
        for (int i = 0; i < attempts; i++) {
            TrackPoint chosen = unique.get(i);
            if (!relocateToTrackPoint(train, level, chosen, direction, false)) {
                LOGGER.debug("Sablenautics: layout rejected for train {} at {}",
                        train.id, chosen.worldPos());
                continue;
            }

            // If the body is already close/aligned, attach it immediately. Otherwise start a
            // physics-driven approach; a bogie that is near the rail allows the whole train
            // to be pulled/rotated smoothly onto it.
            if (beginAttachment(train, level, isCandidateNearBogie(bogieProbes, chosen.worldPos()))) {
                LOGGER.info("Sablenautics: train {} started attaching at {}", train.id, chosen.worldPos());
                return true;
            }

            rejectRelocation(train, level);
        }

        data.nextAttachAttemptTick = now + AUTO_ATTACH_RETRY_TICKS;
        return false;
    }

    /**
     * Starts a smooth re-attachment. Bodies that are already almost exactly on the target
     * attach immediately; everything else is driven onto the rails by physics (no teleport),
     * which keeps the train's momentum intact.
     *
     * @param allowApproach true when a bogie is close enough to justify pulling a rotated or
     *                      scattered train onto the rails
     * @return true when an immediate attach happened or the approach was started
     */
    private static boolean beginAttachment(Train train, ServerLevel level, boolean allowApproach) {
        double maxError = 0.0;
        double maxAngle = 0.0;
        List<Vector3d> targets = new ArrayList<>(train.carriages.size());
        List<Quaterniond> orientations = new ArrayList<>(train.carriages.size());

        for (int i = 0; i < train.carriages.size(); i++) {
            SubLevelData data = dataFor(train, i);
            if (data == null || data.subLevel().isRemoved()) {
                return false;
            }

            Carriage carriage = train.carriages.get(i);
            carriage.updateContraptionAnchors();
            DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
            if (dce == null || dce.positionAnchor == null) {
                return false;
            }

            Pose3d pose = data.subLevel().logicalPose();
            Quaterniond orientation = computeTargetOrientation(dce, pose, data.initialYaw());
            Vector3d target = computeTargetPosition(data.subLevel(), dce.positionAnchor, orientation);
            targets.add(target);
            orientations.add(orientation);

            maxError = Math.max(maxError, pose.position().distance(target));
            maxAngle = Math.max(maxAngle, orientationAngle(pose.orientation(), orientation));
        }

        boolean closeEnough = maxError <= AUTO_ATTACH_DISTANCE && maxAngle <= AUTO_ATTACH_ANGLE;
        if (!closeEnough && !allowApproach) {
            LOGGER.debug("Sablenautics: auto-attach rejected for train {} (error {}, angle {})",
                    train.id, String.format("%.2f", maxError),
                    String.format("%.1f", Math.toDegrees(maxAngle)));
            return false;
        }

        if (closeEnough
                && maxError <= DIRECT_ATTACH_DISTANCE
                && maxAngle <= DIRECT_ATTACH_ANGLE) {
            attachNow(train, level, targets, orientations);
            return true;
        }

        startApproach(train, level, targets, orientations);
        return true;
    }

    private static void attachNow(Train train, ServerLevel level, List<Vector3d> targets,
                                  List<Quaterniond> orientations) {
        for (int i = 0; i < train.carriages.size(); i++) {
            SubLevelData data = dataFor(train, i);
            if (data == null) continue;

            Vector3d target = targets.get(i);
            Quaterniond orientation = orientations.get(i);
            ServerSubLevel body = data.subLevel();
            SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
            PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;

            if (pipeline != null) {
                ensureRailJoint(data, body, pipeline, target, orientation);
                pipeline.wakeUp(body);
            }
            markAttached(data, level, target, orientation);
        }

        finishTrainAttachment(train);
    }

    private static void startApproach(Train train, ServerLevel level, List<Vector3d> targets,
                                      List<Quaterniond> orientations) {
        long now = level.getGameTime();
        for (int i = 0; i < train.carriages.size(); i++) {
            SubLevelData data = dataFor(train, i);
            if (data == null) continue;

            data.attachTargetPosition = targets.get(i);
            data.attachTargetOrientation = orientations.get(i);
            data.attachStartTick = now;
            data.hasTarget = false;
            data.initialized = true;
            writeDetachedFlag(data);
        }

        train.speed = 0;
        train.targetSpeed = 0;
        train.derailed = true;
        LOGGER.info("Sablenautics: train {} is being smoothly pulled onto the track", train.id);
    }

    private static void markAttached(SubLevelData data, ServerLevel level, Vector3d target,
                                     Quaterniond orientation) {
        data.attachTargetPosition = null;
        data.attachTargetOrientation = null;
        data.attachStartTick = -1L;
        data.detached = false;
        data.derailedByDetach = false;
        data.flewOff = false;
        data.flyOffAnchor = null;
        data.flyOffEdges = null;
        data.autoAttachTrigger = true;
        data.initialized = true;
        data.hasTarget = true;
        data.savedPoints.clear();
        resetTargetTracking(data, target, orientation, level);
        writeDetachedFlag(data);
    }

    private static void finishTrainAttachment(Train train) {
        if (hasDetachedCarriage(train)) return;

        train.derailed = false;
        train.targetSpeed = 0;
        train.speedBeforeStall = null;

        Double storedSpeed = DETACHED_TRAIN_SPEED.get(train.id);
        double preservedSpeed = storedSpeed != null ? storedSpeed : preservedBodySpeed(train);
        double maxSpeed = train.maxSpeed();
        train.speed = Mth.clamp(preservedSpeed, -maxSpeed, maxSpeed);

        // Keep the forward part of the physical velocity consistent with the logical train
        // speed, so attachment neither stops the train nor launches it sideways.
        applyPreservedVelocity(train);

        DETACHED_GRAPHS.remove(train.id);
        DETACHED_TRAIN_SPEED.remove(train.id);
        linkNeighbours(train);
    }

    private static void applyPreservedVelocity(Train train) {
        for (SubLevelData data : SUBLEVELS.values()) {
            if (!data.trainId().equals(train.id) || data.detached) continue;
            ServerSubLevel body = data.subLevel();
            ServerLevel level = body.getLevel();
            if (level == null) continue;

            SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
            PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;
            RigidBodyHandle handle = physicsSystem != null ? physicsSystem.getPhysicsHandle(body) : null;
            if (pipeline == null || handle == null) continue;

            Vec3 forward = railForward(train, data);
            if (forward.lengthSqr() < 1.0e-8) continue;

            Vector3d axis = new Vector3d(forward.x, forward.y, forward.z);
            double desiredForward = train.speed * 20.0;
            double currentForward = handle.getLinearVelocity(new Vector3d()).dot(axis);
            handle.addLinearAndAngularVelocity(
                    new Vector3d(axis).mul(desiredForward - currentForward),
                    new Vector3d());
            pipeline.wakeUp(body);
        }
    }

    private static double preservedBodySpeed(Train train) {
        SubLevelData data = dataFor(train, 0);
        if (data == null) return 0.0;
        Vec3 forward = railForward(train, data);
        if (forward.lengthSqr() < 1.0e-8) return 0.0;
        Vector3dc velocity = data.subLevel().latestLinearVelocity;
        return new Vector3d(forward.x, forward.y, forward.z).dot(velocity) / 20.0;
    }

    private static Vec3 railForward(Train train, SubLevelData data) {
        int index = data.carriageIndex();
        if (index < 0 || index >= train.carriages.size() || train.graph == null) {
            return Vec3.ZERO;
        }
        Carriage carriage = train.carriages.get(index);
        TravellingPoint leading = carriage.getLeadingPoint();
        TravellingPoint trailing = carriage.getTrailingPoint();
        if (leading.edge != null && trailing.edge != null) {
            Vec3 forward = leading.getPosition(train.graph).subtract(trailing.getPosition(train.graph));
            if (forward.lengthSqr() > 1.0e-8) return forward.normalize();
        }
        return Vec3.ZERO;
    }

    private static boolean hasDetachedCarriage(Train train) {
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id) && data.detached) {
                return true;
            }
        }
        return false;
    }

    /**
     * Physics-driven approach used while a detached train is being placed on rails. The body
     * keeps its existing velocity (spring damping only steers it) and is rotated towards the
     * rail orientation. Once it is close enough, the rail joint is created without teleporting.
     */
    private static void applyApproach(ServerSubLevel subLevel, RigidBodyHandle handle,
                                      SubLevelData data, double timeStep) {
        Vector3d target = data.attachTargetPosition;
        Quaterniond targetOrientation = data.attachTargetOrientation;
        if (target == null || targetOrientation == null) return;

        double mass = subLevel.getMassTracker().getMass();
        if (mass <= 0) return;

        Pose3d pose = subLevel.logicalPose();
        Vector3d diff = new Vector3d(target).sub(pose.position());
        double distance = diff.length();
        double angle = orientationAngle(pose.orientation(), targetOrientation);

        long now = subLevel.getLevel().getGameTime();
        boolean timedOut = data.attachStartTick >= 0
                && now - data.attachStartTick > APPROACH_TIMEOUT_TICKS;

        if ((distance <= APPROACH_SNAP_DISTANCE && angle <= APPROACH_SNAP_ANGLE) || timedOut) {
            SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(subLevel.getLevel());
            PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;
            if (pipeline != null) {
                ensureRailJoint(data, subLevel, pipeline, target, targetOrientation);
                pipeline.wakeUp(subLevel);
            }
            markAttached(data, subLevel.getLevel(), target, targetOrientation);
            Train train = Create.RAILWAYS.trains.get(data.trainId());
            if (train != null) {
                finishTrainAttachment(train);
            }
            return;
        }

        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        Vector3d acceleration = new Vector3d(diff).mul(APPROACH_STIFFNESS)
                .fma(-APPROACH_DAMPING, velocity);
        clampLength(acceleration, APPROACH_MAX_ACCELERATION);

        // Cancel gravity so the train can be lifted back onto the rails.
        Vector3d gravity = DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), new Vector3d());
        acceleration.sub(gravity);

        double dt = Math.max(timeStep, 1.0 / 20.0 / 8.0);
        Quaterniond inverseOrientation = new Quaterniond(pose.orientation()).conjugate();
        Vector3d impulseLocal = inverseOrientation.transform(
                acceleration.mul(mass * dt), new Vector3d());
        handle.applyLinearAndAngularImpulse(impulseLocal, new Vector3d(), true);

        // Rotate towards the rail frame. This changes angular velocity directly, which keeps
        // the correction smooth instead of hard-snapping the body with a joint.
        Quaterniond delta = new Quaterniond(targetOrientation)
                .mul(new Quaterniond(pose.orientation()).conjugate())
                .normalize();
        double sinHalf = Math.sqrt(Math.max(0.0, delta.x * delta.x + delta.y * delta.y + delta.z * delta.z));
        if (sinHalf > 1.0e-6) {
            Vector3d axis = new Vector3d(delta.x, delta.y, delta.z).div(sinHalf);
            double desired = Math.min(angle * 6.0, APPROACH_MAX_ANGULAR_SPEED);
            Vector3d desiredAngular = axis.mul(desired);
            Vector3d currentAngular = handle.getAngularVelocity(new Vector3d());
            handle.addLinearAndAngularVelocity(new Vector3d(),
                    new Vector3d(desiredAngular).sub(currentAngular));
        }
    }

    /**
     * Rolls back a speculative relocation that did not meet the strict attachment conditions.
     * All travelling points are suspended again so the train remains invisible/irrelevant to
     * other trains until it is actually attached.
     */
    private static void detachTrainData(Train train, long nextAttemptTick) {
        for (SubLevelData data : SUBLEVELS.values()) {
            if (!data.trainId().equals(train.id)) continue;
            suspendCarriagePoints(train, data);
            data.detached = true;
            data.hasTarget = false;
            data.initialized = true;
            data.attachTargetPosition = null;
            data.attachTargetOrientation = null;
            data.attachStartTick = -1L;
            data.nextAttachAttemptTick = nextAttemptTick;
            writeDetachedFlag(data);
        }
    }

    private static void rejectRelocation(Train train, ServerLevel level) {
        freezeTrain(train);
        detachTrainData(train, level.getGameTime() + AUTO_ATTACH_RETRY_TICKS);
    }

    private static @Nullable SubLevelData dataFor(Train train, int carriageIndex) {
        for (SubLevelData candidate : SUBLEVELS.values()) {
            if (candidate.trainId().equals(train.id) && candidate.carriageIndex() == carriageIndex) {
                return candidate;
            }
        }
        return null;
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

    private record SavedPoint(@Nullable TrackNode node1, @Nullable TrackNode node2,
                              @Nullable TrackEdge edge, double position) {
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

    /** @return true if {@code data} is the single carriage that coordinates a whole-train re-attach */
    private static boolean isAutoAttachLeader(Train train, SubLevelData data) {
        int leader = Integer.MAX_VALUE;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            leader = Math.min(leader, other.carriageIndex());
        }
        return leader != Integer.MAX_VALUE && data.carriageIndex() == leader;
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
     * When the train is driven, the body is held by the longitudinal spring; when it is
     * pushed by the world, only the lateral/vertical axes are held and the carriage can
     * roll along the rails. The rail joint (hard angular lock) keeps it aligned.
     */
    public static void prePhysicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        SubLevelData data = SUBLEVELS.get(subLevel.getUniqueId());
        if (data == null || data.subLevel() != subLevel) return;

        ServerLevel level = subLevel.getLevel();
        if (level == null) return;

        if (data.attachTargetPosition != null) {
            if (level.hasChunk(chunkCoord((int) subLevel.logicalPose().position().x()),
                    chunkCoord((int) subLevel.logicalPose().position().z()))) {
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

        Vector3d acceleration;
        Train train = Create.RAILWAYS.trains.get(data.trainId());
        boolean freeRolling = isFreeRolling(train);
        Vec3 forward = freeRolling ? railForward(train, data) : Vec3.ZERO;
        boolean hasAxis = forward.lengthSqr() > 1.0e-8;

        Vector3d positionError = new Vector3d(data.targetPosition).sub(pose.position());
        Vector3d velocityError = new Vector3d(data.targetVelocity).sub(velocity);

        if (hasAxis) {
            Vector3d axis = new Vector3d(forward.x, forward.y, forward.z).normalize();

            // Only the lateral/vertical offset is held by the strong rail suspension. Along
            // the track the body keeps its momentum, so an external push can actually roll
            // the train instead of being absorbed by the spring.
            double alongError = positionError.dot(axis);
            double alongVelocityError = velocityError.dot(axis);
            Vector3d lateralError = new Vector3d(positionError).fma(-alongError, axis);
            Vector3d lateralVelocityError = new Vector3d(velocityError).fma(-alongVelocityError, axis);

            acceleration = lateralError.mul(stiffness).fma(damping, lateralVelocityError);

            // The carriage that drives the logical train is not pulled along the track at
            // all: it is the body that was actually pushed. Pulling it towards its own
            // target would make the train swing back and forth like a pendulum. The other
            // carriages follow it through a critically damped spring.
            if (!data.physicsDriver) {
                acceleration.fma(FREE_ROLL_STIFFNESS * alongError
                        + FREE_ROLL_DAMPING * alongVelocityError, axis);
            }

            // Rolling resistance makes an unpowered train come to rest on its own.
            double alongVelocity = velocity.dot(axis);
            if (Math.abs(alongVelocity) > 1.0e-4) {
                double dt = Math.max(timeStep, 1.0 / 20.0 / 8.0);
                double resistance = Math.min(ROLLING_RESISTANCE, Math.abs(alongVelocity) / dt);
                acceleration.fma(-Math.signum(alongVelocity) * resistance, axis);
            }
        } else {
            acceleration = positionError.mul(stiffness).fma(damping, velocityError);
        }

        clampLength(acceleration, maxAcceleration);

        // Cancel gravity so the suspension only has to track the rail target.
        Vector3d gravity = DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), new Vector3d());
        acceleration.sub(gravity);

        double dt = Math.max(timeStep, 1.0 / 20.0 / 8.0);
        Vector3d impulseWorld = new Vector3d(acceleration).mul(mass * dt);
        Vector3d impulseLocal = inverseOrientation.transform(impulseWorld, new Vector3d());
        handle.applyLinearAndAngularImpulse(impulseLocal, new Vector3d(), true);
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

    /**
     * A carriage that was pushed sideways or vertically off its rail target still tears the
     * whole train off the rails. A carriage that is only further along the track is fine:
     * that is exactly what happens when another object pushes the train forward.
     */
    private static boolean isTornOff(Train train, SubLevelData data, Pose3d pose,
                                     Vector3d targetPosition, double bodyError) {
        if (bodyError <= DETACH_DISTANCE) return false;

        Vec3 forward = railForward(train, data);
        if (forward.lengthSqr() < 1.0e-8) {
            return bodyError > DETACH_DISTANCE;
        }

        Vector3d offset = new Vector3d(pose.position()).sub(targetPosition);
        Vector3d axis = new Vector3d(forward.x, forward.y, forward.z).normalize();
        double along = offset.dot(axis);
        double lateralSq = Math.max(0.0, offset.lengthSquared() - along * along);
        return lateralSq > DETACH_DISTANCE * DETACH_DISTANCE
                || Math.abs(along) > MAX_LONGITUDINAL_OFFSET;
    }

    /**
     * The physical bodies on the rails are independent rigid bodies. Create, however, only
     * advances the train because of its logical {@code train.speed}. While nobody drives the
     * train, move the logical rail target towards the bodies that were actually pushed, so
     * any object can roll the train along its rails.
     * <p>
     * The movement is derived from the body's <em>offset</em> from its wheel target, not from
     * its velocity. That makes the coupling self-terminating: once the body stops, the target
     * catches up and the logical speed drops to zero instead of feeding the momentum back.
     * <p>
     * Called once per train per tick (from the leading carriage).
     */
    private static void updatePhysicsDrivenSpeed(Train train, SubLevelData data) {
        if (!isAutoAttachLeader(train, data)) return;
        if (!isFreeRolling(train)) return;

        double bestSpeed = 0.0;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            if (other.detached() || other.subLevel().isRemoved()) continue;

            Vec3 forward = railForward(train, other);
            if (forward.lengthSqr() < 1.0e-8) continue;

            Vector3dc velocity = other.subLevel().latestLinearVelocity;
            double along = forward.x * velocity.x()
                    + forward.y * velocity.y()
                    + forward.z * velocity.z();
            if (Math.abs(along) > Math.abs(bestSpeed)) {
                bestSpeed = along;
            }
        }

        // Follow the carriage that leads the movement. Carriages that lag behind their own
        // targets must not slow the whole train down; their suspension pulls them along.
        double bestOffset = 0.0;
        SubLevelData bestCarriage = null;
        boolean found = false;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;
            if (other.detached() || other.subLevel().isRemoved()) continue;

            double offset = railOffsetAlong(train, other);
            if (!found) {
                bestOffset = offset;
                bestCarriage = other;
                found = true;
            } else if (bestSpeed > 0.0) {
                if (offset > bestOffset) {
                    bestOffset = offset;
                    bestCarriage = other;
                }
            } else if (bestSpeed < 0.0) {
                if (offset < bestOffset) {
                    bestOffset = offset;
                    bestCarriage = other;
                }
            } else if (Math.abs(offset) > Math.abs(bestOffset)) {
                bestOffset = offset;
                bestCarriage = other;
            }
        }
        if (!found) return;

        double speedPerTick = Math.abs(bestOffset) < FREE_ROLL_OFFSET_DEADZONE
                ? 0.0
                : bestOffset * FREE_ROLL_TARGET_GAIN;

        // Never drive the logical train against the direction the bodies are actually
        // moving: a lagging carriage at the back must slow the target down, not reverse it.
        if (bestSpeed > 0.05 && speedPerTick < 0.0) speedPerTick = 0.0;
        if (bestSpeed < -0.05 && speedPerTick > 0.0) speedPerTick = 0.0;

        // Only the body that actually advances the train is released along the track. All
        // other carriages stay spring-loaded so they cannot swing around their targets.
        for (SubLevelData other : SUBLEVELS.values()) {
            if (other.trainId().equals(train.id)) {
                other.physicsDriver = false;
            }
        }
        if (speedPerTick != 0.0 && bestCarriage != null) {
            bestCarriage.physicsDriver = true;
        }

        double maxSpeed = Math.max(train.maxSpeed(), MAX_BODY_SPEED / 20.0);

        train.speed = Mth.clamp(speedPerTick, -maxSpeed, maxSpeed);
        train.targetSpeed = 0.0;
        train.speedBeforeStall = null;
        if (train.speed != 0.0) {
            // Keep Create from applying its passive slowdown between two physics measurements.
            train.manualTick = true;
            if (train.getCurrentStation() != null) {
                train.leaveStation();
            }
        }
    }

    /**
     * @return how far the carriage body is ahead of its wheel target along the track,
     *         in blocks (negative when it is behind)
     */
    private static double railOffsetAlong(Train train, SubLevelData data) {
        Vec3 forward = railForward(train, data);
        if (forward.lengthSqr() < 1.0e-8) return 0.0;

        int index = data.carriageIndex();
        if (index < 0 || index >= train.carriages.size()) return 0.0;

        ServerSubLevel body = data.subLevel();
        ServerLevel level = body.getLevel();
        if (level == null) return 0.0;

        Vector3dc centerOfMass = body.getMassTracker().getCenterOfMass();
        if (centerOfMass == null) return 0.0;

        // Read the current anchors instead of the cached target: the leading carriage may be
        // ticked before its neighbours, and then their cached target is one tick old.
        Carriage carriage = train.carriages.get(index);
        carriage.updateContraptionAnchors();
        DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
        if (dce == null || dce.positionAnchor == null) return 0.0;

        Pose3d pose = body.logicalPose();
        Vector3d localOffset = localAnchorOffset(body, centerOfMass);
        Vector3d bodyAnchor = pose.orientation()
                .transform(localOffset, new Vector3d())
                .add(pose.position());

        Vector3d axis = new Vector3d(forward.x, forward.y, forward.z).normalize();
        return new Vector3d(bodyAnchor)
                .sub(new Vector3d(dce.positionAnchor.x, dce.positionAnchor.y, dce.positionAnchor.z))
                .dot(axis);
    }

    /**
     * @return true when no player or schedule is currently driving the train, so physical
     *         pushes are allowed to roll it along the rails
     */
    private static boolean isFreeRolling(Train train) {
        if (train == null || train.derailed || train.graph == null) return false;
        if (train.targetSpeed != 0.0) return false;
        if (train.navigation.destination != null) return false;
        return train.speedBeforeStall == null;
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

    /**
     * Pushes the current wheel target into the bogey block entities of this carriage.
     * <p>
     * While the carriage is on the rails the target is the rail target. Once it detaches,
     * the target eases from the last rail position to the body attachment, so the wheels
     * slide back under the Sable bogey block.
     */
    private static void syncBogieBlocks(ServerSubLevel body, SubLevelData data) {
        if (data.bogies().isEmpty()) return;

        ServerLevel level = body.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        int targetTick = container != null
                ? Math.max(0, container.trackingSystem().getInterpolationTick() - 1)
                : (int) level.getGameTime();
        while (data.bogieSyncs.size() < data.bogies().size()) {
            data.bogieSyncs.add(new BogieSync());
        }

        for (int i = 0; i < data.bogies().size(); i++) {
            SableBogeyBlockEntity blockEntity = bogeyBlock(level, data.bogies().get(i));
            if (blockEntity == null) continue;

            BogieTarget target = bogieTarget(data.trainId(), data.carriageIndex(), i);
            if (target == null) {
                blockEntity.clearServerTarget();
                continue;
            }

            BogieSync sync = data.bogieSyncs.get(i);
            Vec3 desired = target.position();
            Quaterniond desiredOrientation = target.orientation();
            Vec3 renderPosition;
            Quaterniond renderOrientation;

            if (target.onRails()) {
                sync.attractTick = -1;
                sync.attractFrom = null;
                sync.attractFromOrientation = null;
                renderPosition = desired;
                renderOrientation = new Quaterniond(desiredOrientation);
            } else if (!sync.initialized) {
                // Loaded already detached: sit directly under the bogey block.
                renderPosition = desired;
                renderOrientation = new Quaterniond(desiredOrientation);
            } else {
                if (sync.attractTick < 0) {
                    sync.attractFrom = sync.position;
                    sync.attractFromOrientation = new Quaterniond(sync.orientation);
                    sync.attractTick = 0;
                }
                sync.attractTick++;

                float t = Math.min(1.0f, sync.attractTick / (float) BOGEY_ATTRACT_TICKS);
                float eased = t * t;
                renderPosition = sync.attractFrom.lerp(desired, eased);
                renderOrientation = new Quaterniond(sync.attractFromOrientation)
                        .slerp(desiredOrientation, eased);
            }

            sync.position = renderPosition;
            sync.orientation.set(renderOrientation);
            sync.initialized = true;

            blockEntity.applyServerTarget(renderPosition, renderOrientation,
                    target.onRails() ? target.wheelAngleDelta() : 0.0f, targetTick);
        }
    }

    private static @Nullable SableBogeyBlockEntity bogeyBlock(ServerLevel level, BogieInfo info) {
        Vector3d center = info.localPlotCenter();
        BlockPos pos = BlockPos.containing(center.x, center.y, center.z);
        return level.getBlockEntity(pos) instanceof SableBogeyBlockEntity bogey ? bogey : null;
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
            PhysicsPipeline pipeline = physicsSystem != null ? physicsSystem.getPipeline() : null;

            Quaterniond orientation = computeTargetOrientation(dce, body.logicalPose(), data.initialYaw());
            Vector3d targetPosition = computeTargetPosition(body, dce.positionAnchor, orientation);

            if (pipeline != null) {
                teleport(body, targetPosition, orientation, pipeline);
                ensureRailJoint(data, body, pipeline, targetPosition, orientation);
            } else {
                Pose3d pose = body.logicalPose();
                pose.position().set(targetPosition);
                pose.orientation().set(orientation);
                body.latestLinearVelocity.zero();
                body.latestAngularVelocity.zero();
            }

            data.detached = false;
            data.derailedByDetach = false;
            data.flewOff = false;
            data.flyOffAnchor = null;
            data.flyOffEdges = null;
            data.autoAttachTrigger = true;
            data.initialized = true;
            data.hasTarget = true;
            data.attachTargetPosition = null;
            data.attachTargetOrientation = null;
            data.attachStartTick = -1L;
            data.savedPoints.clear();
            resetTargetTracking(data, targetPosition, orientation, level);
            writeDetachedFlag(data);
            moved++;
        }

        if (moved > 0) {
            train.derailed = false;
            double preservedSpeed = DETACHED_TRAIN_SPEED.getOrDefault(train.id, train.speed);
            train.speed = Mth.clamp(preservedSpeed, -train.maxSpeed(), train.maxSpeed());
            DETACHED_GRAPHS.remove(train.id);
            DETACHED_TRAIN_SPEED.remove(train.id);
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
            if (data.trainId().equals(train.id)
                    && data.carriageIndex() == carriageIndex
                    && !data.subLevel().isRemoved()) {
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
            if (entity instanceof ItemEntity) continue;
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
        return subLevelFor(train, index) != null || isSableTrain(train);
    }

    /** @return true if this train was assembled with a physics sticker (persisted in its NBT) */
    public static boolean isSableTrain(Train train) {
        if (train == null) return false;
        if (train instanceof SableTrainAccess access && access.sablenautics$isSableTrain()) {
            return true;
        }

        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id)) {
                markSableTrain(train);
                return true;
            }
        }
        return false;
    }

    /** Marks this train as Sable so phantom detection still works after a reload. */
    public static void markSableTrain(Train train) {
        if (train instanceof SableTrainAccess access) {
            access.sablenautics$setSableTrain(true);
        }
    }

    /**
     * Used by the collision mixin. A Sable train that has no live, attached carriage body is
     * invisible to players but would still occupy its old travelling points in Create's
     * collision graph — the classic "phantom train" other trains crash into. Such trains are
     * excluded from collision checks.
     */
    public static boolean isCollisionRelevant(Train train) {
        if (train == null) return false;
        if (!isSableTrain(train)) return true;

        for (SubLevelData data : SUBLEVELS.values()) {
            if (!data.trainId().equals(train.id)) continue;
            if (!data.subLevel().isRemoved() && !data.detached) return true;
        }
        return false;
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

    /**
     * @return the yaw of the carriage in world space, used by the controls HUD direction arrow
     */
    public static float carriageYaw(ServerLevel level, Carriage carriage) {
        if (carriage == null) return 0.0f;
        Train train = carriage.train;
        if (train == null) return 0.0f;

        int index = train.carriages.indexOf(carriage);
        SubLevelData data = dataFor(train, index);

        if (data != null && !data.detached()) {
            Alignment alignment = alignment(level, carriage);
            if (alignment != null) return alignment.yaw();
        }

        if (data != null) {
            Direction assemblyDirection = data.assemblyDirection();
            Vector3d local = new Vector3d(assemblyDirection.getStepX(), 0.0, assemblyDirection.getStepZ());
            Vector3d forward = data.subLevel().logicalPose().orientation().transform(local, new Vector3d());
            if (forward.x * forward.x + forward.z * forward.z > 1.0e-6) {
                return (float) (Math.atan2(forward.z, forward.x) * 180.0 / Math.PI + 180.0);
            }
        }

        return 0.0f;
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
        ORPHAN_SINCE.remove(subLevel.getUniqueId());
        SubLevelData data = SUBLEVELS.remove(subLevel.getUniqueId());
        if (data != null) {
            removeRailJoint(data);
            restoreNeighbourContacts(data);
        }
    }

    public static int purgeSubLevels(ServerLevel level, UUID trainId) {
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
     * Keeps the world free of phantom Sable trains.
     * <p>
     * Two failure modes are handled here:
     * <ul>
     *   <li>a Sable sub-level whose train no longer exists (a floating physics body), and</li>
     *   <li>a Sable train whose sub-level bodies disappeared (an invisible train that other
     *       trains would still collide with through Create's travelling points).</li>
     * </ul>
     * Missing carriages have their travelling points suspended immediately. A train that stays
     * completely body-less for a few seconds is removed from the railway manager entirely.
     */
    public static void validateServer(MinecraftServer server) {
        if (server == null) return;
        long now = server.getTickCount();
        if (now < PHANTOM_CONFIRM_TICKS) return;

        // Drop cached data for sub-levels that Sable already removed.
        List<ServerSubLevel> removedBodies = new ArrayList<>();
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.subLevel().isRemoved()) {
                removedBodies.add(data.subLevel());
            }
        }
        for (ServerSubLevel removed : removedBodies) {
            forget(removed);
        }

        Set<UUID> orphanSeen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;

            for (SubLevel subLevel : new ArrayList<>(container.getAllSubLevels())) {
                if (!(subLevel instanceof ServerSubLevel serverSubLevel) || serverSubLevel.isRemoved()) {
                    continue;
                }

                CompoundTag userData = serverSubLevel.getUserDataTag();
                if (userData == null || !userData.contains(TRAIN_KEY)) continue;

                UUID trainId = userData.getUUID(TRAIN_KEY);
                if (Create.RAILWAYS.trains.containsKey(trainId)) continue;

                UUID subLevelId = serverSubLevel.getUniqueId();
                orphanSeen.add(subLevelId);
                long since = ORPHAN_SINCE.computeIfAbsent(subLevelId, id -> now);
                if (now - since < ORPHAN_SUBLEVEL_TIMEOUT_TICKS) continue;

                LOGGER.warn("Sablenautics: removing orphan Sable sub-level {} for missing train {}",
                        subLevelId, trainId);
                SableTrainDisassembly.purgeSubLevel(level, serverSubLevel);
                ORPHAN_SINCE.remove(subLevelId);
            }
        }
        ORPHAN_SINCE.keySet().removeIf(id -> !orphanSeen.contains(id));

        Set<UUID> phantomSeen = new HashSet<>();
        for (Train train : new ArrayList<>(Create.RAILWAYS.trains.values())) {
            if (!isSableTrain(train)) {
                PHANTOM_SINCE.remove(train.id);
                continue;
            }

            ensureMappings(train);
            int expected = train.carriages.size();
            int live = countLiveBodies(train);

            // Invisible carriages are removed from the collision graph right away; this alone
            // already prevents other trains from crashing into something that is not there.
            // A fully body-less train that is not known to be derailed may simply still be
            // loading, so leave its data untouched in that case.
            if (live > 0 || train.derailed || train.graph == null) {
                if (live < expected) {
                    suspendMissingCarriages(train);
                }
            }

            if (live >= expected) {
                PHANTOM_SINCE.remove(train.id);
                continue;
            }

            if (live > 0) {
                // Partially missing train: keep it usable, but log so it can be investigated.
                PHANTOM_SINCE.remove(train.id);
                continue;
            }

            // Only fully derailed/off-rails trains are removed. An attached train whose
            // sub-levels have not been restored yet (slow load, far-away chunks) keeps its
            // graph and must not be deleted; it is still hidden from collisions above.
            if (!train.derailed && train.graph != null) {
                PHANTOM_SINCE.remove(train.id);
                continue;
            }

            long since = PHANTOM_SINCE.computeIfAbsent(train.id, id -> now);
            if (now - since < PHANTOM_CONFIRM_TICKS) {
                phantomSeen.add(train.id);
                continue;
            }

            LOGGER.warn("Sablenautics: removing phantom Sable train {} ({} carriages, no physical bodies)",
                    train.id, expected);
            removePhantomTrain(server, train);
            PHANTOM_SINCE.remove(train.id);
        }
        PHANTOM_SINCE.keySet().removeIf(id -> !phantomSeen.contains(id));
        LAST_TRAIN_SPEED.keySet().removeIf(id -> !Create.RAILWAYS.trains.containsKey(id));
        DETACHED_GRAPHS.keySet().removeIf(id -> !Create.RAILWAYS.trains.containsKey(id));
        DETACHED_TRAIN_SPEED.keySet().removeIf(id -> !Create.RAILWAYS.trains.containsKey(id));
    }

    private static int countLiveBodies(Train train) {
        int count = 0;
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id) && !data.subLevel().isRemoved()) {
                count++;
            }
        }
        return count;
    }

    private static void suspendMissingCarriages(Train train) {
        for (int i = 0; i < train.carriages.size(); i++) {
            SubLevelData data = dataFor(train, i);
            if (data == null || data.subLevel().isRemoved()) {
                suspendCarriagePoints(train, i);
            }
        }
    }

    private static void removePhantomTrain(MinecraftServer server, Train train) {
        UUID trainId = train.id;
        for (ServerLevel level : server.getAllLevels()) {
            purgeSubLevels(level, trainId);
        }
        SableTrainControlManager.stopForTrain(server, trainId);
        Create.RAILWAYS.removeTrain(trainId);
        LAST_TRAIN_SPEED.remove(trainId);
        DETACHED_GRAPHS.remove(trainId);
        DETACHED_TRAIN_SPEED.remove(trainId);
        CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
    }

    /**
     * Removes leftover {@code CarriageContraptionEntity} objects belonging to Sable trains.
     * Older versions or interrupted assemblies could leave one behind; such an entity renders
     * another invisible/duplicated train and is a perfect candidate for a "phantom train".
     */
    public static void discardPhantomEntities(MinecraftServer server) {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> entities = new ArrayList<>();
            level.getAllEntities().forEach(entities::add);

            for (Entity entity : entities) {
                if (!(entity instanceof CarriageContraptionEntity carriageEntity)) continue;
                Carriage carriage = carriageEntity.getCarriage();
                if (carriage == null || !isSableCarriage(carriage)) continue;
                LOGGER.warn("Sablenautics: discarding leftover carriage entity {} of Sable train {}",
                        carriageEntity.getUUID(), carriage.train != null ? carriage.train.id : null);
                carriageEntity.discard();
            }
        }
    }

    /**
     * Rebuilds the sub-level mapping from persisted user data. Needed for disassembly when
     * the station asks before the plot had a chance to tick after a reload.
     */
    public static void ensureMappings(Train train) {
        int found = 0;
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id) && !data.subLevel().isRemoved()) found++;
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
        if (userData.contains(FLY_OFF_ANCHOR_X_KEY)) {
            data.flyOffAnchor = new Vec3(userData.getDouble(FLY_OFF_ANCHOR_X_KEY),
                    userData.getDouble(FLY_OFF_ANCHOR_Y_KEY),
                    userData.getDouble(FLY_OFF_ANCHOR_Z_KEY));
        }
        // A detached carriage keeps the pose it was saved with; an attached one is aligned once.
        data.initialized = data.detached;

        SUBLEVELS.put(subLevel.getUniqueId(), data);
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train != null) markSableTrain(train);
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
            tag.putDouble("WheelPointSpacing", bogie.wheelPointSpacing());
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
            double wheelPointSpacing = tag.contains("WheelPointSpacing")
                    ? tag.getDouble("WheelPointSpacing")
                    : 2.0;
            bogies.add(new BogieInfo(
                    new Vector3d(tag.getDouble("LX"), tag.getDouble("LY"), tag.getDouble("LZ")),
                    new Vec3(tag.getDouble("IX"), tag.getDouble("IY"), tag.getDouble("IZ")),
                    style, size, tag.getCompound("Data").copy(), axis,
                    tag.getBoolean("UpsideDown"), tag.getDouble("WheelRadius"),
                    wheelPointSpacing, state));
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
        PHANTOM_SINCE.clear();
        ORPHAN_SINCE.clear();
        LAST_TRAIN_SPEED.clear();
        DETACHED_GRAPHS.clear();
        DETACHED_TRAIN_SPEED.clear();
    }
}
