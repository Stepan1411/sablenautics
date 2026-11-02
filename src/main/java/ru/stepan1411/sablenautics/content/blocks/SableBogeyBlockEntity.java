package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;

public class SableBogeyBlockEntity extends AbstractBogeyBlockEntity {

    public SableBogeyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return SablenauticsBogeyStyles.SABLE;
    }
}

