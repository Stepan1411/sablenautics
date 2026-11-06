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
                                .executes(ctx -> purge(ctx.getSource(), true)))));
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

        Vec3 direction = lookDirection(source);
        BlockPos trackBlock = findTrack(source, level);

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
                if (frame != null) {
                    // Rail graph coordinates, not world coordinates.
                    point = frame.logicalPose().transformPositionInverse(point);
                }
                recovered = SableTrainController.relocateToNearestTrack(nearest, level,
                        point, direction, 12.0);
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
        // When the command runs from inside a Sable sub-level, the player's position is in that
        // sub-level's plot space. Compare carriage bodies in the same space so trains riding on
        // the platform can be found too.
        SubLevel playerFrame = Sable.HELPER.getContaining(level,
                new org.joml.Vector3d(origin.x, origin.y, origin.z));

        double bestDistance = Double.MAX_VALUE;
        Train nearest = null;

        for (Train train : Create.RAILWAYS.trains.values()) {
            for (int i = 0; i < train.carriages.size(); i++) {
                ServerSubLevel subLevel = SableTrainController.subLevelFor(train, i);
                if (subLevel == null || subLevel.getLevel() != level) continue;

                double distance = subLevel.logicalPose().position()
                        .distanceSquared(origin.x, origin.y, origin.z);
                if (playerFrame == subLevel) {
                    // The player is standing inside this very carriage body.
                    distance = 0.0;
                } else if (playerFrame != null) {
                    // Inverse-project the body into the plot the player is standing in.
                    Vec3 plot = playerFrame.logicalPose().transformPositionInverse(
                            new Vec3(subLevel.logicalPose().position().x(),
                                    subLevel.logicalPose().position().y(),
                                    subLevel.logicalPose().position().z()));
                    distance = plot.distanceToSqr(origin);
                }
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
        Vec3 direction = entity != null ? entity.getLookAngle()
                : Vec3.directionFromRotation(source.getRotation());
        if (entity != null) {
            SubLevel frame = Sable.HELPER.getContaining(entity);
            if (frame != null) {
                // Track graphs inside a sub-level are stored in its local coordinates.
                direction = frame.logicalPose().transformNormalInverse(direction);
            }
        }
        return direction;
    }

    /**
     * @return the track block the executor is looking at, or the closest track around them
     */
    private static @Nullable BlockPos findTrack(CommandSourceStack source, ServerLevel level) {
        Entity entity = source.getEntity();
        if (entity != null) {
            HitResult hit = entity.pick(32.0, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit
                    && level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof ITrackBlock) {
                return blockHit.getBlockPos();
            }
        }

        Vec3 origin = source.getPosition();
        BlockPos center = BlockPos.containing(origin);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -4, -8), center.offset(8, 4, 8))) {
            if (!(level.getBlockState(pos).getBlock() instanceof ITrackBlock)) continue;

            double distance = pos.distToCenterSqr(origin);
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            best = pos.immutable();
        }

        return best;
    }

    private static int purge(CommandSourceStack source, boolean all) {
        MinecraftServer server = source.getServer();
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
