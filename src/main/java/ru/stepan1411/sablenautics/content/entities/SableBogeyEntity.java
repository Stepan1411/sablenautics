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
    private static final EntityDataAccessor<Long> BODY_ID_MOST =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> BODY_ID_LEAST =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> LOCAL_X =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> LOCAL_Y =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> LOCAL_Z =
            SynchedEntityData.defineId(SableBogeyEntity.class, EntityDataSerializers.LONG);

    private static final Set<SableBogeyEntity> TRACKED = ConcurrentHashMap.newKeySet();

    private @Nullable UUID trainId;
    private int carriageIndex = -1;
    private int bogieIndex = -1;
    private double wheelRadius = 0.5;
    private int orphanTicks;

    private float previousWheelAngle;
    private float wheelAngle;
    private boolean wheelAngleSet;

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
        builder.define(BODY_ID_MOST, 0L);
        builder.define(BODY_ID_LEAST, 0L);
        builder.define(LOCAL_X, 0L);
        builder.define(LOCAL_Y, 0L);
        builder.define(LOCAL_Z, 0L);
    }

    public void configure(UUID trainId, int carriageIndex, UUID bodySubLevelId,
                          int bogieIndex, BogieInfo info) {
        this.trainId = trainId;
        this.carriageIndex = carriageIndex;
        this.bogieIndex = bogieIndex;
        this.wheelRadius = Math.max(0.01, info.wheelRadius());

        entityData.set(STYLE, info.styleId().toString());
        entityData.set(SIZE, info.sizeId().toString());
        entityData.set(BOGEY_DATA, info.bogeyData().copy());
        entityData.set(AXIS, info.axis().ordinal());
        entityData.set(QW, 1.0f);

        setBodySubLevelId(bodySubLevelId);
        Vector3d localCenter = info.localPlotCenter();
        entityData.set(LOCAL_X, Double.doubleToLongBits(localCenter.x));
        entityData.set(LOCAL_Y, Double.doubleToLongBits(localCenter.y));
        entityData.set(LOCAL_Z, Double.doubleToLongBits(localCenter.z));

        Vec3 initial = info.initialWorldPosition();
        setPos(initial.x, initial.y, initial.z);
        setOldPosAndRot();
    }

    private void setBodySubLevelId(@Nullable UUID bodySubLevelId) {
        entityData.set(BODY_ID_MOST, bodySubLevelId == null ? 0L : bodySubLevelId.getMostSignificantBits());
        entityData.set(BODY_ID_LEAST, bodySubLevelId == null ? 0L : bodySubLevelId.getLeastSignificantBits());
    }

    /**
     * @return the Sable sub-level that carries this bogey's carriage, or null for legacy data
     */
    public @Nullable UUID getBodySubLevelId() {
        long most = entityData.get(BODY_ID_MOST);
        long least = entityData.get(BODY_ID_LEAST);
        return most == 0L && least == 0L ? null : new UUID(most, least);
    }

    /**
     * @return this bogey's centre in the carriage sub-level's plot coordinates
     */
    public Vector3d getLocalPlotCenter() {
        return new Vector3d(
                Double.longBitsToDouble(entityData.get(LOCAL_X)),
                Double.longBitsToDouble(entityData.get(LOCAL_Y)),
                Double.longBitsToDouble(entityData.get(LOCAL_Z)));
    }

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide) {
            // The world position is rendered from the carriage sub-level's interpolated pose.
            // Only the wheel spin needs its own client interpolation, because the angle is
            // synced once per tick as a wrapped 0..360 value.
            float syncedAngle = entityData.get(WHEEL_ANGLE);
            if (!wheelAngleSet) {
                previousWheelAngle = syncedAngle;
                wheelAngle = syncedAngle;
                wheelAngleSet = true;
            } else {
                previousWheelAngle = wheelAngle;
                wheelAngle += Mth.wrapDegrees(syncedAngle - wheelAngle);
            }
            return;
        }

        TRACKED.add(this);

        if (trainId == null || carriageIndex < 0 || bogieIndex < 0) {
            if (++orphanTicks > 200) discard();
            return;
        }

        SableTrainController.BogieTarget target =
                SableTrainController.bogieTarget(trainId, carriageIndex, bogieIndex);
        if (target == null) {
            // Give the body sub-level time to load after a world restart.
            if (++orphanTicks > 200) discard();
            return;
        }
        orphanTicks = 0;

        Vec3 position = target.position();
        setPos(position.x, position.y, position.z);
        setDeltaMovement(Vec3.ZERO);

        Quaterniond orientation = target.orientation();
        entityData.set(QX, (float) orientation.x);
        entityData.set(QY, (float) orientation.y);
        entityData.set(QZ, (float) orientation.z);
        entityData.set(QW, (float) orientation.w);

        Vector3d euler = orientation.getEulerAnglesYXZ(new Vector3d());
        setYRot((float) Math.toDegrees(-euler.y));
        setXRot((float) Math.toDegrees(euler.x));

        float angle = entityData.get(WHEEL_ANGLE) - target.wheelAngleDelta();
        entityData.set(WHEEL_ANGLE, angle % 360.0f);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // Keep at least a tiny interpolation window; without it fast-moving trains make
        // the model visibly snap from tick to tick.
        super.lerpTo(x, y, z, yRot, xRot, Math.max(2, steps));
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
        UUID bodySubLevelId = getBodySubLevelId();
        if (bodySubLevelId != null) tag.putUUID("BodySubLevel", bodySubLevelId);
        Vector3d localCenter = getLocalPlotCenter();
        tag.putDouble("LocalX", localCenter.x);
        tag.putDouble("LocalY", localCenter.y);
        tag.putDouble("LocalZ", localCenter.z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        trainId = tag.hasUUID("Train") ? tag.getUUID("Train") : null;
        carriageIndex = tag.getInt("Carriage");
        bogieIndex = tag.getInt("Bogie");
        setBodySubLevelId(tag.hasUUID("BodySubLevel") ? tag.getUUID("BodySubLevel") : null);
        entityData.set(LOCAL_X, Double.doubleToLongBits(tag.getDouble("LocalX")));
        entityData.set(LOCAL_Y, Double.doubleToLongBits(tag.getDouble("LocalY")));
        entityData.set(LOCAL_Z, Double.doubleToLongBits(tag.getDouble("LocalZ")));
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
