package ru.stepan1411.sablenautics.index;

import com.tterrag.registrate.util.entry.BlockEntityEntry;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.PhysicsStickerBlockEntity;

public class SablenauticsBlockEntityTypes {
    private static final com.simibubi.create.foundation.data.CreateRegistrate REGISTRATE = Sablenautics.getRegistrate();

    public static final BlockEntityEntry<PhysicsStickerBlockEntity> PHYSICS_STICKER = REGISTRATE
            .blockEntity("physics_sticker", PhysicsStickerBlockEntity::new)
            .validBlocks(SablenauticsBlocks.PHYSICS_STICKER)
            .register();

    public static void init() {
    }
}
