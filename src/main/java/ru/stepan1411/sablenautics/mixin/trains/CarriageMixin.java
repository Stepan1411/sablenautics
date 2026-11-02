package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.stepan1411.sablenautics.trains.SableCarriageRegistry;

/**
 * Never create a {@link com.simibubi.create.content.trains.entity.CarriageContraptionEntity}.
 * The carriage is a Sable sub-level; only its assembly data is kept.
 */
@Mixin(value = Carriage.class, remap = false)
public abstract class CarriageMixin {

    @Inject(method = "setContraption", at = @At("HEAD"), cancellable = true)
    private void sablenautics$setContraption(Level level, CarriageContraption contraption, CallbackInfo ci) {
        SableCarriageRegistry.onContraptionSet((Carriage) (Object) this, level, contraption);
        ci.cancel();
    }

    /**
     * Create normally initialises the cargo manager through the carriage entity. Existing saves
     * may therefore hold an uninitialised manager; writing it would crash on save.
     */
    @Inject(method = "write", at = @At("HEAD"))
    private void sablenautics$initializeStorage(DimensionPalette dimensions,
                                                HolderLookup.Provider registries,
                                                CallbackInfoReturnable<CompoundTag> cir) {
        Carriage carriage = (Carriage) (Object) this;
        if (carriage.storage != null) {
            carriage.storage.initialize();
        }
    }
}
