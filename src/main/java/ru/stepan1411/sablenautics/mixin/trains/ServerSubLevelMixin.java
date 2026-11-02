package ru.stepan1411.sablenautics.mixin.trains;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.stepan1411.sablenautics.trains.SableTrainController;

/**
 * Applies the carriage suspension force once per physics sub-step. Applying it only
 * once per game tick would make the behaviour depend on Sable's substep count.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelMixin {

    @Inject(method = "prePhysicsTick", at = @At("HEAD"))
    private void sablenautics$applyTrainSuspension(SubLevelPhysicsSystem physicsSystem,
                                                   RigidBodyHandle handle,
                                                   double timeStep,
                                                   CallbackInfo ci) {
        SableTrainController.prePhysicsTick((ServerSubLevel) (Object) this, handle, timeStep);
    }
}
