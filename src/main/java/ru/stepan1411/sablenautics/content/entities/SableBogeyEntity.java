package ru.stepan1411.sablenautics.content.entities;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import ru.stepan1411.sablenautics.trains.BogieInfo;
import ru.stepan1411.sablenautics.trains.SableTrainController;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny non-Sable entity that renders the original bogey model below the carriage.
 * The carriage itself keeps an invisible bogey block so Create's train logic is untouched.
 */
public class SableBogeyEntity extends Entity {
    private static final EntityDataAccessor<String> STYLE =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SIZE =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<CompoundTag> BOGEY_DATA =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Integer> AXIS =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> WHEEL_ANGLE =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> QX =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> QY =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> QZ =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> QW =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> BODY_VALID =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> BODY_X =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BODY_Y =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BODY_Z =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.FLOAT);

    private static final Set<SableBogeyEntity> TRACKED = ConcurrentHashMap.newKeySet();
    private static final int ATTRACT_TICKS = 8;

    private @Nullable UUID trainId;
    private int carriageIndex = -1;
    private int bogieIndex = -1;
    private double wheelRadius = 0.5;
    private int orphanTicks;

    private @Nullable Vec3 attractFrom;
    private @Nullable Quaterniond attractFromOrientation;
    private int attractTick = -1;

    private float previousWheelAngle;
    private float wheelAngle;
    private boolean wheelAngleSet;

    private Vec3 previousRailPosition = Vec3.ZERO;
    private Vec3 railPosition = Vec3.ZERO;
    private final Quaternionf previousRailOrientation = new Quaternionf();
    private final Quaternionf railOrientation = new Quaternionf();
    private boolean railPoseSet;

    public SableBogeyEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STYLE, "");
        builder.define(SIZE, "create:small");
        builder.define(BOGEY_DATA, new CompoundTag());
        builder.define(AXIS, Direction.Axis.Z.ordinal());
        builder.define(WHEEL_ANGLE, 0.0f);
        builder.define(QX, 0.0f);
        builder.define(QY, 0.0f);
        builder.define(QZ, 0.0f);
        builder.define(QW, 1.0f);
        builder.define(BODY_VALID, false);
        builder.define(BODY_X, 0.0f);
        builder.define(BODY_Y, 0.0f);
        builder.define(BODY_Z, 0.0f);
    }

    public void configure(UUID trainId, int carriageIndex, int bogieIndex, BogieInfo info) {
        this.trainId = trainId;
        this.carriageIndex = carriageIndex;
        this.bogieIndex = bogieIndex;
        this.wheelRadius = Math.max(0.01, info.wheelRadius());

        entityData.set(STYLE, info.styleId().toString());
        entityData.set(SIZE, info.sizeId().toString());
        entityData.set(BOGEY_DATA, info.bogeyData().copy());
        entityData.set(AXIS, info.axis().ordinal());
        entityData.set(QW, 1.0f);

        Vec3 initial = info.initialWorldPosition();
        setPos(initial.x, initial.y, initial.z);
        setOldPosAndRot();
    }

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide) {
            // The world position comes from the rail target synced by the server, not from
            // the carriage body: the body may sway away from the rails but the wheels must
            // stay on the track. Buffer two ticks so rendering can interpolate between
            // them without the extra delay of vanilla entity lerping.
            float syncedAngle = entityData.get(WHEEL_ANGLE);
            if (!wheelAngleSet) {
                previousWheelAngle = syncedAngle;
                wheelAngle = syncedAngle;
                wheelAngleSet = true;
            } else {
                previousWheelAngle = wheelAngle;
                wheelAngle += Mth.wrapDegrees(syncedAngle - wheelAngle);
            }

            Vec3 currentPosition = new Vec3(getX(), getY(), getZ());
            Quaternionf currentOrientation = getSyncedOrientation();
            if (!railPoseSet) {
                previousRailPosition = currentPosition;
                railPosition = currentPosition;
                previousRailOrientation.set(currentOrientation);
                railOrientation.set(currentOrientation);
                railPoseSet = true;
            } else {
                previousRailPosition = railPosition;
                railPosition = currentPosition;
                previousRailOrientation.set(railOrientation);
                railOrientation.set(currentOrientation);
            }
            return;
        }

        TRACKED.add(this);

        if (trainId == null || carriageIndex < 0 || bogieIndex < 0) {
            entityData.set(BODY_VALID, false);
            if (++orphanTicks > 200) discard();
            return;
        }

        SableTrainController.BogieTarget target =
                SableTrainController.bogieTarget(trainId, carriageIndex, bogieIndex);
        if (target == null) {
            entityData.set(BODY_VALID, false);
            // Give the body sub-level time to load after a world restart.
            if (++orphanTicks > 200) discard();
            return;
        }
        orphanTicks = 0;

        Vec3 desired = target.position();
        Quaterniond orientation = target.orientation();

        if (target.onRails()) {
            attractTick = -1;
            attractFrom = null;
            attractFromOrientation = null;
            setPos(desired.x, desired.y, desired.z);
            setDeltaMovement(Vec3.ZERO);
            applySyncedOrientation(orientation);
        } else {
            // When the wheels tear off the rails, pull them toward the carriage body over
            // a short time instead of snapping instantly.
            if (attractTick < 0) {
                attractFrom = new Vec3(getX(), getY(), getZ());
                Quaternionf current = getSyncedOrientation();
                attractFromOrientation = new Quaterniond(current.x, current.y, current.z, current.w);
                attractTick = 0;
            }
            attractTick++;

            float t = Math.min(1.0f, attractTick / (float) ATTRACT_TICKS);
            // Ease-in: starts slowly and accelerates towards the end.
            float eased = t * t;

            Vec3 position = attractFrom.lerp(desired, eased);
            setPos(position.x, position.y, position.z);
            setDeltaMovement(Vec3.ZERO);

            Quaterniond attracted = new Quaterniond(attractFromOrientation).slerp(orientation, eased);
            applySyncedOrientation(attracted);
        }

        Vec3 bodyAttachment = target.bodyAttachment();
        entityData.set(BODY_X, (float) bodyAttachment.x);
        entityData.set(BODY_Y, (float) bodyAttachment.y);
        entityData.set(BODY_Z, (float) bodyAttachment.z);
        entityData.set(BODY_VALID, true);

        float angle = entityData.get(WHEEL_ANGLE) - target.wheelAngleDelta();
        entityData.set(WHEEL_ANGLE, angle % 360.0f);
    }

    private void applySyncedOrientation(Quaterniond orientation) {
        entityData.set(QX, (float) orientation.x);
        entityData.set(QY, (float) orientation.y);
        entityData.set(QZ, (float) orientation.z);
        entityData.set(QW, (float) orientation.w);

        Vector3d euler = orientation.getEulerAnglesYXZ(new Vector3d());
        setYRot((float) Math.toDegrees(-euler.y));
        setXRot((float) Math.toDegrees(euler.x));
    }

    @Override
    public void remove(RemovalReason reason) {
        TRACKED.remove(this);
        super.remove(reason);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (trainId != null) tag.putUUID("Train", trainId);
        tag.putInt("Carriage", carriageIndex);
        tag.putInt("Bogie", bogieIndex);
        tag.putString("Style", entityData.get(STYLE));
        tag.putString("Size", entityData.get(SIZE));
        tag.put("BogeyData", entityData.get(BOGEY_DATA).copy());
        tag.putInt("Axis", entityData.get(AXIS));
        tag.putDouble("WheelRadius", wheelRadius);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        trainId = tag.hasUUID("Train") ? tag.getUUID("Train") : null;
        carriageIndex = tag.getInt("Carriage");
        bogieIndex = tag.getInt("Bogie");
        entityData.set(STYLE, tag.getString("Style"));
        entityData.set(SIZE, tag.getString("Size"));
        entityData.set(BOGEY_DATA, tag.getCompound("BogeyData").copy());
        entityData.set(AXIS, tag.getInt("Axis"));
        wheelRadius = tag.getDouble("WheelRadius");
        if (wheelRadius <= 0) wheelRadius = 0.5;
    }

    public ResourceLocation getStyleId() {
        ResourceLocation id = ResourceLocation.tryParse(entityData.get(STYLE));
        return id != null ? id : ResourceLocation.fromNamespaceAndPath("create", "standard");
    }

    public ResourceLocation getSizeId() {
        ResourceLocation id = ResourceLocation.tryParse(entityData.get(SIZE));
        return id != null ? id : ResourceLocation.fromNamespaceAndPath("create", "small");
    }

    public CompoundTag getBogeyRenderData() {
        return entityData.get(BOGEY_DATA);
    }

    public Direction.Axis getBogeyAxis() {
        int ordinal = entityData.get(AXIS);
        Direction.Axis[] values = Direction.Axis.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Direction.Axis.Z;
    }

    public float getWheelAngle(float partialTick) {
        if (!wheelAngleSet) {
            return entityData.get(WHEEL_ANGLE);
        }
        return Mth.lerp(partialTick, previousWheelAngle, wheelAngle);
    }

    public Quaternionf getSyncedOrientation() {
        return new Quaternionf(entityData.get(QX), entityData.get(QY), entityData.get(QZ), entityData.get(QW));
    }

    /**
     * @return the matching point on the carriage body in world space, or {@code null} if the
     *         body is currently not available
     */
    public @Nullable Vec3 getBodyAttachment() {
        if (!entityData.get(BODY_VALID)) {
            return null;
        }
        return new Vec3(entityData.get(BODY_X), entityData.get(BODY_Y), entityData.get(BODY_Z));
    }

    /**
     * @return the rail-target position interpolated between the last two client ticks
     */
    public Vec3 getInterpolatedRailPosition(float partialTick) {
        if (!railPoseSet) {
            return new Vec3(getX(), getY(), getZ());
        }
        return new Vec3(
                Mth.lerp(partialTick, previousRailPosition.x, railPosition.x),
                Mth.lerp(partialTick, previousRailPosition.y, railPosition.y),
                Mth.lerp(partialTick, previousRailPosition.z, railPosition.z));
    }

    /**
     * @return the rail-target orientation interpolated between the last two client ticks
     */
    public Quaternionf getInterpolatedRailOrientation(float partialTick) {
        if (!railPoseSet) {
            return getSyncedOrientation();
        }
        return new Quaternionf(previousRailOrientation).slerp(railOrientation, partialTick);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128.0 * 128.0;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(2.0);
    }

    public static void discardForTrain(UUID trainId) {
        for (SableBogeyEntity entity : List.copyOf(TRACKED)) {
            if (trainId.equals(entity.trainId)) {
                entity.discard();
            }
        }
    }

    public static void discardForCarriage(UUID trainId, int carriageIndex) {
        for (SableBogeyEntity entity : List.copyOf(TRACKED)) {
            if (trainId.equals(entity.trainId) && entity.carriageIndex == carriageIndex) {
                entity.discard();
            }
        }
    }

    public static void clearTracked() {
        TRACKED.clear();
    }
}
