package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
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
        // position. Render from the carriage sub-level's interpolated pose instead, then
        // apply only the delta. This keeps the bogey rigidly attached to the carriage
        // body: the same interpolation drives both, so the model can neither lead nor lag.
        Vec3 interpolatedPosition = new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));

        Vec3 renderPosition = interpolatedPosition;
        Quaternionf renderOrientation = entity.getSyncedOrientation();

        ClientSubLevel body = getBodySubLevel(entity);
        if (body != null) {
            Pose3dc bodyPose = body.renderPose(partialTick);
            Vector3d localCenter = entity.getLocalPlotCenter();
            Vector3d worldCenter = bodyPose.transformPosition(localCenter, new Vector3d());
            renderPosition = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);
            renderOrientation = new Quaternionf(bodyPose.orientation());
        }

        Vec3 offset = renderPosition.subtract(interpolatedPosition);
        poseStack.translate(offset.x, offset.y, offset.z);

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

    private static @Nullable ClientSubLevel getBodySubLevel(SableBogeyEntity entity) {
        var bodyId = entity.getBodySubLevelId();
        if (bodyId == null) return null;

        SubLevelContainer container = SubLevelContainer.getContainer(entity.level());
        if (container == null) return null;

        SubLevel subLevel = container.getSubLevel(bodyId);
        if (subLevel instanceof ClientSubLevel clientSubLevel && !clientSubLevel.isRemoved()) {
            return clientSubLevel;
        }
        return null;
    }
}
