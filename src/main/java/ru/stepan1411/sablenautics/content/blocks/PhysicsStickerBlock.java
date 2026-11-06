package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.contraptions.chassis.StickerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ru.stepan1411.sablenautics.index.SablenauticsBlockEntityTypes;

public class PhysicsStickerBlock extends StickerBlock {
    private static final double TICK = 1.0 / 16.0;

    // Второе свойство: горизонтальная ориентация стикера, когда он приклеен к потолку или полу
    public static final EnumProperty<Direction> ROTATION = EnumProperty.create("rotation", Direction.class,
        d -> d.getAxis().isHorizontal());

    // Тонкая пластина в точности по модели assets/sablenautics/models/block/sticker.json
    // (from [1,1,15] to [15,15,16], повернута под соответствующую сторону блока).
    // Хикбокс инвертирован относительно FACING на 180°, чтобы совпадать с моделью после
    // поворота блокстейта. Для UP/DOWN форма симметрична по Y, rotation на хитбокс не влияет.
    private static VoxelShape panelShape(Direction facing) {
        return switch (facing) {
            case NORTH -> Shapes.box(TICK, TICK, 15 * TICK, 15 * TICK, 15 * TICK, 1); // юг
            case SOUTH -> Shapes.box(TICK, TICK, 0, 15 * TICK, 15 * TICK, TICK);       // север
            case WEST  -> Shapes.box(15 * TICK, TICK, TICK, 1, 15 * TICK, 15 * TICK);  // восток
            case EAST  -> Shapes.box(0, TICK, TICK, TICK, 15 * TICK, 15 * TICK);       // запад
            case UP    -> Shapes.box(TICK, 15 * TICK, TICK, 15 * TICK, 1, 15 * TICK);  // верх
            case DOWN  -> Shapes.box(TICK, 0, TICK, 15 * TICK, TICK, 15 * TICK);       // низ
            default    -> Shapes.block();
        };
    }

    public PhysicsStickerBlock(final Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(ROTATION));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction nearestLookingDirection = context.getNearestLookingDirection();
        boolean shouldPower = context.getLevel().hasNeighborSignal(context.getClickedPos());
        // Смотрю вниз → facing=down, смотрю на северную стену → facing=north.
        // Со Shift — направление инвертируется (как в оригинальном Create).
        Direction facing = context.getPlayer() != null && context.getPlayer()
            .isShiftKeyDown() ? nearestLookingDirection.getOpposite() : nearestLookingDirection;
        Direction rotation = context.getPlayer() != null ? context.getPlayer().getDirection() : Direction.NORTH;

        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(ROTATION, rotation)
            .setValue(POWERED, shouldPower);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return panelShape(state.getValue(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return panelShape(state.getValue(FACING));
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return panelShape(state.getValue(FACING));
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntityType<? extends com.simibubi.create.content.contraptions.chassis.StickerBlockEntity> getBlockEntityType() {
        return (net.minecraft.world.level.block.entity.BlockEntityType<? extends com.simibubi.create.content.contraptions.chassis.StickerBlockEntity>) (Object) SablenauticsBlockEntityTypes.PHYSICS_STICKER.get();
    }
}
