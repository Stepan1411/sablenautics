package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;

import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder bogey block inside a Sable carriage.
 * <p>
 * When a carriage is assembled, the original bogey block is replaced by this one and its
 * style, size and bogey data are copied over. The block entity renderer then draws the
 * original train wheel model directly on the block, so no separate entity is needed.
 * <p>
 * The server sends the rail target of this bogey together with the Sable interpolation tick.
 * The client keeps a short history of those samples and samples it on the exact same
 * interpolated tick that Sable uses to render the carriage body. This keeps the wheels on
 * the rails without lag or leading, both while travelling and while tearing off. Once the
 * carriage is fully detached the target eases back to the body, so the wheels end up under
 * the Sable bogey again.
 * <p>
 * The target is only sent in block-entity update packets and is not saved to disk.
 */
public class SableBogeyBlockEntity extends AbstractBogeyBlockEntity {

    private static final String RENDER_SIZE_KEY = "RenderSize";
    private static final String HAS_TARGET_KEY = "HasBogeyTarget";
    private static final String TARGET_X_KEY = "BogeyTargetX";
    private static final String TARGET_Y_KEY = "BogeyTargetY";
    private static final String TARGET_Z_KEY = "BogeyTargetZ";
    private static final String TARGET_QX_KEY = "BogeyTargetQX";
    private static final String TARGET_QY_KEY = "BogeyTargetQY";
    private static final String TARGET_QZ_KEY = "BogeyTargetQZ";
    private static final String TARGET_QW_KEY = "BogeyTargetQW";
    private static final String TARGET_TICK_KEY = "BogeyTargetTick";
    private static final String WHEEL_ANGLE_DELTA_KEY = "WheelAngleDelta";
    private static final String SUSPENSION_OFFSET_KEY = "SuspensionOffset";

    private static final int MAX_TARGET_HISTORY = 20;

    /** Suspension height range and adjustment step, in blocks. */
    public static final float MIN_SUSPENSION_OFFSET = -3.0f;
    public static final float MAX_SUSPENSION_OFFSET = 3.0f;
    public static final float SUSPENSION_STEP = 0.25f;

    private @Nullable ResourceLocation renderSizeId;

    /** Last rail target received from the server, in world coordinates. */
    private boolean hasTarget;
    private Vec3 targetPosition = Vec3.ZERO;
    private final Quaternionf targetOrientation = new Quaternionf();
    private float wheelAngleDelta;
    private int targetTick;
    /**
     * Visual height of this bogey's wheel model relative to the rail target, in blocks.
     * Negative values lower the wheels, positive values raise them.
     */
    private float suspensionOffset;

    /** Client-only history of rail targets, tagged with Sable's interpolation tick. */
    private final List<TargetSample> targetHistory = new ArrayList<>();

    public record RenderTarget(Vec3 position, Quaternionf orientation) {
    }

    private record TargetSample(int tick, Vec3 position, Quaternionf orientation) {
    }

