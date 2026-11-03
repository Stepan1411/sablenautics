package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.trains.entity.Carriage;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.stepan1411.sablenautics.trains.SableTrainController;

/**
 * Blocks Create from spawning carriage contraption entities for Sable carriages.
 * Normal carriages keep their regular Create entity.
 */
@Mixin(value = Carriage.DimensionalCarriageEntity.class, remap = false)
public abstract class DimensionalCarriageEntityMixin {

    @Shadow
    @Final
    Carriage this$0;

    @Inject(method = "createEntity", at = @At("HEAD"), cancellable = true)
    private void sablenautics$noEntity(Level level, boolean loadPassengers, CallbackInfo ci) {
        if (SableTrainController.isSableCarriage(this$0)) {
            ci.cancel();
        }
    }
}
