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
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
import ru.stepan1411.sablenautics.content.entities.SablenauticsEntities;
import ru.stepan1411.sablenautics.index.SablenauticsBlocks;

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
 * The carriage body is a free rigid body. The visible wheel model is drawn by the
 * {@link SableBogeyBlock} placed under the rail; the carriage itself keeps an invisible
 * bogey block so Create's train data and disassembly still work. While on rails the body
 * is held by a longitudinal spring and an angular rail joint. If it is pushed more than
 * {@link #DETACH_DISTANCE} blocks away from the rail target it tears off and the train
 * is marked as derailed. A carriage that runs out of track while still moving leaves the
 * rails the same way and keeps its momentum, so the train flies off the end instead of
 * stopping dead.
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
    private static final String BOGEY_BLOCK_POSITIONS_KEY = "sablenautics_bogey_block_positions";
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
        /** World positions of bogey blocks (one per bogey), used to place/remove the physical block. */
        private final List<BlockPos> bogeyBlockPositions = new ArrayList<>();

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
                // Place the physical bogey block under the rail so it hangs from the track.
                // The block will be moved to its current rail position every tick in tickSubLevel.
                Vec3 initialWorld = safeBogies.get(i).initialWorldPosition();
                BlockPos bogeyPos = new BlockPos((int) initialWorld.x, (int) initialWorld.y, (int) initialWorld.z);
                if (level.getBlockState(bogeyPos).isAir()) {
                    level.setBlock(bogeyPos, SablenauticsBlocks.SABLE_BOGEY.get().defaultBlockState(), 3);
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(bogeyPos);
                    if (be instanceof SableBogeyBlockEntity bbe) {
                        bbe.setTrainInfo(train.id, carriageIndex, i);
                    }
                    data.bogeyBlockPositions.add(bogeyPos.immutable());
                }
            }
        }
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

        // Update bogey block positions in the world to match current rail positions.
        // The bogey block sits ON the rail (same Y level as travelling point).
        if (!data.detached && pipeline != null) {
            for (int i = 0; i < data.bogies().size(); i++) {
                BogieInfo info = data.bogies().get(i);
                Vector3d bogeyWorld = pose.transformPosition(info.localPlotCenter(), new Vector3d());
                BlockPos bogeyPos = new BlockPos((int) bogeyWorld.x, (int) bogeyWorld.y, (int) bogeyWorld.z);
                BlockPos railPos = bogeyPos;

                if (level.getBlockState(railPos).getBlock() instanceof ITrackBlock) {
                    if (level.getBlockState(bogeyPos).getBlock() != SablenauticsBlocks.SABLE_BOGEY.get()) {
                        level.setBlock(bogeyPos, SablenauticsBlocks.SABLE_BOGEY.get().defaultBlockState(), 3);
                    }
                    data.bogeyBlockPositions.set(i, bogeyPos.immutable());
                } else {
                    // No rail under — remove the bogey block (it detaches from the rail)
                    if (level.getBlockState(bogeyPos).getBlock() == SablenauticsBlocks.SABLE_BOGEY.get()) {
                        level.removeBlock(bogeyPos, false);
                    }
                    data.bogeyBlockPositions.set(i, null);
                }
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
