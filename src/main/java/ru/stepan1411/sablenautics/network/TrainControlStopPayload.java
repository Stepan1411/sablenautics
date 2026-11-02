package ru.stepan1411.sablenautics.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.stepan1411.sablenautics.Sablenautics;

import java.util.UUID;

/** Client -> server (stop request) and server -> client (forced stop). */
public record TrainControlStopPayload(UUID trainId) implements CustomPacketPayload {
    public static final Type<TrainControlStopPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "control_stop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlStopPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TrainControlStopPayload::trainId,
                    TrainControlStopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
