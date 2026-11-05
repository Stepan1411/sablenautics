package ru.stepan1411.sablenautics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import ru.stepan1411.sablenautics.client.ClientSableTrainControls;
import ru.stepan1411.sablenautics.client.ClientSuspensionHeightScreen;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
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
                .playToClient(TrainControlStatusPayload.TYPE, TrainControlStatusPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (FMLEnvironment.dist == Dist.CLIENT) {
                                ClientSableTrainControls.applyStatus(payload);
                            }
                        }))
                .playToServer(TrainControlInputPayload.TYPE, TrainControlInputPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                SableTrainControlManager.handleInput(player, payload.trainId(), payload.mask());
                            }
                        }))
                .playToClient(SuspensionHeightOpenPayload.TYPE, SuspensionHeightOpenPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (FMLEnvironment.dist == Dist.CLIENT) {
                                ClientSuspensionHeightScreen.open(payload.pos(), payload.height());
                            }
                        }))
                .playToServer(SuspensionHeightUpdatePayload.TYPE, SuspensionHeightUpdatePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player
                                    && player.serverLevel().getBlockEntity(payload.pos())
                                    instanceof SableBogeyBlockEntity bogey) {
                                bogey.setSuspensionOffset(payload.height());
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

    public static void sendControlStatus(ServerPlayer player, UUID trainId, boolean derailed, float yaw) {
        PacketDistributor.sendToPlayer(player,
                new TrainControlStatusPayload(trainId, derailed, yaw));
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

    public static void sendOpenSuspensionHeight(ServerPlayer player, BlockPos pos, float height) {
        PacketDistributor.sendToPlayer(player, new SuspensionHeightOpenPayload(pos, height));
    }

    public static void sendSuspensionHeightUpdate(BlockPos pos, float height) {
        PacketDistributor.sendToServer(new SuspensionHeightUpdatePayload(pos, height));
    }
}
