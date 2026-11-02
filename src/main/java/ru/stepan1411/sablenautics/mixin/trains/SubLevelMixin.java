package ru.stepan1411.sablenautics.mixin.trains;

import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.stepan1411.sablenautics.trains.SableTrainController;

@Mixin(value = SubLevel.class, remap = false)
public abstract class SubLevelMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void sablenautics$onTick(CallbackInfo ci) {
        SubLevel subLevel = (SubLevel) (Object) this;
        if (!subLevel.isRemoved()) {
            SableTrainController.tickSubLevel(subLevel, subLevel.getLevel());
        }
    }
}