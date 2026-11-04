package ru.stepan1411.sablenautics.mixin.trains;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TrainMigration;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.stepan1411.sablenautics.trains.SableTrainAccess;
import ru.stepan1411.sablenautics.trains.SableTrainController;
import ru.stepan1411.sablenautics.trains.SableTrainDisassembly;
import ru.stepan1411.sablenautics.trains.SableTrainSeats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces Create's entity-based disassembly checks with Sable sub-level based ones.
 * <p>
 * Also marks Sable trains in the train NBT and hides phantom Sable trains (no physical
 * carriage bodies) from Create's collision scan, so normal trains cannot crash into something
 * that is not visible in the world.
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin implements SableTrainAccess {

    @Unique
    private static final Logger SABLENAUTICS_LOGGER = LoggerFactory.getLogger("Sablenautics");

    @Unique
    private boolean sablenautics$wasDerailed;

    @Unique
    private boolean sablenautics$sableTrain;

    @Shadow
    List<TrainMigration> migratingPoints;

    @Override
    public boolean sablenautics$isSableTrain() {
        return sablenautics$sableTrain;
    }

    @Override
    public void sablenautics$setSableTrain(boolean sable) {
        sablenautics$sableTrain = sable;
    }

    @Override
    public void sablenautics$clearMigrations() {
        migratingPoints.clear();
    }

    /**
     * A Sable train has no carriage entity to stop it at terrain. The controller detects a
     * blocked carriage and must freeze the logical train before Create advances its
     * travelling points into the obstacle.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void sablenautics$stopAtWall(Level level, CallbackInfo ci) {
        Train train = (Train) (Object) this;
        if (SableTrainController.isWallBlocked(train, level.getGameTime())) {
            // Keep targetSpeed so the player can still select reverse and back away; only
            // the actual movement towards the obstacle is frozen.
            train.speed = 0;
            train.speedBeforeStall = null;
        }
    }

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

    /**
     * Create counts passengers through the (nonexistent) carriage contraption entities.
     * Count the entities sitting on the sub-level seats instead so schedule conditions such
     * as "player count" work on Sable trains too.
     */
    @Inject(method = "countPlayerPassengers", at = @At("HEAD"), cancellable = true)
    private void sablenautics$countSeatedPassengers(CallbackInfoReturnable<Integer> cir) {
        int count = SableTrainSeats.countSeatedPassengers((Train) (Object) this);
        if (count >= 0) {
            cir.setReturnValue(count);
        }
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void sablenautics$writeSableFlag(DimensionPalette dimensions, HolderLookup.Provider registries,
                                             CallbackInfoReturnable<CompoundTag> cir) {
        if (sablenautics$sableTrain) {
            cir.getReturnValue().putBoolean(SableTrainController.SABLE_TRAIN_KEY, true);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void sablenautics$readSableFlag(CompoundTag tag, HolderLookup.Provider registries,
                                                   Map<UUID, TrackGraph> trackNetworks, DimensionPalette dimensions,
                                                   CallbackInfoReturnable<Train> cir) {
        Train train = cir.getReturnValue();
        if (train != null && tag.getBoolean(SableTrainController.SABLE_TRAIN_KEY)) {
            SableTrainController.markSableTrain(train);
        }
    }

    /**
     * Filter the train list used by {@code Train.findCollidingTrain}. Sable trains that are
     * fully detached or have lost their bodies must not be hit by normal trains.
     */
    @WrapOperation(
            method = "findCollidingTrain",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;values()Ljava/util/Collection;"
            )
    )
    private Collection<Train> sablenautics$filterPhantomTrains(Map<UUID, Train> trains,
                                                              Operation<Collection<Train>> original) {
        Collection<Train> all = original.call(trains);
        List<Train> relevant = new ArrayList<>(all.size());
        for (Train train : all) {
            if (SableTrainController.isCollisionRelevant(train)) {
                relevant.add(train);
            }
        }
        return relevant;
    }
}
