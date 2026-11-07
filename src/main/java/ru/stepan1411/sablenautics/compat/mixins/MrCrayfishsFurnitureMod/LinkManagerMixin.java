package ru.stepan1411.sablenautics.compat.mixins.MrCrayfishsFurnitureMod;

import com.mrcrayfish.furniture.refurbished.electricity.LinkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.stepan1411.sablenautics.compat.MrCrayfishsFurnitureMod.MrCrayfishsFurnitureModElectricityCompat;

@Mixin(LinkManager.class)
public class LinkManagerMixin {
    @Redirect(method = {"onNodeInteract", "startLinking"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;)V"))
    private static void sablenautics$playNodeSound(Level level, Player player, BlockPos pos, SoundEvent sound,
                                                   SoundSource source) {
        Vec3 world = MrCrayfishsFurnitureModElectricityCompat.projectOutOfSubLevel(level, pos.getCenter());
        level.playSound(player, world.x, world.y, world.z, sound, source, 1.0F, 1.0F);
    }
}
