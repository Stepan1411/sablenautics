package ru.stepan1411.sablenautics.compact.mrcrayfishsfurnituremod;

import com.mrcrayfish.furniture.refurbished.electricity.IElectricityNode;
import com.mrcrayfish.furniture.refurbished.electricity.NodeHitResult;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public final class MrCrayfishsFurnitureModElectricityCompat {
    private static volatile PoseProvider poseProvider = (subLevel, partialTick) -> subLevel.logicalPose();

    private MrCrayfishsFurnitureModElectricityCompat() {
    }

    public static void setPoseProvider(PoseProvider provider) {
        poseProvider = provider;
    }

    public static NodeHitResult performNodeRaycast(Level level, Player player, double range, float partialTick) {
        Vec3 start = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        Vec3 end = start.add(look.x * range, look.y * range, look.z * range);
        NodeHitResult best = traverse(level, null, start, end, null);
        double bestDistance = best != null ? best.getLocation().distanceToSqr(start) : Double.MAX_VALUE;

        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(start, end))) {
            if (subLevel.isRemoved()) continue;

            Pose3dc pose = poseProvider.get(subLevel, partialTick);
            Vec3 localStart = pose.transformPositionInverse(start);
            Vec3 localEnd = pose.transformPositionInverse(end);
            NodeHitResult result = traverse(level, subLevel, localStart, localEnd, pose);
            if (result != null) {
                double distance = result.getLocation().distanceToSqr(start);
                if (distance < bestDistance) {
                    best = result;
                    bestDistance = distance;
                }
            }
        }

        return best != null ? best : new NodeHitResult(end, null, null);
    }

    public static Vec3 projectOutOfSubLevel(Level level, Vec3 pos) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        return subLevel == null ? pos : subLevel.logicalPose().transformPosition(pos);
    }

    @Nullable
    private static NodeHitResult traverse(Level level, @Nullable SubLevel subLevel, Vec3 from, Vec3 to,
                                          @Nullable Pose3dc pose) {
        return BlockGetter.traverseBlocks(from, to, null, (context, pos) -> {
            if (subLevel != null && Sable.HELPER.getContaining(level, pos) != subLevel) {
                return null;
            }

            if (level.getBlockEntity(pos) instanceof IElectricityNode node) {
                Optional<Vec3> hit = node.getPositionedNodeInteractBox().inflate(0.0625).clip(from, to);
                if (hit.isPresent()) {
                    Vec3 location = pose != null ? pose.transformPosition(hit.get()) : hit.get();
                    return new NodeHitResult(location, pos, node);
                }
            }

            return null;
        }, context -> null);
    }

    @FunctionalInterface
    public interface PoseProvider {
        Pose3dc get(SubLevel subLevel, float partialTick);
    }
}
