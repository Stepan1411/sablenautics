package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.BlockEntityRenderer;
import net.minecraft.client.renderer.BlockEntityRendererProvider;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
import ru.stepan1411.sablenautics.trains.SableTrainController.BogieTarget;

public class SableBogeyBlockEntityRenderer extends BlockEntityRenderer<SableBogeyBlockEntity> {

    private static final ResourceLocation BOGEY_TEXTURE = ResourceLocation.fromNamespaceAndPath(
        Sablenautics.MOD_ID, "textures/block/bogeys/sable/top.png");

    public SableBogeyBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SableBogeyBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (entity.getTrainId() == null || entity.getLevel() == null) {
            return;
        }

        BogieTarget target = SableTrainController.bogieTarget(
            entity.getTrainId(), entity.getCarriageIndex(), entity.getBogieIndex());
        if (target == null) {
            return;
        }

        poseStack.pushPose();

        // Position the bogey block at its world position (relative to entity pose)
        BlockPos pos = entity.getBlockPos();
        poseStack.translate(pos.getX() - entity.getX(), pos.getY() - entity.getY(), pos.getZ() - entity.getZ());

        // Apply bogey orientation (aligned with track)
        poseStack.mulPose(target.orientation());

        // Rotate wheels based on accumulated speed
        float wheelAngle = entity.getWheelAngle(partialTick);
        poseStack.mulPose(new org.joml.Quaternionf().rotateDegrees(wheelAngle, 1, 0, 0));

        // Render bogey model as a simple box with texture
        float s = 0.42f;
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutout(BOGEY_TEXTURE));
        Matrix4f matrix = poseStack.last().pose();

        // Draw 6 faces of a cube centered at origin
        quad(matrix, consumer, -s, -s, -s,  s, -s, -s,  s, -s,  s,  s, -s, s, packedLight, packedOverlay); // bottom
        quad(matrix, consumer, -s,  s, -s, -s,  s, s, -s,  s,  s, s,  s,  s, packedLight, packedOverlay); // top
        quad(matrix, consumer, -s, -s, -s, -s,  s, -s,  s,  s, -s,  s,  s, -s, packedLight, packedOverlay); // north
        quad(matrix, consumer,  s, -s,  s,  s,  s,  s, -s,  s,  s, -s,  s,  s, packedLight, packedOverlay); // south
        quad(matrix, consumer, -s, -s, -s, -s, -s,  s, -s,  s,  s, -s,  s,  s, packedLight, packedOverlay); // west
        quad(matrix, consumer,  s, -s,  s,  s, -s, -s,  s,  s,  s,  s,  s,  s, packedLight, packedOverlay); // east

        poseStack.popPose();
    }

    private void quad(Matrix4f matrix, VertexConsumer consumer,
                      float x0, float y0, float z0,
                      float x1, float y1, float z1,
                      float x2, float y2, float z2,
                      float x3, float y3, float z3,
                      int light, int overlay) {
        consumer.addVertex(matrix, x0, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(matrix, 0, 0, -1);
        consumer.addVertex(matrix, x1, y1, z1).setColor(255, 255, 255, 255).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(matrix, 0, 0, -1);
        consumer.addVertex(matrix, x2, y2, z2).setColor(255, 255, 255, 255).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(matrix, 0, 0, -1);
        consumer.addVertex(matrix, x3, y3, z3).setColor(255, 255, 255, 255).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(matrix, 0, 0, -1);
    }
}
