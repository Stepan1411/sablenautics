package ru.stepan1411.sablenautics.trains;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.track.ITrackBlock;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Utility commands for clearing broken test trains and their Sable sub-levels.
 */
public final class SableTrainCommands {

    private SableTrainCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sablenautics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("gotorail")
                        .executes(ctx -> gotorail(ctx.getSource())))
                .then(Commands.literal("purge_trains")
                        .executes(ctx -> purge(ctx.getSource(), false))
                        .then(Commands.literal("derailed")
                                .executes(ctx -> purge(ctx.getSource(), false)))
                        .then(Commands.literal("all")
                                .executes(ctx -> purge(ctx.getSource(), true))))
                .then(Commands.literal("traininfo")
                        .executes(ctx -> trainInfo(ctx.getSource()))));
    }

    private static int gotorail(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (Train train : Create.RAILWAYS.trains.values()) {
            SableTrainController.ensureMappings(train);
        }
        Train nearest = nearestTrain(source.getPosition(), level);
        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "Sablenautics: no Sable train found"));
            return 0;
        }

        Vec3 look = lookDirection(source);
        Vec3 direction = look;
        TrackSearch search = findTrack(source, level);
        BlockPos trackBlock = search != null ? search.pos() : null;
        if (search != null) {
            direction = search.direction();
        }

        if (trackBlock != null) {
            if (!SableTrainController.relocateToTrackBlock(nearest, level, trackBlock, direction)) {
                source.sendFailure(Component.literal(
                        "Sablenautics: could not put the train on that track (not enough room or blocked)"));
                return 0;
            }

            Train train = nearest;
            source.sendSuccess(() -> Component.literal(
                    "Sablenautics: put train \"" + train.name.getString() + "\" on the track at "
                            + trackBlock.getX() + " " + trackBlock.getY() + " " + trackBlock.getZ()), true);
            return train.carriages.size();
        }

        int moved = SableTrainController.teleportToRails(nearest);
        if (moved == 0) {
            // The train may be off the rails or its rail target may no longer be valid.
            // Look for any track around the train and relocate the whole train onto it.
            ServerSubLevel body = SableTrainController.subLevelFor(nearest, 0);
            boolean recovered = false;
            if (body != null) {
                var pos = body.logicalPose().position();
                Vec3 point = new Vec3(pos.x(), pos.y(), pos.z());
                ServerSubLevel frame = SableTrainController.railFrameFor(body);
                Vec3 fallbackDirection = direction;
                if (frame != null) {
                    // Rail graph coordinates, not world coordinates.
                    point = frame.logicalPose().transformPositionInverse(point);
                    fallbackDirection = frame.logicalPose().transformNormalInverse(look);
                }
                recovered = SableTrainController.relocateToNearestTrack(nearest, level,
                        point, fallbackDirection, 12.0);
            }

            if (!recovered) {
                source.sendFailure(Component.literal(
                        "Sablenautics: no track nearby and the train has no loaded Sable carriages"));
                return 0;
            }

            Train train = nearest;
            source.sendSuccess(() -> Component.literal(
                    "Sablenautics: moved train \"" + train.name.getString() + "\" ("
                            + train.carriages.size() + " carriage(s)) onto the nearest track"), true);
            return train.carriages.size();
        }

        Train train = nearest;
        source.sendSuccess(() -> Component.literal(
                "Sablenautics: teleported train \"" + train.name.getString() + "\" ("
                        + train.carriages.size() + " carriage(s)) to the rails"), true);
        return moved;
    }

    /**
     * @return the Sable train with a carriage closest to {@code origin} in this dimension
     */
    private static @Nullable Train nearestTrain(Vec3 origin, ServerLevel level) {
        double bestDistance = Double.MAX_VALUE;
        Train nearest = null;

        for (Train train : Create.RAILWAYS.trains.values()) {
            for (int i = 0; i < train.carriages.size(); i++) {
                ServerSubLevel subLevel = SableTrainController.subLevelFor(train, i);
                if (subLevel == null || subLevel.getLevel() != level) continue;

                double distance = subLevel.logicalPose().position()
                        .distanceSquared(origin.x, origin.y, origin.z);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = train;
                }
            }
        }

        return nearest;
    }

    private static Vec3 lookDirection(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity != null) return entity.getLookAngle();
        return Vec3.directionFromRotation(source.getRotation());
    }

    private record TrackSearch(BlockPos pos, Vec3 direction) {
    }

    /**
     * Finds the rail the executor is looking at, or the closest rail around them. Rails can be
     * part of a Sable sub-level (a platform); in that case the search runs in the plot space of
     * that sub-level and the look direction is converted the same way.
     */
    private static @Nullable TrackSearch findTrack(CommandSourceStack source, ServerLevel level) {
        Entity entity = source.getEntity();
        Vec3 look = lookDirection(source);

        if (entity != null) {
            HitResult hit = entity.pick(32.0, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && isTrack(level, blockHit.getBlockPos())) {
                ServerSubLevel frame = subLevelAt(level, blockHit.getBlockPos());
                Vec3 direction = frame != null
                        ? frame.logicalPose().transformNormalInverse(look)
                        : look;
                return new TrackSearch(blockHit.getBlockPos(), direction);
            }
        }

        // Standing on (or riding inside) a Sable sub-level: search its plot for rails.
        ServerSubLevel frame = playerRailFrame(entity, level);
        if (frame != null) {
            Vec3 local = frame.logicalPose().transformPositionInverse(source.getPosition());
            BlockPos best = nearestTrackBlock(level, local, 8);
            if (best != null) {
                return new TrackSearch(best, frame.logicalPose().transformNormalInverse(look));
            }
        }

        BlockPos best = nearestTrackBlock(level, source.getPosition(), 8);
        return best != null ? new TrackSearch(best, look) : null;
    }

    private static boolean isTrack(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof ITrackBlock;
    }

    private static @Nullable ServerSubLevel subLevelAt(ServerLevel level, BlockPos pos) {
        var subLevel = Sable.HELPER.getContaining(level,
                new org.joml.Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        return subLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved()
                ? serverSubLevel : null;
    }

    /**
     * @return the sub-level whose plot holds the rails around the player, or {@code null}
     */
    private static @Nullable ServerSubLevel playerRailFrame(Entity entity, ServerLevel level) {
        if (entity == null) return null;
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(entity);
        if (tracking == null) return null;

        Vec3 origin = entity.position();
        if (tracking instanceof ServerSubLevel serverTracking && !serverTracking.isRemoved()) {
            Vec3 local = serverTracking.logicalPose().transformPositionInverse(origin);
            if (nearestTrackBlock(level, local, 8) != null) {
                return serverTracking;
            }
        }

        // The player may be riding a carriage body; the rails are on the platform below it.
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return null;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel frame) || frame.isRemoved()) continue;
            var bounds = frame.getPlot().getBoundingBox();
            if (bounds == null) continue;
            Vec3 local = frame.logicalPose().transformPositionInverse(origin);
            if (local.x < bounds.minX() - 1.0 || local.x > bounds.maxX() + 2.0
                    || local.y < bounds.minY() - 1.0 || local.y > bounds.maxY() + 2.0
                    || local.z < bounds.minZ() - 1.0 || local.z > bounds.maxZ() + 2.0) {
                continue;
            }
            if (nearestTrackBlock(level, local, 8) != null) {
                return frame;
            }
        }
        return null;
    }

    private static @Nullable BlockPos nearestTrackBlock(ServerLevel level, Vec3 point, int radius) {
        BlockPos center = BlockPos.containing(point);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius),
                center.offset(radius, 4, radius))) {
            if (!isTrack(level, pos)) continue;

            double distance = pos.distToCenterSqr(point);
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            best = pos.immutable();
        }

        return best;
    }

    private static int trainInfo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (Train train : Create.RAILWAYS.trains.values()) {
            SableTrainController.ensureMappings(train);
        }
        Train nearest = nearestTrain(source.getPosition(), level);
        if (nearest == null) {
            source.sendFailure(Component.literal("Sablenautics: no Sable train found"));
            return 0;
        }

        for (String line : SableTrainController.describeTrain(nearest, level)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int purge(CommandSourceStack source, boolean all) {        MinecraftServer server = source.getServer();
        List<Train> targets = new ArrayList<>();
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (all || train.derailed) {
                targets.add(train);
            }
        }

        int subLevels = 0;
        for (Train train : targets) {
            UUID trainId = train.id;
            for (ServerLevel level : server.getAllLevels()) {
                subLevels += SableTrainController.purgeSubLevels(level, trainId);
            }
            SableTrainControlManager.stopForTrain(server, trainId);
            Create.RAILWAYS.removeTrain(trainId);
            CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
        }

        int count = targets.size();
        int totalSubLevels = subLevels;
        source.sendSuccess(() -> Component.literal("Sablenautics: removed " + count
                + " train(s) and " + totalSubLevels + " sub-level(s)"), true);
        return count;
    }
}
