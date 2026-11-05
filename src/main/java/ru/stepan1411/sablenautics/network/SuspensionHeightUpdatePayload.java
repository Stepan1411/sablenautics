package ru.stepan1411.sablenautics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.stepan1411.sablenautics.Sablenautics;

/**
 * Client -&gt; server: sets the suspension height of one Sable bogey.
 */
public record SuspensionHeightUpdatePayload(BlockPos pos, float height) implements CustomPacketPayload {
    public static final Type<SuspensionHeightUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "suspension_height_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SuspensionHeightUpdatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SuspensionHeightUpdatePayload::pos,
                    ByteBufCodecs.FLOAT, SuspensionHeightUpdatePayload::height,
                    SuspensionHeightUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
