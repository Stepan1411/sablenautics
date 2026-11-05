package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Renders the train's original bogey model on the Sable bogey block.
 * <p>
 * The server sends rail targets tagged with Sable's interpolation tick. The client samples
 * that history on the exact same interpolated tick that Sable uses for the carriage body,
 * so the wheels stay on the rails without lag, leading or packet-boundary jitter. After the
 * carriage fully detaches, the target eases back to the body and the model ends up under the
 * Sable bogey.
 * <p>
 * Wheel spin is synced as degrees per tick and integrated on the client, so it is smooth and
 * never aliases at high speed.
 */
public class SableBogeyBlockEntityRenderer implements BlockEntityRenderer<SableBogeyBlockEntity> {

    /** Targets farther than this from the block are considered stale and are ignored. */
    private static final double MAX_TARGET_DISTANCE_SQR = 64.0 * 64.0;

    private static final Map<SableBogeyBlockEntity, WheelSpin> WHEEL_SPINS = new WeakHashMap<>();

    private static final class WheelSpin {
        private boolean initialized;
        private float rate;
        private float angle;
    }

    public SableBogeyBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SableBogeyBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState state = blockEntity.getBlockState();
        if (!(state.getBlock() instanceof AbstractBogeyBlock<?>)) {
            return;
        }

        BogeyStyle style = blockEntity.getStyle();
        if (style.id.equals(SablenauticsBogeyStyles.SABLE.id)) {
            // Never leave the block invisible if it was placed by hand or has no original style.
            style = AllBogeyStyles.STANDARD;
        }
        BogeySizes.BogeySize size = blockEntity.getRenderSize();

        WheelSpin spin = WHEEL_SPINS.computeIfAbsent(blockEntity, key -> new WheelSpin());
        updateWheelSpin(blockEntity, spin);

        poseStack.pushPose();

        GlueFrame glueFrame = null;
        if (blockEntity.hasRenderTarget()) {
            double renderTick = interpolatedRenderTick(blockEntity);
            SableBogeyBlockEntity.RenderTarget target = blockEntity.sampleTargetAt(renderTick);
            if (target != null) {
                glueFrame = computeTargetFrame(blockEntity, target);
            }
        }

        if (glueFrame != null) {
            renderGlue(poseStack, bufferSource, glueFrame, blockEntity.getSuspensionOffset());
            poseStack.translate(glueFrame.localDelta().x, glueFrame.localDelta().y, glueFrame.localDelta().z);
            poseStack.mulPose(new Quaternionf(glueFrame.localOrientation()));
        }

        poseStack.translate(0.5f, 0.5f, 0.5f);
        if (state.getValue(AbstractBogeyBlock.AXIS) == Direction.Axis.X) {
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
        }
        float suspensionOffset = blockEntity.getSuspensionOffset();
        if (Math.abs(suspensionOffset) > 1.0e-4f) {
            poseStack.translate(0.0f, suspensionOffset, 0.0f);
        }
        style.render(size, partialTick, poseStack, bufferSource, packedLight, packedOverlay,
                spin.angle, blockEntity.getBogeyData(), false);

