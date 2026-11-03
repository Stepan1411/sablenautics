package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.stepan1411.sablenautics.content.entities.SableBogeyEntity;

public class SableBogeyRenderer extends EntityRenderer<SableBogeyEntity> {

    public SableBogeyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(SableBogeyEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    }

    @Override
    public void render(SableBogeyEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        BogeyStyle style = AllBogeyStyles.BOGEY_STYLES.get(entity.getStyleId());
        BogeySizes.BogeySize size = BogeySizes.all().get(entity.getSizeId());
        if (style == null || size == null) {
            return;
        }

        poseStack.pushPose();

        // The entity dispatcher already moved the pose stack to the interpolated entity
        // position. Apply only the delta to the rail target. The bogey is intentionally
        // not attached to the carriage body: the body may sway or tear away from the
        // rails while the wheel model stays on the track.
        Vec3 dispatchedPosition = new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));
        Vec3 renderPosition = entity.getInterpolatedRailPosition(partialTick);
        Quaternionf renderOrientation = entity.getInterpolatedRailOrientation(partialTick);

        Vec3 offset = renderPosition.subtract(dispatchedPosition);
        poseStack.translate(offset.x, offset.y, offset.z);

        // Glue strands between the visible bogey and its attachment point on the Sable body.
        // The body can sway relative to the rail target, so the strands stretch a little.
        Vec3 bodyAttachment = entity.getBodyAttachment();
        if (bodyAttachment != null) {
            Vector3f up = renderOrientation.transform(new Vector3f(0, 1, 0));
            Vector3f right = renderOrientation.transform(new Vector3f(1, 0, 0));

            // The visible bogey model is drawn about 1.5 blocks below the entity origin,
            // so keep the glue on the model rather than floating above it.
            Vec3 start = renderPosition.subtract(up.x * 0.9, up.y * 0.9, up.z * 0.9);
            Vec3 end = bodyAttachment.subtract(up.x * 0.05, up.y * 0.05, up.z * 0.05);
            Vec3 localStart = start.subtract(renderPosition);
            Vec3 localEnd = end.subtract(renderPosition);

            Vec3 midpoint = renderPosition.add(localEnd.scale(0.5));
            int glueLight = LevelRenderer.getLightColor((BlockAndTintGetter) entity.level(),
                    BlockPos.containing(midpoint));
            SableGlueRenderer.render(poseStack, buffer, localStart, localEnd,
                    new Vec3(right.x, right.y, right.z), glueLight);
        }

        poseStack.mulPose(renderOrientation);
        if (entity.getBogeyAxis() == Direction.Axis.X) {
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
        }

        // The invisible bogey block may occupy the same space; render unlit so the model
        // can never turn black when it is technically inside a block.
        style.render(size, partialTick, poseStack, buffer, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, entity.getWheelAngle(partialTick),
                entity.getBogeyRenderData(), false);
        poseStack.popPose();
    }
}
