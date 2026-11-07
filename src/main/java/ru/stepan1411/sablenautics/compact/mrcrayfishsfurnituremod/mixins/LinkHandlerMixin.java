package ru.stepan1411.sablenautics.compact.mrcrayfishsfurnituremod.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mrcrayfish.furniture.refurbished.client.LinkHandler;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.stepan1411.sablenautics.compact.mrcrayfishsfurnituremod.MrCrayfishsFurnitureModElectricityClientCompat;

import java.util.Set;

@Mixin(LinkHandler.class)
public abstract class LinkHandlerMixin {
    @Shadow
    private Set<BlockPos> sourcePositions;
    @Shadow
    private double linkLength;
    @Unique
    private boolean sablenautics$powerableAreaPushed;

    @Inject(method = "performLinkRaycast", at = @At("HEAD"), cancellable = true)
    private void sablenautics$performLinkRaycast(Player player, float partialTick, double range,
                                                 CallbackInfoReturnable<HitResult> cir) {
        cir.setReturnValue(MrCrayfishsFurnitureModElectricityClientCompat.performLinkRaycast(
                player, partialTick, range));
    }

    @Inject(method = "renderUnfinishedLink", at = @At("HEAD"), cancellable = true)
    private void sablenautics$renderUnfinishedLink(Player player, BlockPos pos, float partialTick, CallbackInfo ci) {
        this.linkLength = MrCrayfishsFurnitureModElectricityClientCompat.renderUnfinishedLink(
                player, pos, partialTick);
        ci.cancel();
    }

    @Inject(method = "renderPowerableArea", at = @At("HEAD"))
    private void sablenautics$pushPowerableAreaPose(PoseStack poseStack, Player player, float partialTick,
                                                    CallbackInfo ci) {
        ClientSubLevel subLevel = MrCrayfishsFurnitureModElectricityClientCompat.uniformSubLevel(this.sourcePositions);
        this.sablenautics$powerableAreaPushed = subLevel != null;
        if (subLevel != null) {
            poseStack.pushPose();
            MrCrayfishsFurnitureModElectricityClientCompat.applyPose(poseStack, subLevel.renderPose(partialTick));
        }
    }

    @Inject(method = "renderPowerableArea", at = @At("RETURN"))
    private void sablenautics$popPowerableAreaPose(PoseStack poseStack, Player player, float partialTick,
                                                   CallbackInfo ci) {
        if (this.sablenautics$powerableAreaPushed) {
            poseStack.popPose();
            this.sablenautics$powerableAreaPushed = false;
        }
    }

    @Redirect(method = "renderPowerableArea",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 sablenautics$localEyePosition(Player player, float partialTick) {
        Vec3 eyePosition = player.getEyePosition(partialTick);
        ClientSubLevel subLevel = MrCrayfishsFurnitureModElectricityClientCompat.uniformSubLevel(this.sourcePositions);
        return subLevel == null ? eyePosition : subLevel.renderPose(partialTick).transformPositionInverse(eyePosition);
    }
}
