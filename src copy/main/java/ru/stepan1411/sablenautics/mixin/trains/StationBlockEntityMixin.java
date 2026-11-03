package ru.stepan1411.sablenautics.mixin.trains;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.stepan1411.sablenautics.index.SablenauticsBlocks;
import ru.stepan1411.sablenautics.trains.SableTrainAssembler;

import java.util.UUID;

@Mixin(value = StationBlockEntity.class, remap = false)
public abstract class StationBlockEntityMixin {

    @Shadow
    Direction assemblyDirection;

    @Shadow
    int assemblyLength;

    /**
     * Scans the train area once at the start of assembly. If any carriage carries a
     * physics sticker, the whole train becomes Sable, so the train never mixes normal
     * Create carriage entities with Sable physics bodies (which caused collisions and
     * launched long trains).
     */
    @Inject(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/station/StationBlockEntity;" +
                             "refreshAssemblyInfo()V",
                    shift = At.Shift.AFTER
            )
    )
    private void sablenautics$scanTrainForSticker(UUID playerUUID, CallbackInfo ci) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        Level level = blockEntity.getLevel();
        if (level == null || assemblyDirection == null) return;

        BlockPos origin = blockEntity.getBlockPos();
        Direction side = assemblyDirection.getClockWise();
        int length = Math.max(assemblyLength, 8);

        for (int along = -2; along <= length + 2; along++) {
            BlockPos base = origin.relative(assemblyDirection, along);
            for (int lateral = -12; lateral <= 12; lateral++) {
                BlockPos sideBase = base.relative(side, lateral);
                for (int up = -6; up <= 12; up++) {
                    BlockPos pos = sideBase.above(up);
                    if (level.getBlockState(pos).getBlock() == SablenauticsBlocks.PHYSICS_STICKER.get()) {
                        SableTrainAssembler.setCurrentAssemblySable(true);
                        return;
                    }
                }
            }
        }
    }

    @Inject(method = "assemble", at = @At("RETURN"))
    private void sablenautics$resetStickerScan(UUID playerUUID, CallbackInfo ci) {
        SableTrainAssembler.resetCurrentAssemblySable();
    }

    @WrapOperation(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraption;" +
                             "removeBlocksFromWorld(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"
            )
    )
    private void sablenautics$onRemoveBlocksFromWorld(CarriageContraption contraption, Level level, BlockPos offset, Operation<Void> original) {
        SableTrainAssembler.assemble(contraption, level);
        original.call(contraption, level, offset);
        SableTrainAssembler.captureGlue(contraption);
    }

    @WrapOperation(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/GlobalRailwayManager;addTrain(Lcom/simibubi/create/content/trains/entity/Train;)V"
            )
    )
    private void sablenautics$onAddTrain(GlobalRailwayManager railway, Train train, Operation<Void> original) {
        // Phase 2: Register the assembled sublevel with the train
        SableTrainAssembler.registerTrain(train);
        original.call(railway, train);
    }
}