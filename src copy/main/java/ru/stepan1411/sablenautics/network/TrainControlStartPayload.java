package ru.stepan1411.sablenautics.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.stepan1411.sablenautics.Sablenautics;

import java.util.UUID;

/** Server -> client: a Sable train control session started. */
public record TrainControlStartPayload(UUID trainId, int carriageIndex, boolean inverted)
        implements CustomPacketPayload {
    public static final Type<TrainControlStartPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "control_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlStartPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TrainControlStartPayload::trainId,
                    ByteBufCodecs.VAR_INT, TrainControlStartPayload::carriageIndex,
                    ByteBufCodecs.BOOL, TrainControlStartPayload::inverted,
                    TrainControlStartPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
