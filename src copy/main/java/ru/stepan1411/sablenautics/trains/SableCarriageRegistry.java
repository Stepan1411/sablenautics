package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.content.contraptions.minecart.TrainCargoManager;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.world.level.Level;
import ru.stepan1411.sablenautics.mixin.trains.CarriageContraptionAccessor;
import ru.stepan1411.sablenautics.mixin.trains.ContraptionAccessor;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps the assembly data of a carriage without ever creating a
 * {@code CarriageContraptionEntity}. The train itself is represented by a Sable sub-level.
 */
public final class SableCarriageRegistry {
    private static final Map<Carriage, CarriageContraption> CONTRAPTIONS = new IdentityHashMap<>();

    private SableCarriageRegistry() {
    }

    public static void onContraptionSet(Carriage carriage, Level level, CarriageContraption contraption) {
        CONTRAPTIONS.put(carriage, contraption);

        // CarriageContraptionEntity normally initialises the contraption storage and moves the
        // cargo manager onto the carriage. Do the same without an entity.
        MountedStorageManager rawStorage = ((ContraptionAccessor) contraption).sablenautics$getRawStorage();
        if (rawStorage != null) {
            rawStorage.initialize();
            if (rawStorage instanceof TrainCargoManager cargo) {
                carriage.storage = cargo;
                ((CarriageContraptionAccessor) contraption).sablenautics$setStorageProxy(cargo);
            }
        }

        // Normal Create gets anchors updated from CarriageContraptionEntity#tick. Without the entity
        // we refresh them on demand from the train's travelling points.
        carriage.updateContraptionAnchors();
    }

    public static CarriageContraption get(Carriage carriage) {
        return CONTRAPTIONS.get(carriage);
    }

    public static void clear() {
        CONTRAPTIONS.clear();
    }
}
