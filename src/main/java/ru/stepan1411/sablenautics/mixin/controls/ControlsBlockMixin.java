package ru.stepan1411.sablenautics.mixin.controls;

import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create opens the train controls model only inside a {@code ContraptionWorld}.
 * Sable sub-level blocks live in a regular level, so {@code updateShape} would close them
 * again on every neighbour update. Keep them open while they belong to a sub-level.
 */
@Mixin(value = ControlsBlock.class, remap = false)
public abstract class ControlsBlockMixin {

    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void sablenautics$keepOpenInSubLevels(BlockState state, Direction direction, BlockState neighbour,
                                                 LevelAccessor level, BlockPos pos, BlockPos neighbourPos,
                                                 CallbackInfoReturnable<BlockState> cir) {
        BlockState result = cir.getReturnValue();
        if (result == null
                || !result.hasProperty(ControlsBlock.OPEN)
                || result.getValue(ControlsBlock.OPEN)
                || !(level instanceof Level realLevel)) {
            return;
        }

        if (Sable.HELPER.getContaining(realLevel, pos) != null) {
            cir.setReturnValue(result.setValue(ControlsBlock.OPEN, true));
        }
    }
}
