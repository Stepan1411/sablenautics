package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.BogeyVisual;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * No-op visual for the Sable bogey style.
 */
public class SableBogeyStyleVisual implements BogeyVisual {

    public SableBogeyStyleVisual(VisualizationContext ctx, float partialTick, boolean inContraption) {
    }

    @Override
    public void update(CompoundTag bogeyData, float wheelAngle, PoseStack poseStack) {
    }

    @Override
    public void hide() {
    }

    @Override
    public void updateLight(int packedLight) {
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
    }

    @Override
    public void delete() {
    }
}

