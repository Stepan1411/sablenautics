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
 * Server -> client: live state shown by the Sable train controls HUD.
 * <p>
 * {@code yaw} is the carriage's world-space yaw (used by the direction arrow) and
 * {@code derailed} switches the HUD to the wrench hint instead of kicking the player.
 */
public record TrainControlStatusPayload(UUID trainId, boolean derailed, float yaw)
        implements CustomPacketPayload {
    public static final Type<TrainControlStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "control_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlStatusPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TrainControlStatusPayload::trainId,
                    ByteBufCodecs.BOOL, TrainControlStatusPayload::derailed,
                    ByteBufCodecs.FLOAT, TrainControlStatusPayload::yaw,
                    TrainControlStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
