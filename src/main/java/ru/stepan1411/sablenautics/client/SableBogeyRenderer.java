package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
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

        // Render at the smoothed position instead of the raw packet position. The entity
        // dispatcher already moved the pose stack to the interpolated position, so apply
        // only the delta.
        Vec3 renderPosition = new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));
        Vec3 offset = entity.getSmoothedPosition().subtract(renderPosition);
        poseStack.translate(offset.x, offset.y, offset.z);

        poseStack.mulPose(entity.getSmoothedOrientation());
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
