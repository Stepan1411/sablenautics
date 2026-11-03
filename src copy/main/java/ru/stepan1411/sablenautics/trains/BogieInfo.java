package ru.stepan1411.sablenautics.trains;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Everything needed to render one bogey as a standalone entity and to restore its
 * original block when the train is disassembled.
 */
public record BogieInfo(
        Vector3d localPlotCenter,
        Vec3 initialWorldPosition,
        ResourceLocation styleId,
        ResourceLocation sizeId,
        CompoundTag bogeyData,
        Direction.Axis axis,
        boolean upsideDown,
        double wheelRadius,
        BlockState originalState
) {
}
