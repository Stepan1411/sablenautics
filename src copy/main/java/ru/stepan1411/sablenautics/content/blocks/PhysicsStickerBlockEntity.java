package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.contraptions.chassis.StickerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class PhysicsStickerBlockEntity extends StickerBlockEntity {
    public PhysicsStickerBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }
}
