package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.trains.entity.Carriage;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks Create from spawning carriage contraption entities from serialised data.
 * Sable sub-levels are the carriage; no entity is needed or wanted.
 */
@Mixin(value = Carriage.DimensionalCarriageEntity.class, remap = false)
public abstract class DimensionalCarriageEntityMixin {

    @Inject(method = "createEntity", at = @At("HEAD"), cancellable = true)
    private void sablenautics$noEntity(Level level, boolean loadPassengers, CallbackInfo ci) {
        ci.cancel();
    }
}
