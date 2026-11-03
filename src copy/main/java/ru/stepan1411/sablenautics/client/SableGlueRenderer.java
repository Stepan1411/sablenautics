package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import ru.stepan1411.sablenautics.Sablenautics;

/**
 * Renders the glue strands that visually connect a bogey entity to its Sable carriage body.
 * The look matches the merging-glue strands used by simulatedcoasters' coaster carts.
 */
public final class SableGlueRenderer {

    private static final ResourceLocation STRAND_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Sablenautics.MOD_ID, "textures/block/glue/strand.png");

    private static final double[][] STRAND_POSITIONS = {
            {0.25D, 0.25D, 0.45D, 0.3D},
            {0.6D, 0.6D, 0.65D, 0.7D},
            {0.35D, 0.55D, 0.7D, 0.5D}
    };

    private SableGlueRenderer() {
    }

    /**
     * @param start local start point (pose-stack coordinates)
     * @param end   local end point (pose-stack coordinates)
     * @param rightHint a vector used to orient the strand cross-section
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffers, Vec3 start, Vec3 end,
                              Vec3 rightHint, int light) {
        Vec3 stretch = end.subtract(start);
        double lengthSq = stretch.lengthSqr();
        if (lengthSq < 1.0E-6) {
            return;
        }

        Vec3 stretchDir = stretch.scale(1.0 / Math.sqrt(lengthSq));
        Vec3 right = rightHint.subtract(stretchDir.scale(rightHint.dot(stretchDir)));
        if (right.lengthSqr() < 1.0E-8) {
            right = stretchDir.cross(new Vec3(0, 1, 0));
        }
        if (right.lengthSqr() < 1.0E-8) {
            right = stretchDir.cross(new Vec3(1, 0, 0));
        }
        right = right.normalize();
        Vec3 up = stretchDir.cross(right).normalize();

        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(STRAND_TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        for (double[] positions : STRAND_POSITIONS) {
            double ax = (positions[0] - 0.5D) * 0.75D;
            double ay = (positions[1] - 0.5D) * 0.75D;
            double bx = (positions[2] - 0.5D) * 0.75D;
            double by = (positions[3] - 0.5D) * 0.75D;

            Vec3 strandStart = start.add(right.scale(ax)).add(up.scale(ay));
            Vec3 strandEnd = end.add(right.scale(bx)).add(up.scale(by));
            renderCross(matrix, pose, buffer, strandStart, up, right, strandEnd, up, right, light);
        }
    }

    private static void renderCross(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer buffer,
                                    Vec3 start, Vec3 startUp, Vec3 startRight,
                                    Vec3 end, Vec3 endUp, Vec3 endRight, int light) {
        Vec3 startUpHalf = startUp.scale(0.5D);
        Vec3 endUpHalf = endUp.scale(0.5D);
        Vec3 startRightHalf = startRight.scale(0.5D);
        Vec3 endRightHalf = endRight.scale(0.5D);

        emitQuad(matrix, pose, buffer,
                start.subtract(startUpHalf), start.add(startUpHalf),
                end.add(endUpHalf), end.subtract(endUpHalf), light);

        emitQuad(matrix, pose, buffer,
                start.subtract(startRightHalf), start.add(startRightHalf),
                end.add(endRightHalf), end.subtract(endRightHalf), light);
    }

    private static void emitQuad(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer buffer,
                                 Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3, int light) {
        Vec3 e1 = v1.subtract(v0);
        Vec3 e2 = v3.subtract(v0);
        Vec3 normal = e1.cross(e2);
        double normalLength = normal.length();
        float nx = normalLength > 1.0E-8 ? (float) (normal.x / normalLength) : 0.0f;
        float ny = normalLength > 1.0E-8 ? (float) (normal.y / normalLength) : 1.0f;
        float nz = normalLength > 1.0E-8 ? (float) (normal.z / normalLength) : 0.0f;

        vert(matrix, pose, buffer, v0, 0.0f, 0.0f, light, nx, ny, nz);
        vert(matrix, pose, buffer, v1, 0.0f, 1.0f, light, nx, ny, nz);
        vert(matrix, pose, buffer, v2, 1.0f, 1.0f, light, nx, ny, nz);
        vert(matrix, pose, buffer, v3, 1.0f, 0.0f, light, nx, ny, nz);
    }

    private static void vert(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer buffer, Vec3 point,
                             float u, float v, int light, float nx, float ny, float nz) {
        buffer.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
