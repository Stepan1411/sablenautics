package ru.stepan1411.sablenautics.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.stepan1411.sablenautics.Sablenautics;

import java.util.UUID;

/**
 * Client -> server: pressed control keys as a bit mask.
 * 0 = forward, 1 = back, 2 = left, 3 = right, 4 = space, 5 = shift.
 */
public record TrainControlInputPayload(UUID trainId, int mask) implements CustomPacketPayload {
    public static final Type<TrainControlInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "control_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlInputPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TrainControlInputPayload::trainId,
                    ByteBufCodecs.VAR_INT, TrainControlInputPayload::mask,
                    TrainControlInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
