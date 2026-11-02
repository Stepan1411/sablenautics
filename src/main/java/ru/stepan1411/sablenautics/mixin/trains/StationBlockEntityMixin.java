package ru.stepan1411.sablenautics.mixin.trains;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ru.stepan1411.sablenautics.trains.SableTrainAssembler;

@Mixin(value = StationBlockEntity.class, remap = false)
public abstract class StationBlockEntityMixin {

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