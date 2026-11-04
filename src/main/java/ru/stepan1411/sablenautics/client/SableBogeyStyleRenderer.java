package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.BogeyRenderer;
import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;

/**
 * Fallback renderer for the Sable bogey style.
 * <p>
 * Sable carriages copy the original bogey style onto their bogey block, so this renderer
 * is only used for blocks placed by hand. It draws Create's standard small bogey wheels
 * instead of leaving the block invisible.
 */
public class SableBogeyStyleRenderer implements BogeyRenderer {

    private final StandardBogeyRenderer.Small fallback = new StandardBogeyRenderer.Small();

    @Override
    public void render(CompoundTag bogeyData, float wheelAngle, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay,
                       boolean inContraption) {
        fallback.render(bogeyData, wheelAngle, partialTick, poseStack, bufferSource,
                packedLight, packedOverlay, inContraption);
    }
}
