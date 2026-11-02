package ru.stepan1411.sablenautics.trains;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
                .then(Commands.literal("purge_trains")
                        .executes(ctx -> purge(ctx.getSource(), false))
                        .then(Commands.literal("derailed")
                                .executes(ctx -> purge(ctx.getSource(), false)))
                        .then(Commands.literal("all")
                                .executes(ctx -> purge(ctx.getSource(), true)))));
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
