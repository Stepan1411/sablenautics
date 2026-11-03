package ru.stepan1411.sablenautics.mixin.trains;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = Contraption.class, remap = false)
public interface ContraptionAccessor {

    @Accessor("storage")
    MountedStorageManager sablenautics$getRawStorage();

    @Accessor("superglue")
    List<AABB> sablenautics$getSuperglue();
}
