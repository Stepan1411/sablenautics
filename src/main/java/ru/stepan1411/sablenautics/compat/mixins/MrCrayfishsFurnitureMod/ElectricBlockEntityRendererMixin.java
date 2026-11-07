package ru.stepan1411.sablenautics.compat.mixins.MrCrayfishsFurnitureMod;

import com.mrcrayfish.furniture.refurbished.client.renderer.blockentity.ElectricBlockEntityRenderer;
import com.mrcrayfish.furniture.refurbished.electricity.IElectricityNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.stepan1411.sablenautics.compat.MrCrayfishsFurnitureMod.MrCrayfishsFurnitureModElectricityClientCompat;

@Mixin(ElectricBlockEntityRenderer.class)
public class ElectricBlockEntityRendererMixin {
    @Inject(method = "drawNodeAndConnections", at = @At("HEAD"), cancellable = true)
    private static void sablenautics$drawNodeAndConnections(IElectricityNode node, CallbackInfo ci) {
        if (MrCrayfishsFurnitureModElectricityClientCompat.drawNodeAndConnectionsInWorld(node)) {
            ci.cancel();
        }
    }
}
