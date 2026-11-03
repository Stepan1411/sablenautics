package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.contraptions.chassis.StickerBlock;
import ru.stepan1411.sablenautics.index.SablenauticsBlockEntityTypes;

public class PhysicsStickerBlock extends StickerBlock {
    public PhysicsStickerBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntityType<? extends com.simibubi.create.content.contraptions.chassis.StickerBlockEntity> getBlockEntityType() {
        return (net.minecraft.world.level.block.entity.BlockEntityType<? extends com.simibubi.create.content.contraptions.chassis.StickerBlockEntity>) (Object) SablenauticsBlockEntityTypes.PHYSICS_STICKER.get();
    }
}
