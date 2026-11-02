package ru.stepan1411.sablenautics.trains;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

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
        Vec3 origin = source.getPosition();
        double bestDistance = Double.MAX_VALUE;
        Train nearest = null;

        for (Train train : Create.RAILWAYS.trains.values()) {
            for (int i = 0; i < train.carriages.size(); i++) {
                ServerSubLevel subLevel = SableTrainController.subLevelFor(train, i);
                if (subLevel == null) continue;

                double distance = subLevel.logicalPose().position()
                        .distanceSquared(origin.x, origin.y, origin.z);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = train;
                }
            }
        }

        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "Sablenautics: no Sable train found"));
            return 0;
        }

        int moved = SableTrainController.teleportToRails(nearest);
        if (moved == 0) {
            source.sendFailure(Component.literal(
                    "Sablenautics: train has no loaded Sable carriages"));
            return 0;
        }

        Train train = nearest;
        source.sendSuccess(() -> Component.literal(
                "Sablenautics: teleported train \"" + train.name.getString() + "\" ("
                        + train.carriages.size() + " carriage(s)) to the rails"), true);
        return moved;
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
