package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CarriageContraption.class, remap = false)
public interface CarriageContraptionAccessor {

    @Accessor("storageProxy")
    void sablenautics$setStorageProxy(MountedStorageManager storage);
}
