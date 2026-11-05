package ru.stepan1411.sablenautics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.stepan1411.sablenautics.Sablenautics;

/**
 * Server -&gt; client: opens the suspension height screen for a Sable bogey.
 */
public record SuspensionHeightOpenPayload(BlockPos pos, float height) implements CustomPacketPayload {
    public static final Type<SuspensionHeightOpenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "suspension_height_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SuspensionHeightOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SuspensionHeightOpenPayload::pos,
                    ByteBufCodecs.FLOAT, SuspensionHeightOpenPayload::height,
                    SuspensionHeightOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
