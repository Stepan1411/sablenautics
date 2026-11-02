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
import com.simibubi.create.content.trains.entity.TrainRelocator;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
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
    /** Track blocks this close to the edge the train flew off are not grabbed again. */
    private static final double FLY_OFF_GRAB_RADIUS = 2.5;

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

        DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
        if (dce == null || dce.positionAnchor == null) return;

        // Create stops the whole train when one of its travelling points runs out of track.
        // A Sable train that is still moving keeps its momentum instead and leaves the rails.
        if (!data.detached && data.initialized) {
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
                if (pipeline == null) return;
                teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
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
            } else {
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
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
        int detached = 0;
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id)) continue;

            other.flyOffAnchor = anchor;
            // Allow ANY detached carriage to trigger auto-attach. The one at blockedIndex
            // gets priority, but others can also try if they find track first.
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
     * Hooks a train up to the track at {@code trackPos}, wherever it is. Create's relocator
     * rewrites every travelling point of the train from that spot, so this works for any
     * track segment, not just the one the train derailed from.
     *
     * @param direction horizontal direction used to pick the right track axis / slope side
     * @return true when the train now sits on the rails
     */
    public static boolean relocateToTrack(Train train, ServerLevel level, BlockPos trackPos, Vec3 direction) {
        if (train == null || level == null || trackPos == null) return false;
        if (!(level.getBlockState(trackPos).getBlock() instanceof ITrackBlock)) return false;

        if (!TrainRelocator.relocate(train, level, trackPos, null, false, direction, false)) {
            return false;
        }

        LOGGER.info("Sablenautics: relocated train {} onto the track at {}", train.id, trackPos);
        teleportToRails(train);
        return true;
    }

    /**
     * Hooks a train onto a bezier curve segment. Used when the bogeys are over a curved track
     * piece that doesn't have a simple ITrackBlock at the exact block position.
     */
    public static boolean relocateToBezier(Train train, ServerLevel level, BlockPos trackBEPos,
                                           BlockPos curveTarget, int segment, Vec3 direction) {
        if (train == null || level == null || trackBEPos == null || curveTarget == null) return false;

        BlockEntity be = level.getBlockEntity(trackBEPos);
        if (!(be instanceof TrackBlockEntity trackBE)) return false;

        BezierConnection connection = trackBE.getConnections().get(curveTarget);
        if (connection == null || !connection.isPrimary()) return false;

        // Determine bezier direction: whether we're moving from trackBEPos towards curveTarget
        // or vice versa. Use the horizontal dot product with the curve's start-to-end vector.
        Vec3 start = connection.starts.getFirst();
        Vec3 end = connection.starts.getSecond();
        Vec3 curveDir = new Vec3(end.x - start.x, 0.0, end.z - start.z).normalize();
        boolean bezierDirection = direction.dot(curveDir) >= 0;

        BezierTrackPointLocation bezierLoc = new BezierTrackPointLocation(curveTarget, segment);

        if (!TrainRelocator.relocate(train, level, trackBEPos, bezierLoc, bezierDirection, direction, false)) {
            return false;
        }

        LOGGER.info("Sablenautics: relocated train {} onto bezier curve at {}->{} segment {}", train.id, trackBEPos, curveTarget, segment);
        teleportToRails(train);
        return true;
    }

    /**
     * Snaps a fully detached train onto any rail segment its trigger carriage is lying on,
     * regardless of how fast the body is moving. A train that is still flying over rails
     * cannot grab the track end it just left (see {@link #isFlyOffAnchor}).
     */
    private static boolean tryAttachToNearbyTrack(ServerSubLevel body, ServerLevel level, Train train,
                                                  SubLevelData data) {
        LOGGER.info("Sablenautics: tryAttachToNearbyTrack called for train {} carriage {} detached={} autoAttachTrigger={}",
                train.id, data.carriageIndex(), data.detached, data.autoAttachTrigger);
        if (!data.detached) return false;
        if (!data.autoAttachTrigger) return false;
        // A single carriage that was pushed off keeps trying its own rail target. Only a train
        // that left the rails as a whole is allowed to latch onto a completely different track.
        if (!allCarriagesDetached(train)) return false;

        long now = level.getGameTime();
        if (now < data.nextAttachAttemptTick) return false;

        // Collect track candidates from ALL detached carriages of this train, not just this one.
        // The trigger carriage might be at the end of the old track while other carriages
        // are already over new track.
        List<TrackCandidate> candidates = new ArrayList<>();
        for (SubLevelData other : SUBLEVELS.values()) {
            if (!other.trainId().equals(train.id) || !other.detached) continue;
            ServerSubLevel otherBody = other.subLevel();
            if (otherBody.isRemoved()) continue;
            candidates.addAll(trackCandidatesForBody(otherBody, level, other));
        }

        if (candidates.isEmpty()) {
            LOGGER.info("Sablenautics: no track candidates found for any carriage of train {}", train.id);
            data.nextAttachAttemptTick = now + AUTO_ATTACH_SCAN_INTERVAL;
            return false;
        }

        // Deduplicate and sort by distance to the trigger carriage
        Vec3 triggerCenter = new Vec3(body.logicalPose().position().x, body.logicalPose().position().y, body.logicalPose().position().z);
        candidates.sort(Comparator.comparingDouble(c -> c.worldPos.distanceToSqr(triggerCenter)));
        List<TrackCandidate> unique = new ArrayList<>();
        for (TrackCandidate c : candidates) {
            if (unique.stream().noneMatch(u -> u.pos.equals(c.pos) && u.segment == c.segment && (u.curveTarget == null ? c.curveTarget == null : u.curveTarget.equals(c.curveTarget)))) {
                unique.add(c);
            }
        }

        Vec3 direction = trackDirection(body, data);
        LOGGER.info("Sablenautics: trying {} unique track candidates for train {} with direction {}", unique.size(), train.id, direction);
        for (TrackCandidate candidate : unique) {
            LOGGER.info("Sablenautics: trying candidate {} (bezier={}, pos={}, curveTarget={}, segment={})",
                    candidate.isBezier() ? "bezier" : "track", candidate.isBezier(), candidate.pos, candidate.curveTarget, candidate.segment);
            boolean success;
            if (candidate.isBezier()) {
                success = relocateToBezier(train, level, candidate.pos, candidate.curveTarget, candidate.segment, direction);
            } else {
                success = relocateToTrack(train, level, candidate.pos, direction);
            }
            if (success) {
                LOGGER.info("Sablenautics: auto-attached train {} to {} at {}", train.id, candidate.isBezier() ? "bezier" : "track", candidate.pos);
                return true;
            } else {
                LOGGER.info("Sablenautics: relocation failed for candidate {}", candidate.pos);
            }
        }

        // The probes walk the whole train, so don't repeat them every single tick.
        data.nextAttachAttemptTick = now + AUTO_ATTACH_RETRY_TICKS;
        return false;
    }

    /**
     * Finds track candidates for a specific carriage body.
     */
    private static List<TrackCandidate> trackCandidatesForBody(ServerSubLevel body, ServerLevel level, SubLevelData data) {
        Pose3d pose = body.logicalPose();
        List<TrackCandidate> candidates = new ArrayList<>();

        for (BogieInfo bogie : data.bogies()) {
            Vector3d world = pose.transformPosition(bogie.localPlotCenter(), new Vector3d());
            Vec3 centerVec = new Vec3(world.x, world.y, world.z);
            BlockPos center = BlockPos.containing(world.x, world.y, world.z);

            // 1. Regular track blocks in a box around the bogey
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -3, -1), center.offset(1, 2, 1))) {
                if (!(level.getBlockState(pos).getBlock() instanceof ITrackBlock)) continue;

                BlockPos immutable = pos.immutable();
                if (candidates.stream().anyMatch(c -> c.pos.equals(immutable) && !c.isBezier())) continue;
                // Don't grab the very track end the train just flew off from.
                if (isFlyOffAnchor(data, immutable)) continue;
                candidates.add(new TrackCandidate(immutable, -1, centerVec));
            }

            // 2. Bezier curve connections from nearby TrackBlockEntities
            for (BlockPos bePos : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
                BlockEntity be = level.getBlockEntity(bePos);
                if (!(be instanceof TrackBlockEntity trackBE)) continue;

                for (Map.Entry<BlockPos, BezierConnection> entry : trackBE.getConnections().entrySet()) {
                    BlockPos curveTarget = entry.getKey();
                    BezierConnection bc = entry.getValue();
                    if (!bc.isPrimary()) continue;

                    // Check if the bogey is actually near this bezier curve using its bounds
                    AABB bounds = bc.getBounds();
                    AABB inflated = bounds.inflate(3.0);
                    if (!inflated.contains(centerVec)) {
                        continue;
                    }
                    LOGGER.info("Sablenautics: bogey {} INSIDE bezier bounds {} at bePos {}", centerVec, bounds, bePos);

                    // The curve is stored at the target position; we check if our bogey is near the curve
                    int maxSegments = bc.getSegmentCount();
                    for (int seg = 0; seg < maxSegments; seg++) {
                        int segment = seg; // effectively final for lambda
                        BlockPos candidatePos = bePos.immutable();
                        if (candidates.stream().anyMatch(c -> c.pos.equals(candidatePos) && c.curveTarget != null && c.curveTarget.equals(curveTarget) && c.segment == segment)) continue;
                        if (isFlyOffAnchor(data, candidatePos, curveTarget)) continue;
                        candidates.add(new TrackCandidate(candidatePos, curveTarget, segment, centerVec));
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Represents a track candidate that can be either a regular track block or a bezier curve segment.
     */
    private static class TrackCandidate {
        final BlockPos pos;        // for regular: the track block pos; for bezier: the TrackBlockEntity pos
        final BlockPos curveTarget; // only for bezier: the target position (other end of curve)
        final int segment;         // -1 for regular track, >=0 for bezier segment
        final Vec3 worldPos;       // for sorting by distance

        TrackCandidate(BlockPos pos, int segment, Vec3 worldPos) {
            this(pos, null, segment, worldPos);
        }

        TrackCandidate(BlockPos pos, BlockPos curveTarget, int segment, Vec3 worldPos) {
            this.pos = pos;
            this.curveTarget = curveTarget;
            this.segment = segment;
            this.worldPos = worldPos;
        }

        boolean isBezier() {
            return segment >= 0;
        }
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
     * @return track candidates (regular blocks or bezier segments) under the bogeys, nearest first
     */
    private static List<TrackCandidate> trackCandidates(ServerSubLevel body, ServerLevel level, SubLevelData data) {
        Pose3d pose = body.logicalPose();
        List<TrackCandidate> candidates = new ArrayList<>();
        List<Vec3> bogieCenters = new ArrayList<>();

        for (BogieInfo bogie : data.bogies()) {
            Vector3d world = pose.transformPosition(bogie.localPlotCenter(), new Vector3d());
            Vec3 centerVec = new Vec3(world.x, world.y, world.z);
            bogieCenters.add(centerVec);
            BlockPos center = BlockPos.containing(world.x, world.y, world.z);

            // 1. Regular track blocks in a box around the bogey
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -3, -1), center.offset(1, 2, 1))) {
                if (!(level.getBlockState(pos).getBlock() instanceof ITrackBlock)) continue;

                BlockPos immutable = pos.immutable();
                if (candidates.stream().anyMatch(c -> c.pos.equals(immutable) && !c.isBezier())) continue;
                // Don't grab the very track end the train just flew off from.
                if (isFlyOffAnchor(data, immutable)) continue;
                candidates.add(new TrackCandidate(immutable, -1, Vec3.atCenterOf(immutable)));
            }

            // 2. Bezier curve connections from nearby TrackBlockEntities
            for (BlockPos bePos : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
                BlockEntity be = level.getBlockEntity(bePos);
                if (!(be instanceof TrackBlockEntity trackBE)) continue;

                for (Map.Entry<BlockPos, BezierConnection> entry : trackBE.getConnections().entrySet()) {
                    BlockPos curveTarget = entry.getKey();
                    BezierConnection bc = entry.getValue();
                    if (!bc.isPrimary()) continue;

                    // Check if the bogey is actually near this bezier curve using its bounds
                    AABB bounds = bc.getBounds();
                    Vec3 bogeyVec = new Vec3(world.x, world.y, world.z);
                    AABB inflated = bounds.inflate(3.0);
                    if (!inflated.contains(bogeyVec)) {
                        LOGGER.info("Sablenautics: bogey {} not in bezier bounds {} (inflated {}) at bePos {}", bogeyVec, bounds, inflated, bePos);
                        continue;
                    }
                    LOGGER.info("Sablenautics: bogey {} INSIDE bezier bounds {} at bePos {}", bogeyVec, bounds, bePos);

                    // The curve is stored at the target position; we check if our bogey is near the curve
                    int maxSegments = bc.getSegmentCount();
                    for (int seg = 0; seg < maxSegments; seg++) {
                        int segment = seg; // effectively final for lambda
                        BlockPos candidatePos = bePos.immutable();
                        if (candidates.stream().anyMatch(c -> c.pos.equals(candidatePos) && c.curveTarget != null && c.curveTarget.equals(curveTarget) && c.segment == segment)) continue;
                        if (isFlyOffAnchor(data, candidatePos, curveTarget)) continue;
                        candidates.add(new TrackCandidate(candidatePos, curveTarget, segment, bogeyVec));
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            LOGGER.debug("Sablenautics: no track candidates found for train {}", data.trainId());
            return candidates;
        }

        Vec3 reference = bogieCenters.isEmpty() ? candidates.get(0).worldPos : bogieCenters.get(0);
        candidates.sort(Comparator.comparingDouble(c -> c.worldPos.distanceToSqr(reference)));
        LOGGER.debug("Sablenautics: found {} track candidates for train {}", candidates.size(), data.trainId());
        return candidates.size() > 5 ? new ArrayList<>(candidates.subList(0, 5)) : candidates;
    }

    private static boolean isFlyOffAnchor(SubLevelData data, BlockPos pos) {
        if (data.flyOffAnchor == null) return false;
        return pos.distToCenterSqr(data.flyOffAnchor) < FLY_OFF_GRAB_RADIUS * FLY_OFF_GRAB_RADIUS;
    }

    private static boolean isFlyOffAnchor(SubLevelData data, BlockPos pos, BlockPos curveTarget) {
        if (data.flyOffAnchor == null) return false;
        // Check both the track BE position and the curve target
        if (pos.distToCenterSqr(data.flyOffAnchor) < FLY_OFF_GRAB_RADIUS * FLY_OFF_GRAB_RADIUS) return true;
        if (curveTarget != null && curveTarget.distToCenterSqr(data.flyOffAnchor) < FLY_OFF_GRAB_RADIUS * FLY_OFF_GRAB_RADIUS) return true;
        return false;
    }

    /**
     * Best guess of the direction the carriage is lying in, used to pick the track axis.
     */
    private static Vec3 trackDirection(ServerSubLevel body, SubLevelData data) {
        Vector3d velocity = body.latestLinearVelocity;
        Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        if (horizontal.lengthSqr() > 1.0e-4) return horizontal.normalize();

        List<BogieInfo> bogies = data.bogies();
        if (bogies.size() >= 2) {
            Pose3d pose = body.logicalPose();
            Vector3d first = pose.transformPosition(bogies.get(0).localPlotCenter(), new Vector3d());
            Vector3d second = pose.transformPosition(bogies.get(1).localPlotCenter(), new Vector3d());
            Vec3 axis = new Vec3(second.x - first.x, 0.0, second.z - first.z);
            if (axis.lengthSqr() > 1.0e-4) return axis.normalize();
        }

        Direction assemblyDirection = data.assemblyDirection();
        Vector3d local = new Vector3d(assemblyDirection.getStepX(), 0.0, assemblyDirection.getStepZ());
        Vector3d rotated = body.logicalPose().orientation().transform(local, new Vector3d());
        Vec3 fallback = new Vec3(rotated.x, 0.0, rotated.z);
        return fallback.lengthSqr() > 1.0e-4 ? fallback.normalize() : new Vec3(0.0, 0.0, 1.0);
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
        data.autoAttachTrigger = true;
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
        data.autoAttachTrigger = true;
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
            data.autoAttachTrigger = true;
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