        poseStack.popPose();
    }

    /**
     * @return the Sable interpolation tick the carriage body is currently rendered at
     */
    private static double interpolatedRenderTick(SableBogeyBlockEntity blockEntity) {
        if (blockEntity.getLevel() instanceof ClientLevel clientLevel) {
            ClientSubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
            if (container != null) {
                ClientSableInterpolationState interpolation = container.getInterpolation();
                double current = interpolation.mostRecentInterpolationTick;
                double previous = interpolation.lastInterpolationTick;
                if (current > 0.0) {
                    if (previous > 0.0) {
                        float partialTick = Minecraft.getInstance().getTimer()
                                .getGameTimeDeltaPartialTick(true);
                        return Mth.lerp(partialTick, previous, current);
                    }
                    return current;
                }
                return interpolation.getTickPointer();
            }
        }
        return blockEntity.getLevel() == null
                ? 0.0
                : blockEntity.getLevel().getGameTime();
    }

    /**
     * Converts the sampled world rail target into a local offset and orientation for this
     * block, using the exact render pose Sable applies to the sub-level.
     */
    private static @Nullable GlueFrame computeTargetFrame(SableBogeyBlockEntity blockEntity,
                                                          SableBogeyBlockEntity.RenderTarget target) {
        ClientSubLevelAccess subLevel = Sable.HELPER.getContainingClient(blockEntity);
        if (subLevel == null) {
            return null;
        }

        Pose3dc pose = subLevel.renderPose();
        Vector3d blockCenter = new Vector3d(
                blockEntity.getBlockPos().getX() + 0.5,
                blockEntity.getBlockPos().getY() + 0.5,
                blockEntity.getBlockPos().getZ() + 0.5);
        Vector3d blockWorld = pose.transformPosition(blockCenter, new Vector3d());

        Vec3 position = target.position();
        if (blockWorld.distanceSquared(position.x, position.y, position.z) > MAX_TARGET_DISTANCE_SQR) {
            return null;
        }

        Vector3d worldDelta = new Vector3d(position.x, position.y, position.z).sub(blockWorld);
        Quaterniond subLevelInverse = new Quaterniond(pose.orientation()).invert();
        Vector3d localDelta = subLevelInverse.transform(worldDelta, new Vector3d());
        Quaterniond localOrientation = new Quaterniond(subLevelInverse)
                .mul(new Quaterniond(target.orientation()));

        Vector3d midpointLocal = new Vector3d(blockCenter).add(new Vector3d(localDelta).mul(0.5));
        Vector3d midpointWorld = pose.transformPosition(midpointLocal, new Vector3d());
        int light = LevelRenderer.getLightColor(blockEntity.getLevel(),
                BlockPos.containing(midpointWorld.x, midpointWorld.y, midpointWorld.z));

        return new GlueFrame(localDelta, localOrientation, light);
    }

    /**
     * Draws the stretching glue strands between the wheel model's rail target and the Sable
     * bogey block (the body side). Coordinates are relative to the block origin, matching
     * the pose stack at this point.
     */
    private static void renderGlue(PoseStack poseStack, MultiBufferSource buffers,
                                   GlueFrame frame, float suspensionOffset) {
        Vector3d blockCenter = new Vector3d(0.5, 0.5, 0.5);
        Vector3d targetLocal = new Vector3d(blockCenter).add(frame.localDelta());
        Vector3d up = frame.localOrientation().transform(new Vector3d(0.0, 1.0, 0.0));
        Vector3d right = frame.localOrientation().transform(new Vector3d(1.0, 0.0, 0.0));

        // Keep the visible end on the wheel model (slightly below the rail target) and the
        // other end on the Sable bogey block, so the glue visibly bridges the two. The wheel
        // end follows the suspension height offset of this particular bogey.
        Vec3 start = new Vec3(
                targetLocal.x - up.x * 0.9 + up.x * suspensionOffset,
                targetLocal.y - up.y * 0.9 + up.y * suspensionOffset,
                targetLocal.z - up.z * 0.9 + up.z * suspensionOffset);
        Vec3 end = new Vec3(
                blockCenter.x - up.x * 0.05,
                blockCenter.y - up.y * 0.05,
                blockCenter.z - up.z * 0.05);

        SableGlueRenderer.render(poseStack, buffers, start, end,
                new Vec3(right.x, right.y, right.z), frame.light());
    }

    private record GlueFrame(Vector3d localDelta, Quaterniond localOrientation, int light) {
    }

    private static void updateWheelSpin(SableBogeyBlockEntity blockEntity, WheelSpin spin) {
        float deltaTicks = Math.min(2.0f,
                Minecraft.getInstance().getTimer().getGameTimeDeltaTicks());
        if (deltaTicks <= 0.0f) {
            return;
        }

        // Match the wheel model's rolling direction to the train's direction of travel.
        float targetRate = blockEntity.getWheelAngleDelta();
        if (!spin.initialized) {
            spin.rate = targetRate;
            spin.initialized = true;
        } else {
            // Smooth the angular velocity a little so tick-to-tick speed noise does not
            // show up as stutter in the wheel spin.
            float alpha = 1.0f - (float) Math.exp(-deltaTicks / 0.5f);
            spin.rate += (targetRate - spin.rate) * alpha;
        }

        spin.angle = (spin.angle + spin.rate * deltaTicks) % 360.0f;
    }
}
