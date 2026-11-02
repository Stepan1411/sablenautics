package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.BogeyRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;

/**
 * No-op renderer for the Sable bogey style.
 */
public class SableBogeyStyleRenderer implements BogeyRenderer {
    @Override
    public void render(CompoundTag bogeyData, float wheelAngle, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay,
                       boolean inContraption) {
    }
}

