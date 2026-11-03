package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;

public class SableBogeyBlockEntity extends AbstractBogeyBlockEntity {

    private boolean railAbove = false;

    public SableBogeyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return SablenauticsBogeyStyles.SABLE;
    }

    public void tick() {
        if (level != null && !level.isClientSide) {
            boolean hasRail = level.getBlockState(worldPosition.above()).getBlock() instanceof ITrackBlock;
            if (hasRail != railAbove) {
                railAbove = hasRail;
                if (!hasRail) {
                    // Rail above removed - bogey detaches from the rail
                    level.removeBlock(worldPosition, false);
                    net.minecraft.world.level.block.Block.popResource(level, worldPosition, new net.minecraft.world.item.ItemStack(getBlockState().getBlock()));
                }
            }
        }
    }

    public boolean hasRailAbove() {
        return railAbove;
    }
}

