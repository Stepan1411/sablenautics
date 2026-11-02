package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Bogeys are moved into their own rail-following sub-level. Their real shape would
 * constantly collide with the carriage body above them, so Sable gets a tiny collider
 * instead. The vanilla shape is still used for mass calculations.
 */
@Mixin(value = AbstractBogeyBlock.class, remap = false)
public abstract class AbstractBogeyBlockMixin implements BlockSubLevelCollisionShape {

    @Override
    public VoxelShape getSubLevelCollisionShape(BlockGetter level, BlockState state) {
        // Keep a tiny non-empty shape so Sable still counts the bogey block as mass.
        return Shapes.box(0.49, 0.49, 0.49, 0.51, 0.51, 0.51);
    }
}
