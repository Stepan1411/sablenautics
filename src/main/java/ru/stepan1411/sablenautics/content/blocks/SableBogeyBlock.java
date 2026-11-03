package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;
import ru.stepan1411.sablenautics.index.SablenauticsBlockEntityTypes;

/**
 * Sable bogey block. Represents the wheel assembly of a Sable carriage.
 * The block sits ON the rail block below it — if the rail below is removed,
 * the bogey block detaches (drops itself as an item).
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

    /**
     * A bogey block can only exist on top of a rail block — it sits on the rail below.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.getBlock() instanceof ITrackBlock;
    }

    /**
     * When the rail below is removed, the bogey block detaches and drops itself.
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos fromPos,
                                boolean isMoving) {
        if (fromPos != null && fromPos.equals(pos.below())) {
            if (!(level.getBlockState(pos.below()).getBlock() instanceof ITrackBlock)) {
                // Rail below was removed — bogey detaches from the rail.
                level.removeBlock(pos, false);
                popResource(level, pos, new ItemStack(this));
            }
        }
        super.neighborChanged(state, level, pos, neighborBlock, fromPos, isMoving);
    }
}

