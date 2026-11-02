package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.stepan1411.sablenautics.trains.SableTrainDisassembly;

/**
 * Replaces Create's entity-based disassembly checks with Sable sub-level based ones.
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

    @Unique
    private static final Logger SABLENAUTICS_LOGGER = LoggerFactory.getLogger("Sablenautics");

    @Unique
    private boolean sablenautics$wasDerailed;

    @Inject(method = "tick", at = @At("TAIL"))
    private void sablenautics$logDerail(Level level, CallbackInfo ci) {
        Train train = (Train) (Object) this;
        if (train.derailed && !sablenautics$wasDerailed) {
            StringBuilder details = new StringBuilder();
            for (Carriage carriage : train.carriages) {
                details.append(" [spacing=").append(carriage.bogeySpacing)
                        .append(" anchorDiff=").append(carriage.getAnchorDiff())
                        .append(" leadStress=").append(carriage.leadingBogey().getStress())
                        .append("]");
            }
            SABLENAUTICS_LOGGER.warn("Sablenautics: train {} became derailed at speed {};{}",
                    train.id, train.speed, details);
        }
        sablenautics$wasDerailed = train.derailed;
    }

    @Inject(method = "canDisassemble", at = @At("HEAD"), cancellable = true)
    private void sablenautics$canDisassemble(CallbackInfoReturnable<Boolean> cir) {
        Boolean result = SableTrainDisassembly.canDisassemble((Train) (Object) this);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "disassemble", at = @At("HEAD"), cancellable = true)
    private void sablenautics$disassemble(Direction assemblyDirection, BlockPos pos,
                                          CallbackInfoReturnable<Boolean> cir) {
        Boolean result = SableTrainDisassembly.disassemble((Train) (Object) this, assemblyDirection, pos);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
