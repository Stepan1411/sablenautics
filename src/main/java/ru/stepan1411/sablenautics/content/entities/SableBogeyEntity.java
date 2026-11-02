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

    private static final Set<SableBogeyEntity> TRACKED = ConcurrentHashMap.newKeySet();

    private @Nullable UUID trainId;
    private int carriageIndex = -1;
    private int bogieIndex = -1;
    private double wheelRadius = 0.5;
    private int orphanTicks;

    private final Quaternionf smoothOrientation = new Quaternionf();
    private boolean smoothOrientationSet;

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
            Quaternionf target = getSyncedOrientation();
            if (!smoothOrientationSet) {
                smoothOrientation.set(target);
                smoothOrientationSet = true;
            } else {
                smoothOrientation.slerp(target, 0.35f);
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
        return entityData.get(WHEEL_ANGLE);
    }

    public Quaternionf getSyncedOrientation() {
        return new Quaternionf(entityData.get(QX), entityData.get(QY), entityData.get(QZ), entityData.get(QW));
    }

    public Quaternionf getSmoothedOrientation() {
        return new Quaternionf(smoothOrientationSet ? smoothOrientation : getSyncedOrientation());
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
