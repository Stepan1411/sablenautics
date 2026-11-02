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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a Create carriage as a Sable sub-level.
 * <p>
 * The carriage body is a free rigid body. A second sub-level contains the bogey blocks
 * (the wheel model) and is moved along the track each tick. While the train is on the
 * rails the body is held above the wheels by a spring force. If the body is pushed more
 * than {@link #DETACH_DISTANCE} blocks away from the wheels it tears off: the spring is
 * removed, the train is marked as derailed and the wheel sub-level starts following the
 * free-flying body.
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
    private static final String BODY_UUID_KEY = "sablenautics_body_uuid";
    private static final String BOGIE_UUID_KEY = "sablenautics_bogie_uuid";
    private static final String DETACHED_KEY = "sablenautics_detached";

    private static final String ROLE_BODY = "body";
    private static final String ROLE_BOGIE = "bogie";

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
    /** Bogie sub-level UUID -> body sub-level UUID. */
    private static final Map<UUID, UUID> BOGIE_TO_BODY = new HashMap<>();

    /**
     * Mutable state of one carriage. Accessor method names intentionally match the old
     * record so the rest of the mod does not have to change.
     */
    public static final class SubLevelData {
        private final ServerSubLevel subLevel;
        private @Nullable ServerSubLevel bogieSubLevel;
        private final UUID trainId;
        private final int carriageIndex;
        private final float initialYaw;
        private final Direction assemblyDirection;
        private final List<AABB> superglue;

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
        /** Body <-> wheel hard glue, active after the train tore off. */
        private @Nullable GenericConstraintHandle bogieJoint;

        private SubLevelData(ServerSubLevel subLevel, @Nullable ServerSubLevel bogieSubLevel,
                             UUID trainId, int carriageIndex, float initialYaw,
                             Direction assemblyDirection, List<AABB> superglue) {
            this.subLevel = subLevel;
            this.bogieSubLevel = bogieSubLevel;
            this.trainId = trainId;
            this.carriageIndex = carriageIndex;
            this.initialYaw = initialYaw;
            this.assemblyDirection = assemblyDirection;
            this.superglue = superglue;
        }

        public ServerSubLevel subLevel() {
            return subLevel;
        }

        public @Nullable ServerSubLevel bogieSubLevel() {
            return bogieSubLevel;
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
                                        @Nullable ServerSubLevel bogieSubLevel,
                                        CarriageContraption contraption, int carriageIndex,
                                        List<AABB> superglue) {
        if (train == null || subLevel == null) return;

        Direction assemblyDirection = contraption != null ? contraption.getAssemblyDirection() : Direction.NORTH;
        float initialYaw = assemblyDirection.getClockWise().toYRot();
        SubLevelData data = new SubLevelData(subLevel, bogieSubLevel, train.id, carriageIndex, initialYaw,
                assemblyDirection, superglue == null ? List.of() : List.copyOf(superglue));
        SUBLEVELS.put(subLevel.getUniqueId(), data);
        if (bogieSubLevel != null) {
            BOGIE_TO_BODY.put(bogieSubLevel.getUniqueId(), subLevel.getUniqueId());
        }

        CompoundTag bodyData = subLevel.getUserDataTag();
        if (bodyData == null) bodyData = new CompoundTag();
        bodyData.putUUID(TRAIN_KEY, train.id);
        bodyData.putInt(CARRIAGE_KEY, carriageIndex);
        bodyData.putFloat(INITIAL_YAW_KEY, initialYaw);
        bodyData.putString(ASSEMBLY_KEY, assemblyDirection.getName());
        bodyData.put(GLUE_KEY, writeGlue(data.superglue()));
        bodyData.putString(ROLE_KEY, ROLE_BODY);
        bodyData.putBoolean(DETACHED_KEY, false);
        if (bogieSubLevel != null) {
            bodyData.putUUID(BOGIE_UUID_KEY, bogieSubLevel.getUniqueId());
        } else {
            bodyData.remove(BOGIE_UUID_KEY);
        }
        subLevel.setUserDataTag(bodyData);

        if (bogieSubLevel != null) {
            CompoundTag bogieData = bogieSubLevel.getUserDataTag();
            if (bogieData == null) bogieData = new CompoundTag();
            bogieData.putUUID(TRAIN_KEY, train.id);
            bogieData.putInt(CARRIAGE_KEY, carriageIndex);
            bogieData.putFloat(INITIAL_YAW_KEY, initialYaw);
            bogieData.putString(ASSEMBLY_KEY, assemblyDirection.getName());
            bogieData.putString(ROLE_KEY, ROLE_BOGIE);
            bogieData.putBoolean(DETACHED_KEY, false);
            bogieData.putUUID(BODY_UUID_KEY, subLevel.getUniqueId());
            bogieSubLevel.setUserDataTag(bogieData);
        }

        LOGGER.info("Sablenautics: Registered sublevel {} for train {} carriage {} (bogie {}, yaw {}, direction {}, glue {})",
                subLevel.getUniqueId(), train.id, carriageIndex,
                bogieSubLevel != null ? bogieSubLevel.getUniqueId() : "none",
                initialYaw, assemblyDirection, data.superglue().size());
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
            // Bogie sub-levels have no data entry of their own; they are moved from the body tick.
            UUID bodyId = BOGIE_TO_BODY.get(subLevel.getUniqueId());
            if (bodyId != null && SUBLEVELS.containsKey(bodyId)) return;

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

        // Legacy saves have only the body sub-level. Keep the old teleport behaviour for them.
        if (data.bogieSubLevel() == null || data.bogieSubLevel().isRemoved()) {
            removeRailJoint(data);
            removeBogieJoint(data);
            if (pipeline != null) {
                teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
            }
            return;
        }

        ServerSubLevel bogie = data.bogieSubLevel();
        if (bogie.getLevel() != serverLevel) {
            // The carriage changed dimension; fall back until a bogie exists in this level.
            removeRailJoint(data);
            removeBogieJoint(data);
            if (pipeline != null) {
                teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
            }
            return;
        }

        // Align the freshly assembled / freshly loaded carriage once.
        if (!data.initialized) {
            if (!data.detached) {
                if (pipeline == null) return;
                teleport(serverSubLevel, targetPosition, targetOrientation, pipeline);
                teleport(bogie, computeTargetPosition(bogie, anchor, targetOrientation), targetOrientation, pipeline);
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
            } else {
                ensureBogieJoint(data, serverSubLevel, bogie, pipeline);
            }
            data.initialized = true;
            data.hasTarget = true;
            return;
        }

        double bodyError = pose.position().distance(targetPosition);

        if (!data.detached) {
            if (bodyError > DETACH_DISTANCE) {
                // The suspension gave out. Weld the wheels to the body at their current
                // relative pose before letting go of the rails.
                removeRailJoint(data);
                if (pipeline != null) {
                    Vector3d attached = computeAttachedPosition(serverSubLevel, bogie, pose);
                    teleport(bogie, attached, new Quaterniond(pose.orientation()), pipeline);
                    Vector3d bodyVelocity = pipeline.getLinearVelocity(serverSubLevel, new Vector3d());
                    Vector3d bodyAngularVelocity = pipeline.getAngularVelocity(serverSubLevel, new Vector3d());
                    pipeline.addLinearAndAngularVelocity(bogie, bodyVelocity, bodyAngularVelocity);
                }
                ensureBogieJoint(data, serverSubLevel, bogie, pipeline);
                detach(data, train, bodyError);
            } else {
                // Wheels stay exactly on the rails. Only the body is driven, so the wheel
                // sub-level is never pulled off the track by constraint reaction forces.
                if (pipeline != null) {
                    Vector3d bogiePosition = computeTargetPosition(bogie, anchor, targetOrientation);
                    teleport(bogie, bogiePosition, targetOrientation, pipeline);
                    pipeline.addLinearAndAngularVelocity(bogie, data.targetVelocity, data.targetAngularVelocity);
                }
                ensureRailJoint(data, serverSubLevel, pipeline, targetPosition, targetOrientation);
            }
        } else {
            // The wheel model is now part of the loose train and is no longer teleported.
            ensureBogieJoint(data, serverSubLevel, bogie, pipeline);

            double angle = orientationAngle(pose.orientation(), targetOrientation);
            if (bodyError <= REATTACH_DISTANCE && angle <= REATTACH_ANGLE) {
                removeBogieJoint(data);
                if (pipeline != null) {
                    Vector3d bogiePosition = computeTargetPosition(bogie, anchor, targetOrientation);
                    teleport(bogie, bogiePosition, targetOrientation, pipeline);
                    pipeline.addLinearAndAngularVelocity(bogie, data.targetVelocity, data.targetAngularVelocity);
                }
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
        if (data == null) {
            UUID bodyId = BOGIE_TO_BODY.get(subLevel.getUniqueId());
            if (bodyId == null) return;
            data = SUBLEVELS.get(bodyId);
            if (data == null || data.detached || data.bogieSubLevel() != subLevel) return;
            applyAntiGravity(subLevel, handle, timeStep);
            return;
        }

        if (data.subLevel() != subLevel) return;
        if (data.detached || !data.hasTarget) return;
        if (data.bogieSubLevel() == null || data.bogieSubLevel().isRemoved()) return;

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

    private static void applyAntiGravity(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        double mass = subLevel.getMassTracker().getMass();
        if (mass <= 0) return;

        Pose3d pose = subLevel.logicalPose();
        Vector3d gravity = DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), new Vector3d());
        if (gravity.lengthSquared() < 1.0e-9) return;

        Quaterniond inverseOrientation = new Quaterniond(pose.orientation()).conjugate();
        Vector3d impulseWorld = new Vector3d(gravity).negate()
                .mul(mass * Math.max(timeStep, 1.0 / 20.0 / 8.0));
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
    private static Vector3d computeAttachedPosition(ServerSubLevel body, ServerSubLevel bogie, Pose3d bodyPose) {
        Vector3dc bodyCom = body.getMassTracker().getCenterOfMass();
        Vector3dc bogieCom = bogie.getMassTracker().getCenterOfMass();
        if (bodyCom == null || bogieCom == null) {
            return new Vector3d(bodyPose.position());
        }

        Quaterniond orientation = new Quaterniond(bodyPose.orientation());
        Vector3d offset = localAnchorOffset(body, bodyCom).sub(localAnchorOffset(bogie, bogieCom));
        return new Vector3d(bodyPose.position()).add(orientation.transform(offset, new Vector3d()));
    }

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

    /**
     * Hard glue between the wheel sub-level and the body, used after the train tore off.
     */
    private static void ensureBogieJoint(SubLevelData data, ServerSubLevel body, ServerSubLevel bogie,
                                         @Nullable PhysicsPipeline pipeline) {
        if (pipeline == null) return;
        if (data.bogieJoint != null && data.bogieJoint.isValid()) return;

        GenericConstraintConfiguration config = new GenericConstraintConfiguration(
                plotCenter(bogie),
                plotCenter(body),
                new Quaterniond(),
                new Quaterniond(),
                EnumSet.allOf(ConstraintJointAxis.class));
        GenericConstraintHandle joint = pipeline.addConstraint(bogie, body, config);
        if (joint == null) return;

        // Body and wheels must not fight each other with contacts while welded together.
        joint.setContactsEnabled(false);
        data.bogieJoint = joint;
        pipeline.wakeUp(bogie);
        pipeline.wakeUp(body);
    }

    private static void removeBogieJoint(SubLevelData data) {
        removeConstraint(data.bogieJoint);
        data.bogieJoint = null;
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
        if (data != null && data.subLevel() == serverSubLevel) return data;

        UUID bodyId = BOGIE_TO_BODY.get(serverSubLevel.getUniqueId());
        if (bodyId == null) return null;
        data = SUBLEVELS.get(bodyId);
        if (data == null) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevel body = container != null ? container.getSubLevel(bodyId) : null;
            if (body instanceof ServerSubLevel bodySubLevel) {
                data = restore(bodySubLevel);
            }
        }
        return data;
    }

    public static @Nullable ServerSubLevel subLevelFor(Train train, int carriageIndex) {
        for (SubLevelData data : SUBLEVELS.values()) {
            if (data.trainId().equals(train.id) && data.carriageIndex() == carriageIndex) {
                return data.subLevel();
            }
        }
        return null;
    }

    public static @Nullable ServerSubLevel bogieFor(ServerSubLevel body) {
        SubLevelData data = SUBLEVELS.get(body.getUniqueId());
        if (data == null) data = restore(body);
        return data != null ? data.bogieSubLevel() : null;
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
        UUID uuid = subLevel.getUniqueId();
        UUID bodyId = BOGIE_TO_BODY.remove(uuid);
        SubLevelData data = SUBLEVELS.remove(uuid);
        if (data == null && bodyId != null) {
            data = SUBLEVELS.get(bodyId);
        }
        if (data != null) {
            removeRailJoint(data);
            removeBogieJoint(data);
            if (data.bogieSubLevel() != null) {
                BOGIE_TO_BODY.remove(data.bogieSubLevel().getUniqueId());
                if (data.bogieSubLevel() == subLevel) {
                    data.bogieSubLevel = null;
                }
            }
            SUBLEVELS.remove(data.subLevel().getUniqueId());
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

        if (ROLE_BOGIE.equals(userData.getString(ROLE_KEY))) {
            UUID bodyId = userData.contains(BODY_UUID_KEY) ? userData.getUUID(BODY_UUID_KEY) : null;
            if (bodyId == null) return null;

            SubLevelData data = SUBLEVELS.get(bodyId);
            if (data == null) {
                SubLevelContainer container = SubLevelContainer.getContainer(subLevel.getLevel());
                SubLevel body = container != null ? container.getSubLevel(bodyId) : null;
                if (body instanceof ServerSubLevel bodySubLevel) {
                    data = restoreBody(bodySubLevel);
                }
            }
            if (data == null) return null;

            BOGIE_TO_BODY.put(subLevel.getUniqueId(), bodyId);
            if (data.bogieSubLevel() == subLevel) {
                // Already linked; never overwrite live detach/init state from stale user data.
                return null;
            }

            data.bogieSubLevel = subLevel;
            data.detached = userData.getBoolean(DETACHED_KEY);
            data.initialized = data.detached;
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

        SubLevelData data = new SubLevelData(subLevel, null, trainId, carriageIndex, initialYaw,
                assemblyDirection, readGlue(userData));
        data.detached = userData.getBoolean(DETACHED_KEY);
        // A detached carriage keeps the pose it was saved with; an attached one is aligned once.
        data.initialized = data.detached;

        if (userData.contains(BOGIE_UUID_KEY)) {
            UUID bogieId = userData.getUUID(BOGIE_UUID_KEY);
            SubLevelContainer container = SubLevelContainer.getContainer(subLevel.getLevel());
            SubLevel bogie = container != null ? container.getSubLevel(bogieId) : null;
            if (bogie instanceof ServerSubLevel bogieSubLevel) {
                data.bogieSubLevel = bogieSubLevel;
                BOGIE_TO_BODY.put(bogieId, subLevel.getUniqueId());
            }
        }

        SUBLEVELS.put(subLevel.getUniqueId(), data);
        LOGGER.info("Sablenautics: Restored sublevel {} for train {} carriage {} (bogie {}, detached {})",
                subLevel.getUniqueId(), trainId, carriageIndex,
                data.bogieSubLevel != null ? data.bogieSubLevel.getUniqueId() : "none",
                data.detached);
        return data;
    }

    private static void writeDetachedFlag(SubLevelData data) {
        CompoundTag bodyData = data.subLevel().getUserDataTag();
        if (bodyData == null) bodyData = new CompoundTag();
        bodyData.putBoolean(DETACHED_KEY, data.detached);
        data.subLevel().setUserDataTag(bodyData);

        ServerSubLevel bogie = data.bogieSubLevel();
        if (bogie != null) {
            CompoundTag bogieData = bogie.getUserDataTag();
            if (bogieData == null) bogieData = new CompoundTag();
            bogieData.putBoolean(DETACHED_KEY, data.detached);
            bogie.setUserDataTag(bogieData);
        }
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

    public static void clear() {
        for (SubLevelData data : SUBLEVELS.values()) {
            removeRailJoint(data);
            removeBogieJoint(data);
        }
        SUBLEVELS.clear();
        BOGIE_TO_BODY.clear();
    }
}