    public SableBogeyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return SablenauticsBogeyStyles.SABLE;
    }

    public void setRenderSize(@Nullable ResourceLocation sizeId) {
        this.renderSizeId = sizeId;
        setChanged();
    }

    /**
     * @return the size of the original bogey that this block replaced, or the block's own
     *         size when none was stored
     */
    public BogeySizes.BogeySize getRenderSize() {
        if (renderSizeId != null) {
            BogeySizes.BogeySize size = BogeySizes.all().get(renderSizeId);
            if (size != null) {
                return size;
            }
        }
        if (getBlockState().getBlock() instanceof AbstractBogeyBlock<?> bogey) {
            return bogey.getSize();
        }
        return BogeySizes.SMALL;
    }

    /**
     * Called on the server every tick to push the current rail target to the client.
     *
     * @param wheelAngleDelta degrees the wheel rotates per game tick
     * @param tick            Sable interpolation tick this target belongs to
     */
    public void applyServerTarget(Vec3 position, Quaterniondc orientation,
                                  float wheelAngleDelta, int tick) {
        boolean changed = !hasTarget
                || targetTick != tick
                || targetPosition.distanceToSqr(position) > 1.0E-6
                || !sameOrientation(targetOrientation, orientation)
                || Math.abs(wheelAngleDelta - this.wheelAngleDelta) > 0.01f;
        this.hasTarget = true;
        this.targetPosition = position;
        this.targetOrientation.set(orientation);
        this.wheelAngleDelta = wheelAngleDelta;
        this.targetTick = tick;

        if (changed && getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void clearServerTarget() {
        if (!hasTarget) {
            return;
        }
        hasTarget = false;
        wheelAngleDelta = 0.0f;
        targetHistory.clear();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static boolean sameOrientation(Quaternionf current, Quaterniondc other) {
        return Math.abs(current.x - other.x()) < 1.0E-5
                && Math.abs(current.y - other.y()) < 1.0E-5
                && Math.abs(current.z - other.z()) < 1.0E-5
                && Math.abs(current.w - other.w()) < 1.0E-5;
    }

    public boolean hasRenderTarget() {
        return hasTarget;
    }

    public float getWheelAngleDelta() {
        return wheelAngleDelta;
    }

    public float getSuspensionOffset() {
        return suspensionOffset;
    }

    public void setSuspensionOffset(float offset) {
        this.suspensionOffset = Math.max(MIN_SUSPENSION_OFFSET, Math.min(MAX_SUSPENSION_OFFSET, offset));
        setChanged();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Samples the synced rail target at the given Sable interpolation tick.
     *
     * @return {@code null} while no target samples are available yet
     */
    public @Nullable RenderTarget sampleTargetAt(double renderTick) {
        if (targetHistory.isEmpty()) {
            return null;
        }

        TargetSample first = targetHistory.get(0);
        if (renderTick <= first.tick()) {
            return new RenderTarget(first.position(), new Quaternionf(first.orientation()));
        }

        TargetSample last = targetHistory.get(targetHistory.size() - 1);
        if (renderTick >= last.tick()) {
            if (targetHistory.size() < 2) {
                return new RenderTarget(last.position(), new Quaternionf(last.orientation()));
            }
            TargetSample previous = targetHistory.get(targetHistory.size() - 2);
            double span = last.tick() - previous.tick();
            double extra = span > 1.0E-6 ? Math.min(1.0, (renderTick - last.tick()) / span) : 0.0;
            Vec3 extrapolated = last.position().add(last.position().subtract(previous.position()).scale(extra));
            return new RenderTarget(extrapolated, new Quaternionf(last.orientation()));
        }

        for (int i = 1; i < targetHistory.size(); i++) {
            TargetSample next = targetHistory.get(i);
            if (renderTick > next.tick()) {
                continue;
            }
            TargetSample previous = targetHistory.get(i - 1);
            double span = next.tick() - previous.tick();
            double t = span > 1.0E-6 ? (renderTick - previous.tick()) / span : 1.0;
            Vec3 position = previous.position().lerp(next.position(), t);
            Quaternionf orientation = new Quaternionf(previous.orientation())
                    .slerp(next.orientation(), (float) t);
            return new RenderTarget(position, orientation);
        }

        return new RenderTarget(last.position(), new Quaternionf(last.orientation()));
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.putBoolean(HAS_TARGET_KEY, hasTarget);
        tag.putDouble(TARGET_X_KEY, targetPosition.x);
        tag.putDouble(TARGET_Y_KEY, targetPosition.y);
        tag.putDouble(TARGET_Z_KEY, targetPosition.z);
        tag.putFloat(TARGET_QX_KEY, targetOrientation.x);
        tag.putFloat(TARGET_QY_KEY, targetOrientation.y);
        tag.putFloat(TARGET_QZ_KEY, targetOrientation.z);
        tag.putFloat(TARGET_QW_KEY, targetOrientation.w);
        tag.putInt(TARGET_TICK_KEY, targetTick);
        tag.putFloat(WHEEL_ANGLE_DELTA_KEY, wheelAngleDelta);
        tag.putFloat(SUSPENSION_OFFSET_KEY, suspensionOffset);
        return tag;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (renderSizeId != null) {
            tag.putString(RENDER_SIZE_KEY, renderSizeId.toString());
        }
        tag.putFloat(SUSPENSION_OFFSET_KEY, suspensionOffset);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        boolean client = getLevel() != null && getLevel().isClientSide;

        super.loadAdditional(tag, registries);
        renderSizeId = tag.contains(RENDER_SIZE_KEY)
                ? ResourceLocation.tryParse(tag.getString(RENDER_SIZE_KEY))
                : null;

        hasTarget = tag.getBoolean(HAS_TARGET_KEY);
        targetPosition = new Vec3(
                tag.getDouble(TARGET_X_KEY),
                tag.getDouble(TARGET_Y_KEY),
                tag.getDouble(TARGET_Z_KEY));
        targetOrientation.set(
                tag.getFloat(TARGET_QX_KEY),
                tag.getFloat(TARGET_QY_KEY),
                tag.getFloat(TARGET_QZ_KEY),
                tag.getFloat(TARGET_QW_KEY));
        targetTick = tag.getInt(TARGET_TICK_KEY);
        wheelAngleDelta = tag.getFloat(WHEEL_ANGLE_DELTA_KEY);
        suspensionOffset = tag.getFloat(SUSPENSION_OFFSET_KEY);

        if (client) {
            if (hasTarget) {
                addTargetSample(targetTick, targetPosition, targetOrientation);
            } else {
                targetHistory.clear();
            }
        }
    }

    private void addTargetSample(int tick, Vec3 position, Quaternionf orientation) {
        if (!targetHistory.isEmpty()) {
            TargetSample last = targetHistory.get(targetHistory.size() - 1);
            if (tick == last.tick()) {
                targetHistory.set(targetHistory.size() - 1,
                        new TargetSample(tick, position, new Quaternionf(orientation)));
                return;
            }
            if (tick < last.tick()) {
                // Out-of-order packet: drop stale history instead of interpolating backwards.
                targetHistory.clear();
            }
        }

        targetHistory.add(new TargetSample(tick, position, new Quaternionf(orientation)));
        while (targetHistory.size() > MAX_TARGET_HISTORY) {
            targetHistory.remove(0);
        }
    }
}
