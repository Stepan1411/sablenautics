package ru.stepan1411.sablenautics.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import ru.stepan1411.sablenautics.client.ClientSableTrainControls;
import ru.stepan1411.sablenautics.trains.SableTrainControlManager;

import java.util.UUID;

public final class SablenauticsNetwork {

    private SablenauticsNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(TrainControlStartPayload.TYPE, TrainControlStartPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (FMLEnvironment.dist == Dist.CLIENT) {
                                ClientSableTrainControls.start(payload);
                            }
                        }))
                .playToServer(TrainControlInputPayload.TYPE, TrainControlInputPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                SableTrainControlManager.handleInput(player, payload.trainId(), payload.mask());
                            }
                        }))
                .playBidirectional(TrainControlStopPayload.TYPE, TrainControlStopPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                SableTrainControlManager.stopForPlayer(player, payload.trainId());
                            } else if (FMLEnvironment.dist == Dist.CLIENT) {
                                ClientSableTrainControls.stop();
                            }
                        }));
    }

    public static void sendStartControl(ServerPlayer player, UUID trainId, int carriageIndex, boolean inverted) {
        PacketDistributor.sendToPlayer(player,
                new TrainControlStartPayload(trainId, carriageIndex, inverted));
    }

    public static void sendStopControl(ServerPlayer player, UUID trainId) {
        PacketDistributor.sendToPlayer(player, new TrainControlStopPayload(trainId));
    }

    public static void sendInput(UUID trainId, int mask) {
        PacketDistributor.sendToServer(new TrainControlInputPayload(trainId, mask));
    }

    public static void requestStop(UUID trainId) {
        PacketDistributor.sendToServer(new TrainControlStopPayload(trainId));
    }
}
