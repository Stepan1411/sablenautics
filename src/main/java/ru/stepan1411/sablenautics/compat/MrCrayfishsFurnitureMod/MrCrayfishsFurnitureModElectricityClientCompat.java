package ru.stepan1411.sablenautics.compat.MrCrayfishsFurnitureMod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mrcrayfish.furniture.refurbished.client.DeferredElectricRenderer;
import com.mrcrayfish.furniture.refurbished.client.LinkHandler;
import com.mrcrayfish.furniture.refurbished.client.renderer.blockentity.ElectricBlockEntityRenderer;
import com.mrcrayfish.furniture.refurbished.electricity.Connection;
import com.mrcrayfish.furniture.refurbished.electricity.IElectricityNode;
import com.mrcrayfish.furniture.refurbished.electricity.LinkHitResult;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Intersectiond;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.Set;

public final class MrCrayfishsFurnitureModElectricityClientCompat {
    private static final int DEFAULT_COLOUR = -1;
    private static final int POWERED_COLOUR = -9652;
    private static final int CROSSING_ZONE_COLOUR = -3983818;

    private MrCrayfishsFurnitureModElectricityClientCompat() {
    }

    public static boolean drawNodeAndConnectionsInWorld(IElectricityNode node) {
        Level level = node.getNodeLevel();
        if (level == null) return false;
        if (!(Sable.HELPER.getContaining(level, node.getNodePosition()) instanceof ClientSubLevel clientSubLevel)) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !LinkHandler.isHoldingWrench()) {
            return true;
        }

