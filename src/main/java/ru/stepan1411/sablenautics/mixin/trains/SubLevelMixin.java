package ru.stepan1411.sablenautics.mixin.trains;

import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Formerly invoked {@link SableTrainController#tickSubLevel} from {@code SubLevel#tick()}.
 * Train ticking is now handled exclusively by {@link ru.stepan1411.sablenautics.trains.SableTrainTicker}
 * which runs on every server tick regardless of chunk loading. This mixin is kept as a no-op
 * to avoid removing the mixin registration entirely.
 */
@Mixin(value = SubLevel.class, remap = false)
public abstract class SubLevelMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void sablenautics$onTick(CallbackInfo ci) {
        // Intentionally empty: ticking is done by SableTrainTicker instead.
    }
}
