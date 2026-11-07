package ru.stepan1411.sablenautics.compat.mixins.MrCrayfishsFurnitureMod;

import com.mrcrayfish.furniture.refurbished.electricity.NodeHitResult;
import com.mrcrayfish.furniture.refurbished.item.WrenchItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.stepan1411.sablenautics.compat.MrCrayfishsFurnitureMod.MrCrayfishsFurnitureModElectricityCompat;

@Mixin(WrenchItem.class)
public class WrenchItemMixin {
    @Inject(method = "performNodeRaycast", at = @At("HEAD"), cancellable = true)
    private static void sablenautics$performNodeRaycast(Level level, Player player, double range, float partialTick,
                                                        CallbackInfoReturnable<NodeHitResult> cir) {
        cir.setReturnValue(MrCrayfishsFurnitureModElectricityCompat.performNodeRaycast(
                level, player, range, partialTick));
    }
}
