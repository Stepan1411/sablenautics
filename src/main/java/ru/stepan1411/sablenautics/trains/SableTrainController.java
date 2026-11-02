package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a Create carriage as a Sable sub-level.
 * <p>
 * The carriage body is a free rigid body. The visible wheel model is a separate
 * {@link ru.stepan1411.sablenautics.content.entities.SableBogeyEntity} (not a Sable
 * sub-level); the carriage itself keeps an invisible bogey block so Create's train data
 * and disassembly still work. While on rails the body is held by a longitudinal spring
 * and an angular rail joint. If it is pushed more than {@link #DETACH_DISTANCE} blocks
 * away from the rail target it tears off and the train is marked as derailed.
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
    private static final String BOGIES_KEY = "sablenautics_bogies";

    private static final String ROLE_BODY = "body";

    /** Distance between the body and the wheel model at which the train tears off. */
    public static final double DETACH_DISTANCE = 3.0;
    /** Re-attach only when the wheel model is this close to its rail target... */
    public static final double REATTACH_DISTANCE = 1.0;
    /** ...and the body is aligned with the track to within this angle. */
    public static final double REATTACH_ANGLE = Math.toRadians(15.0);

    // Longitudinal suspension is force based so it can stretch and eventually tear off.
    private static final double LINEAR_STIFFNESS = 120.0;
    private static final double LINEAR_DAMPING = 22.0;
    private static final double MAX_LINEAR_ACCELERATION = 400.0;
    private static final double MAX_TARGET_VELOCITY = 60.0;
    private static final double MAX_TARGET_ANGULAR_VELOCITY = 20.0;

    /** Body sub-level UUID -> carriage data. */
    private static final Map<UUID, SubLevelData> SUBLEVELS = new HashMap<>();

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

        private boolean detached;
        private boolean derailedByDetach;
        private boolean initialized;

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

        Carriage carriage = train.carriages.get(data.carriageIndex());

        // Create used to refresh anchors in CarriageContraptionEntity#tick. There is no entity now,
        // so refresh them from the travelling points here.
        carriage.updateContraptionAnchors();

        DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
        if (dce == null || dce.positionAnchor == null) return;

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
            double angle = orientationAngle(pose.orientation(), targetOrientation);
            if (bodyError <= REATTACH_DISTANCE && angle <= REATTACH_ANGLE) {
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
                reattach(data, train);
            }
        }

        data.hasTarget = true;
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

        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        Vector3d acceleration = new Vector3d(data.targetPosition)
                .sub(pose.position())
                .mul(LINEAR_STIFFNESS)
                .fma(LINEAR_DAMPING, new Vector3d(data.targetVelocity).sub(velocity));
        clampLength(acceleration, MAX_LINEAR_ACCELERATION);

        // Cancel gravity so the suspension only has to track the rail target.
        Vector3d gravity = DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), new Vector3d());
        acceleration.sub(gravity);

        double dt = Math.max(timeStep, 1.0 / 20.0 / 8.0);
        Vector3d impulseWorld = new Vector3d(acceleration).mul(mass * dt);
        Vector3d impulseLocal = inverseOrientation.transform(impulseWorld, new Vector3d());
        handle.applyLinearAndAngularImpulse(impulseLocal, new Vector3d(), true);
    }

    private static void detach(SubLevelData data, Train train, double error) {
        data.detached = true;
        data.derailedByDetach = true;
        data.hasTarget = false;
        train.derailed = true;
        writeDetachedFlag(data);
        LOGGER.warn("Sablenautics: carriage {} of train {} tore off the rails (offset {} blocks)",
                data.carriageIndex(), train.id, String.format("%.2f", error));
    }

    private static void reattach(SubLevelData data, Train train) {
        data.detached = false;
        if (data.derailedByDetach) {
            data.derailedByDetach = false;
            train.derailed = false;
        }
        writeDetachedFlag(data);
        LOGGER.info("Sablenautics: carriage {} of train {} re-attached to the rails",
                data.carriageIndex(), train.id);
    }

    private static void updateTargetVelocity(SubLevelData data, Vector3d targetPosition,
                                             Quaterniond targetOrientation, ServerLevel level) {
        long now = level.getGameTime();
        if (data.hasTarget) {
            double dtTicks = Math.max(1L, now - data.lastTargetTick);
            double dt = dtTicks / 20.0;

            data.targetVelocity.set(targetPosition).sub(data.lastTargetPosition).div(dt);
            clampLength(data.targetVelocity, MAX_TARGET_VELOCITY);

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

    public record BogieTarget(Vec3 position, Quaterniond orientation, boolean attached,
                              float wheelAngleDelta) {
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

        Carriage carriage = train.carriages.get(carriageIndex);
        carriage.updateContraptionAnchors();

        ServerLevel level = body.getLevel();
        DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
        if (dce == null || dce.positionAnchor == null) return null;

        BogieInfo info = data.bogies().get(bogieIndex);
        Vector3dc centerOfMass = body.getMassTracker().getCenterOfMass();
        if (centerOfMass == null) return null;

        if (!data.detached()) {
            Quaterniond targetOrientation = computeTargetOrientation(dce, body.logicalPose(), data.initialYaw());
            Vector3d targetPosition = computeTargetPosition(body, dce.positionAnchor, targetOrientation);
            Vector3d localOffset = new Vector3d(info.localPlotCenter()).sub(centerOfMass);
            Vector3d world = new Vector3d(targetPosition)
                    .add(targetOrientation.transform(localOffset, new Vector3d()));
            float angleDelta = (float) (360.0 * train.speed / (Math.PI * 2.0 * info.wheelRadius()));
            return new BogieTarget(new Vec3(world.x, world.y, world.z), targetOrientation, true, angleDelta);
        }

        Pose3d bodyPose = body.logicalPose();
        Vector3d world = bodyPose.transformPosition(info.localPlotCenter(), new Vector3d());
        return new BogieTarget(new Vec3(world.x, world.y, world.z),
                new Quaterniond(bodyPose.orientation()), false, 0.0f);
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
        data.detached = userData.getBoolean(DETACHED_KEY);
        // A detached carriage keeps the pose it was saved with; an attached one is aligned once.
        data.initialized = data.detached;

        SUBLEVELS.put(subLevel.getUniqueId(), data);
        LOGGER.info("Sablenautics: Restored sublevel {} for train {} carriage {} ({} bogey entity data, detached {})",
                subLevel.getUniqueId(), trainId, carriageIndex, data.bogies().size(), data.detached);
        return data;
    }

    private static void writeDetachedFlag(SubLevelData data) {
        CompoundTag bodyData = data.subLevel().getUserDataTag();
        if (bodyData == null) bodyData = new CompoundTag();
        bodyData.putBoolean(DETACHED_KEY, data.detached);
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
        }
        SUBLEVELS.clear();
        SableBogeyEntity.clearTracked();
    }
}