        DeferredElectricRenderer renderer = DeferredElectricRenderer.get();
        LinkHandler handler = LinkHandler.get();
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Pose3dc pose = clientSubLevel.renderPose(partialTick);
        Vec3 nodeCenter = pose.transformPosition(node.getNodePosition().getCenter());
        drawNodeHighlight(node, level, handler, renderer, nodeCenter);
        drawConnections(node, level, handler, renderer, partialTick);
        drawNodeModel(node, renderer, nodeCenter);
        return true;
    }

    private static void drawNodeHighlight(IElectricityNode node, Level level, LinkHandler handler,
                                          DeferredElectricRenderer renderer, Vec3 nodeCenter) {
        boolean isLookingAt = handler.isTargetNode(node);
        if (isLookingAt && !handler.isLinking() && !node.isNodeConnectionLimitReached()
                || handler.isLinkingNode(node)
                || handler.canLinkToNode(level, node) && handler.isTargetNode(node)) {
            AABB box = node.getNodeInteractBox().move(-0.5, -0.5, -0.5);
            int color = handler.getLinkColour(level);
            renderer.deferDraw((poseStack, consumer) -> {
                poseStack.pushPose();
                poseStack.translate(nodeCenter.x, nodeCenter.y, nodeCenter.z);
                Matrix4f matrix = poseStack.last().pose();
                renderer.drawInvertedColouredBox(matrix, consumer, box.inflate(0.03125), color, 0.7F);
                poseStack.popPose();
            });
        }
    }

    private static void drawConnections(IElectricityNode node, Level level, LinkHandler handler,
                                        DeferredElectricRenderer renderer, float partialTick) {
        Set<Connection> drawnConnections = ElectricBlockEntityRenderer.getDrawnConnections();

        for (Connection connection : node.getNodeConnections()) {
            if (!drawnConnections.add(connection)) continue;

            Vec3 start = project(level, connection.getPosA().getCenter(), partialTick);
            Vec3 end = project(level, connection.getPosB().getCenter(), partialTick);
            Vec3 delta = end.subtract(start);
            double yaw = Math.atan2(-delta.z, delta.x) + Math.PI;
            double pitch = Math.atan2(delta.horizontalDistance(), delta.y) + (float) (Math.PI / 2);
            boolean selected = !handler.isLinking() && connection.equals(handler.getTargetConnection());
            int color = getConnectionColour(connection, level);
            float offset = (float) (Math.sin((double) Util.getMillis() / 500.0) + 1.0) / 2.0F * 0.2F;
            renderer.deferDraw((poseStack, consumer) -> {
                poseStack.pushPose();
                poseStack.translate(start.x, start.y, start.z);
                poseStack.mulPose(Axis.YP.rotation((float) yaw));
                poseStack.mulPose(Axis.ZP.rotation((float) pitch));
                Matrix4f matrix = poseStack.last().pose();
                AABB box = new AABB(0.0, -0.03125, -0.03125, delta.length(), 0.03125, 0.03125);
                renderer.drawColouredBox(matrix, consumer, box, color, 0.7F + offset);
                renderer.drawColouredBox(matrix, consumer, box.inflate(0.03125), color, 0.5F + offset);
                if (selected) {
                    renderer.drawColouredBox(matrix, consumer, box.inflate(0.03125), DEFAULT_COLOUR, 0.8F);
                }
                poseStack.popPose();
            });
        }
    }

    private static void drawNodeModel(IElectricityNode node, DeferredElectricRenderer renderer, Vec3 nodeCenter) {
        AABB box = node.getNodeInteractBox().move(-0.5, -0.5, -0.5);
        renderer.deferDraw((poseStack, consumer) -> {
            poseStack.pushPose();
            poseStack.translate(nodeCenter.x, nodeCenter.y, nodeCenter.z);
            Matrix4f matrix = poseStack.last().pose();
            renderer.drawTexturedBox(matrix, consumer, box, 0.0F, 0.0F, 0.25F, 0.25F);
            poseStack.popPose();
        });
    }

    private static int getConnectionColour(Connection connection, Level level) {
        if (connection.isCrossingPowerableZone(level)) {
            return CROSSING_ZONE_COLOUR;
        }
        return connection.isPowered(level) ? POWERED_COLOUR : DEFAULT_COLOUR;
    }

    public static void applyPose(PoseStack poseStack, Pose3dc pose) {
        Matrix4d matrix = pose.bakeIntoMatrix(new Matrix4d());
        poseStack.mulPose(new Matrix4f(matrix));
    }

    public static HitResult performLinkRaycast(Player player, float partialTick, double range) {
        Level level = player.level();
        Vec3 rayStart = player.getEyePosition(partialTick);
        Vec3 rayEnd = rayStart.add(player.getViewVector(partialTick).normalize().scale(range));
        double closestDistance = Double.POSITIVE_INFINITY;
        Connection closestConnection = null;
        Vec3 closestHit = Vec3.ZERO;

        for (Connection connection : ElectricBlockEntityRenderer.getDrawnConnections()) {
            Vec3 linkStart = project(level, connection.getPosA().getCenter(), partialTick);
            Vec3 linkEnd = project(level, connection.getPosB().getCenter(), partialTick);
            Vector3d result = new Vector3d();
            double squareDistance = Intersectiond.findClosestPointsLineSegments(
                    rayStart.x, rayStart.y, rayStart.z,
                    rayEnd.x, rayEnd.y, rayEnd.z,
                    linkStart.x, linkStart.y, linkStart.z,
                    linkEnd.x, linkEnd.y, linkEnd.z,
                    new Vector3d(), result);
            double distance = Math.sqrt(squareDistance);
            if (distance < 0.1 && distance < closestDistance) {
                closestDistance = distance;
                closestConnection = connection;
                closestHit = new Vec3(result.x, result.y, result.z);
            }
        }

        return new LinkHitResult(closestHit, closestConnection);
    }

    public static double renderUnfinishedLink(Player player, BlockPos pos, float partialTick) {
        Level level = player.level();
        LinkHandler handler = LinkHandler.get();
        Vec3 worldStart = project(level, Vec3.atCenterOf(pos), partialTick);
        IElectricityNode target = handler.getTargetNode();
        Vec3 worldEnd;
        if (target != null && !handler.isLinkingNode(target) && handler.canLinkToNode(level, target)) {
            worldEnd = project(level, target.getNodePosition().getCenter(), partialTick);
        } else {
            worldEnd = player.getViewVector(partialTick).normalize().scale(1.5)
                    .add(player.getEyePosition(partialTick));
        }

        Vec3 delta = worldEnd.subtract(worldStart);
        double length = worldStart.distanceTo(worldEnd);
        DeferredElectricRenderer renderer = DeferredElectricRenderer.get();
        renderer.deferDraw((poseStack, consumer) -> {
            poseStack.pushPose();
            poseStack.translate(worldStart.x, worldStart.y, worldStart.z);
            double yaw = Math.atan2(-delta.z, delta.x) + Math.PI;
            double pitch = Math.atan2(delta.horizontalDistance(), delta.y) + (float) (Math.PI / 2);
            poseStack.mulPose(Axis.YP.rotation((float) yaw));
            poseStack.mulPose(Axis.ZP.rotation((float) pitch));
            Matrix4f matrix = poseStack.last().pose();
            int color = handler.getLinkColour(level);
            AABB box = new AABB(0.0, -0.03125, -0.03125, delta.length(), 0.03125, 0.03125);
            renderer.drawColouredBox(matrix, consumer, box, color, 0.8F);
            renderer.drawColouredBox(matrix, consumer, box.inflate(0.03125), color, 0.6F);
            poseStack.popPose();
        });
        return length;
    }

    @Nullable
    public static ClientSubLevel uniformSubLevel(Set<BlockPos> positions) {
        Level level = Minecraft.getInstance().level;
        if (level == null || positions.isEmpty()) return null;

        ClientSubLevel found = null;
        for (BlockPos pos : positions) {
            if (!(Sable.HELPER.getContaining(level, pos) instanceof ClientSubLevel clientSubLevel)) {
                return null;
            }
            if (found == null) {
                found = clientSubLevel;
            } else if (found != clientSubLevel) {
                return null;
            }
        }
        return found;
    }

    private static Vec3 project(Level level, Vec3 pos, float partialTick) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (subLevel == null) return pos;

        Pose3dc pose = subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel.renderPose(partialTick)
                : subLevel.logicalPose();
        return pose.transformPosition(pos);
    }
}
