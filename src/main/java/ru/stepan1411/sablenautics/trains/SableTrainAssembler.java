package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.*;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stepan1411.sablenautics.index.SablenauticsBlocks;
import ru.stepan1411.sablenautics.mixin.trains.ContraptionAccessor;

import java.util.*;

public class SableTrainAssembler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sablenautics");

    private static final ResourceLocation INVISIBLE_BOGEY =
            ResourceLocation.fromNamespaceAndPath("railways", "invisible_bogey");

    // Temporary storage for assembled sublevels before train is created.
    // Keyed by contraption, filled during phase 1 (assemble), consumed during phase 2 (registerTrain).
    // Insertion order matches Create's carriage order, so it can be used for carriage indexing.
    private static final Map<CarriageContraption, SubLevelData> PENDING_SUBLEVELS = new LinkedHashMap<>();
    private static final Set<CarriageContraption> SABLE_CONTRAPTIONS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static class SubLevelData {
        final ServerSubLevel subLevel;
        final CarriageContraption contraption;
        final List<BogieInfo> bogies;
        List<AABB> superglue = List.of();

        SubLevelData(ServerSubLevel subLevel, CarriageContraption contraption, List<BogieInfo> bogies) {
            this.subLevel = subLevel;
            this.contraption = contraption;
            this.bogies = bogies;
        }
    }

    private record BogeyCapture(BlockPos globalPos, ResourceLocation styleId, ResourceLocation sizeId,
                                CompoundTag bogeyData, Direction.Axis axis, boolean upsideDown,
                                double wheelRadius, BlockState originalState) {
    }

    /**
     * Phase 1: Assemble blocks into a Sable sublevel BEFORE they are removed from world.
     * Called from mixin wrapping removeBlocksFromWorld.
     */
    public static boolean hasPhysicsSticker(CarriageContraption contraption) {
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks == null) return false;
        for (StructureBlockInfo info : blocks.values()) {
            if (info.state().getBlock() == SablenauticsBlocks.PHYSICS_STICKER.get()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSable(CarriageContraption contraption) {
        return contraption != null && SABLE_CONTRAPTIONS.contains(contraption);
    }

    public static void clear() {
        PENDING_SUBLEVELS.clear();
        SABLE_CONTRAPTIONS.clear();
    }

    public static void assemble(CarriageContraption contraption, Level level) {
        LOGGER.debug("Sablenautics: Start Sublevel assembly");

        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!hasPhysicsSticker(contraption)) {
            LOGGER.debug("Sablenautics: carriage has no physics sticker; keeping it a normal Create carriage");
            return;
        }

        BlockPos anchor = contraption.anchor;
        Set<BlockPos> globalPosSet = new LinkedHashSet<>();
        List<BogeyCapture> bogeyCaptures = new ArrayList<>();

        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks == null) {
            LOGGER.warn("Sablenautics: contraption blocks is null");
            return;
        }

        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            BlockPos localPos = entry.getKey();
            BlockPos globalPos = localPos.offset(anchor);
            StructureBlockInfo info = entry.getValue();

            if (info.state().getBlock() instanceof AbstractBogeyBlock<?> bogeyBlock) {
                // The wheel block is removed from the carriage; its model is drawn by an entity.
                bogeyCaptures.add(captureBogey(serverLevel, globalPos, info.state(), bogeyBlock));
            } else {
                globalPosSet.add(globalPos);
            }

            // Train controls blocks live inside the sub-level, not in a ContraptionWorld, so Create never
            // flips them to their "open" state. Do it before the blocks are moved into the plot.
            if (info.state().getBlock() instanceof ControlsBlock) {
                var worldState = serverLevel.getBlockState(globalPos);
                if (worldState.getBlock() instanceof ControlsBlock
                        && worldState.hasProperty(ControlsBlock.OPEN)
                        && !worldState.getValue(ControlsBlock.OPEN)) {
                    serverLevel.setBlock(globalPos, worldState.setValue(ControlsBlock.OPEN, true), 2);
                }
            }
        }

        if (globalPosSet.isEmpty()) {
            LOGGER.warn("Sablenautics: no blocks to assemble");
            return;
        }

        // Hide the wheel blocks inside the carriage. Railways' invisible bogey is used when
        // present; otherwise the block is removed entirely, so the mod still works without it.
        for (BogeyCapture capture : bogeyCaptures) {
            if (hideBogeyBlock(serverLevel, capture.globalPos())) {
                globalPosSet.add(capture.globalPos());
            }
        }

        BoundingBox3i bounds = BoundingBox3i.from(globalPosSet);
        if (bounds == null) {
            LOGGER.error("Sablenautics: Sublevel bounds is null");
            return;
        }

        SubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(serverLevel, anchor, globalPosSet, bounds);
        if (!(subLevel instanceof ServerSubLevel serverSublevel)) {
            LOGGER.error("Sablenautics: Couldn't cast Sublevel to ServerSubLevel");
            return;
        }

        // Make sure the controls model is open inside the plot. The same state was written into
        // the world before assembly, but block updates during the move may have reset it.
        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        for (BlockPos local : blocks.keySet()) {
            BlockPos plotPos = plotAnchor.offset(local.getX() - anchor.getX(),
                    local.getY() - anchor.getY(), local.getZ() - anchor.getZ());
            BlockState plotState = serverLevel.getBlockState(plotPos);
            if (plotState.getBlock() instanceof ControlsBlock
                    && plotState.hasProperty(ControlsBlock.OPEN)
                    && !plotState.getValue(ControlsBlock.OPEN)) {
                serverLevel.setBlock(plotPos, plotState.setValue(ControlsBlock.OPEN, true), 0);
            }
        }

        Vector3dc baseCoM = serverSublevel.getMassTracker().getCenterOfMass();
        if (baseCoM == null) {
            LOGGER.error("Sablenautics: Center of Mass is null");
            return;
        }

        List<BogieInfo> bogies = new ArrayList<>(bogeyCaptures.size());
        Vector3d plotCenter = new Vector3d(plotAnchor.getX() + 0.5, plotAnchor.getY() + 0.5, plotAnchor.getZ() + 0.5);
        for (BogeyCapture capture : bogeyCaptures) {
            BlockPos plotPos = plotAnchor.offset(
                    capture.globalPos().getX() - anchor.getX(),
                    capture.globalPos().getY() - anchor.getY(),
                    capture.globalPos().getZ() - anchor.getZ());
            Vector3d localCenter = new Vector3d(
                    plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5);
            Vec3 initialWorld = new Vec3(
                    capture.globalPos().getX() + 0.5,
                    capture.globalPos().getY() + 0.5,
                    capture.globalPos().getZ() + 0.5);
            bogies.add(new BogieInfo(localCenter, initialWorld, capture.styleId(), capture.sizeId(),
                    capture.bogeyData(), capture.axis(), capture.upsideDown(), capture.wheelRadius(),
                    capture.originalState()));
        }

        // Create's Carriage lists the leading bogey first; the leading bogey is the one
        // closest to the carriage anchor (the plot centre).
        bogies.sort(Comparator.comparingDouble(info -> info.localPlotCenter().distanceSquared(plotCenter)));

        // Store for phase 2 (train registration after train is created)
        SABLE_CONTRAPTIONS.add(contraption);
        PENDING_SUBLEVELS.put(contraption, new SubLevelData(serverSublevel, contraption, bogies));
        LOGGER.info("Sablenautics: Assembled Sable sublevel {} with {} bogey entity/entities",
                serverSublevel.getUniqueId(), bogies.size());

        LOGGER.debug("Sablenautics: End Sublevel assembly");
    }

    private static BogeyCapture captureBogey(ServerLevel level, BlockPos pos, BlockState state,
                                             AbstractBogeyBlock<?> block) {
        ResourceLocation styleId = block.getDefaultStyle().id;
        CompoundTag bogeyData = new CompoundTag();

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AbstractBogeyBlockEntity bogeyBe) {
            styleId = bogeyBe.getStyle().id;
            bogeyData = bogeyBe.getBogeyData().copy();
        }

        Direction.Axis axis = state.hasProperty(AbstractBogeyBlock.AXIS)
                ? state.getValue(AbstractBogeyBlock.AXIS)
                : Direction.Axis.Z;

        return new BogeyCapture(pos, styleId, block.getSize().id(), bogeyData, axis,
                block.isUpsideDown(state), block.getWheelRadius(), state);
    }

    /**
     * @return true if a block should still be moved into the body sub-level
     */
    private static boolean hideBogeyBlock(ServerLevel level, BlockPos pos) {
        BlockState oldState = level.getBlockState(pos);
        if (!(oldState.getBlock() instanceof AbstractBogeyBlock<?>)) return false;

        Block invisibleBlock = BuiltInRegistries.BLOCK.get(INVISIBLE_BOGEY);
        if (invisibleBlock instanceof AbstractBogeyBlock<?>) {
            BlockState invisible = invisibleBlock.defaultBlockState();
            if (invisible.hasProperty(AbstractBogeyBlock.AXIS)
                    && oldState.hasProperty(AbstractBogeyBlock.AXIS)) {
                invisible = invisible.setValue(AbstractBogeyBlock.AXIS,
                        oldState.getValue(AbstractBogeyBlock.AXIS));
            }
            if (invisible.hasProperty(BlockStateProperties.WATERLOGGED)
                    && oldState.hasProperty(BlockStateProperties.WATERLOGGED)) {
                invisible = invisible.setValue(BlockStateProperties.WATERLOGGED,
                        oldState.getValue(BlockStateProperties.WATERLOGGED));
            }
            level.setBlock(pos, invisible, Block.UPDATE_ALL);
            return true;
        }

        // No Railways: fully remove the block. The wheel model is supplied by the entity.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        return false;
    }

    /**
     * Called right after {@code Contraption.removeBlocksFromWorld} moved the glue entities into
     * the contraption. Keep those boxes so disassembly can restore the glue.
     */
    public static void captureGlue(CarriageContraption contraption) {
        SubLevelData data = PENDING_SUBLEVELS.get(contraption);
        if (data == null) return;
        List<AABB> glue = ((ContraptionAccessor) contraption).sablenautics$getSuperglue();
        data.superglue = glue == null ? List.of() : List.copyOf(glue);
        LOGGER.debug("Sablenautics: Captured {} super glue box(es) for sublevel {}",
                data.superglue.size(), data.subLevel.getUniqueId());
    }

    /**
     * Phase 2: Register the assembled sublevels with the train.
     * Called from mixin at TAIL of StationBlockEntity.assemble(), after train is created.
     */
    public static void registerTrain(Train train) {
        if (train == null) {
            LOGGER.warn("Sablenautics: registerTrain called with null train");
            return;
        }

        LOGGER.info("Sablenautics: registerTrain called for train {} with {} carriages",
                train.id, train.carriages.size());

        int registered = 0;
        int carriageCount = train.carriages.size();
        for (int carriageIndex = 0; carriageIndex < carriageCount; carriageIndex++) {
            Carriage carriage = train.carriages.get(carriageIndex);
            CarriageContraption carriageContraption = SableCarriageRegistry.get(carriage);

            SubLevelData data = carriageContraption != null
                    ? PENDING_SUBLEVELS.remove(carriageContraption)
                    : null;
            if (data == null) continue;

            SableTrainController.registerSubLevel(train, data.subLevel, data.contraption,
                    carriageIndex, data.superglue, data.bogies);
            LOGGER.info("Sablenautics: Registered train {} carriage {} with Sable sublevel {}",
                    train.id, carriageIndex, data.subLevel.getUniqueId());
            registered++;
        }

        if (registered == 0) {
            LOGGER.warn("Sablenautics: No pending sublevel found for train {}", train.id);
        } else if (registered < carriageCount) {
            LOGGER.warn("Sablenautics: Train {} has {} carriages but only {} sublevels were registered",
                    train.id, carriageCount, registered);
        }

        // Any leftovers would be sub-levels without a train owner and would fall freely.
        if (!PENDING_SUBLEVELS.isEmpty()) {
            LOGGER.warn("Sablenautics: Discarding {} unclaimed pending sublevels",
                    PENDING_SUBLEVELS.size());
            PENDING_SUBLEVELS.clear();
        }

        SABLE_CONTRAPTIONS.clear();
    }
}
