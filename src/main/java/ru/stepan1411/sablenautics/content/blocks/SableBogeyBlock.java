package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;
import ru.stepan1411.sablenautics.index.SablenauticsBlockEntityTypes;

/**
 * Sable bogey block. It replaces the original bogey before the carriage is
 * moved into a Sable sub-level, so cars still contain a valid {@link AbstractBogeyBlock}
 * while the visible wheel model is drawn by its block entity renderer.
 */
public class SableBogeyBlock extends AbstractBogeyBlock<SableBogeyBlockEntity> {

    public SableBogeyBlock(Properties properties) {
        super(properties, BogeySizes.SMALL);
    }

    @Override
    public TrackMaterial.TrackType getTrackType(BogeyStyle style) {
        return TrackMaterial.TrackType.STANDARD;
    }

    @Override
    public double getWheelPointSpacing() {
        return 2;
    }

    @Override
    public double getWheelRadius() {
        return size.wheelRadius();
    }

    @Override
    protected Vec3 getConnectorAnchorOffset() {
        return new Vec3(0, 7 / 32f, 1);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return SablenauticsBogeyStyles.SABLE;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
                                       Player player) {
        return AllBlocks.RAILWAY_CASING.asStack();
    }

    /**
     * The placeholder is not meant to be cycled into a visible bogey with a wrench.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public Class<SableBogeyBlockEntity> getBlockEntityClass() {
        return SableBogeyBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SableBogeyBlockEntity> getBlockEntityType() {
        return SablenauticsBlockEntityTypes.SABLE_BOGEY.get();
    }
}

