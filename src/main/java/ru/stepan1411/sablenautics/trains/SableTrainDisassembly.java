package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stepan1411.sablenautics.content.entities.SableBogeyEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Disassembles Sable trains back into blocks. Replacement for the entity based logic in
 * {@code Train.canDisassemble()} / {@code Train.disassemble()}.
 */
public final class SableTrainDisassembly {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sablenautics");

    private SableTrainDisassembly() {
    }

    /**
     * @return null if the train is not fully represented by Sable sub-levels (let Create handle it)
     */
    public static Boolean canDisassemble(Train train) {
        if (train.carriages.isEmpty()) return null;

        SableTrainController.ensureMappings(train);

        boolean anySable = false;
        for (int i = 0; i < train.carriages.size(); i++) {
            if (SableTrainController.subLevelFor(train, i) == null) {
                if (anySable) return false;
                return null;
            }
            anySable = true;
        }

        for (int i = 0; i < train.carriages.size(); i++) {
            Carriage carriage = train.carriages.get(i);
            if (carriage.presentInMultipleDimensions()) return false;

            ServerSubLevel subLevel = SableTrainController.subLevelFor(train, i);
            if (subLevel == null || !(subLevel.getLevel() instanceof ServerLevel level)) return false;
            if (SableTrainController.isDetached(subLevel)) return false;

            SableTrainController.Alignment alignment = SableTrainController.alignment(level, carriage);
            if (alignment == null) return false;
            if (!Mth.equal(alignment.pitch(), 0)) return false;
            if (!Mth.equal(((alignment.yaw() % 90) + 360) % 90, 0)) return false;
        }
        return true;
    }

    /**
     * @return null if the train is not fully represented by Sable sub-levels (let Create handle it)
     */
    public static Boolean disassemble(Train train, Direction assemblyDirection, BlockPos pos) {
        Boolean can = canDisassemble(train);
        if (can == null) return null;
        if (!can) return false;

        ServerSubLevel first = SableTrainController.subLevelFor(train, 0);
        if (first == null || !(first.getLevel() instanceof ServerLevel level)) return false;

        // All carriages must live in the same level for a block based disassembly.
        for (int i = 1; i < train.carriages.size(); i++) {
            ServerSubLevel subLevel = SableTrainController.subLevelFor(train, i);
            if (subLevel == null || subLevel.getLevel() != level) return false;
        }

        boolean backwards = train.currentlyBackwards;
        int offset = 1;
        int carriageCount = train.carriages.size();

        for (int i = 0; i < carriageCount; i++) {
            int carriageIndex = backwards ? carriageCount - 1 - i : i;
            Carriage carriage = train.carriages.get(carriageIndex);
            ServerSubLevel subLevel = SableTrainController.subLevelFor(train, carriageIndex);
            if (subLevel == null) return false;

            SableTrainController.Alignment alignment = SableTrainController.alignment(level, carriage);
            if (alignment == null) return false;

            BlockPos target = pos.relative(assemblyDirection,
                            backwards ? offset + carriage.bogeySpacing : offset)
                    .below(carriage.leadingBogey().isUpsideDown() ? 2 : 0);

            StructureTransform transform = new StructureTransform(target, 0,
                    -alignment.yaw() + alignment.initialYaw(), 0);

            List<BlockPos> placedBogeys = new ArrayList<>(placeBlocks(level, subLevel, transform));

            List<AABB> superglue = SableTrainController.superglueFor(subLevel);
            purgeSubLevel(level, subLevel);
            restoreGlue(level, transform, superglue);

            if (!restoreBogeyBlocks(level, subLevel, transform)) {
                // Legacy saves do not carry BogieInfo; fall back to Create's carriage data.
                restoreBogeyData(level, carriage, placedBogeys, assemblyDirection);
            }

            offset += carriage.bogeySpacing;
            if (i < train.carriageSpacing.size()) {
                offset += train.carriageSpacing.get(backwards ? train.carriageSpacing.size() - 1 - i : i);
            }
        }

        GlobalStation station = train.getCurrentStation();
        if (station != null) {
            station.cancelReservation(train);
            BlockPos stationPos = station.getBlockEntityPos();
            if (level.getBlockEntity(stationPos) instanceof StationBlockEntity sbe) {
                sbe.lastDisassembledTrainName = train.name.copy();
                sbe.lastDisassembledMapColorIndex = train.mapColorIndex;
            }
        }

        SableTrainControlManager.stopForTrain(level.getServer(), train.id);
        SableBogeyEntity.discardForTrain(train.id);
        Create.RAILWAYS.removeTrain(train.id);
        CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
        LOGGER.info("Sablenautics: Disassembled train {} into blocks", train.id);
        return true;
    }

    private static List<BlockPos> placeBlocks(ServerLevel level, ServerSubLevel subLevel,
                                              StructureTransform transform) {
        List<BlockPos> placedBogeys = new ArrayList<>();
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) return placedBogeys;
        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos plotPos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(plotPos);
                    if (state.isAir()) continue;

                    BlockPos relative = plotPos.subtract(plotAnchor);
                    BlockPos newPos = transform.apply(relative);
                    BlockState newState = transform.apply(state);

                    if (newState.getBlock() instanceof ControlsBlock
                            && newState.hasProperty(ControlsBlock.OPEN)) {
                        newState = newState.setValue(ControlsBlock.OPEN, false);
                    }

                    if (newPos.getY() < level.getMinBuildHeight() || newPos.getY() >= level.getMaxBuildHeight()) {
                        continue;
                    }

                    BlockState existing = level.getBlockState(newPos);
                    if (existing.getDestroySpeed(level, newPos) == -1) continue;

                    level.destroyBlock(newPos, true);
                    level.setBlock(newPos, newState, Block.UPDATE_ALL);

                    BlockEntity oldBe = level.getBlockEntity(plotPos);
                    if (oldBe != null) {
                        CompoundTag tag = oldBe.saveWithFullMetadata(level.registryAccess());
                        tag.putInt("x", newPos.getX());
                        tag.putInt("y", newPos.getY());
                        tag.putInt("z", newPos.getZ());
                        BlockEntity newBe = BlockEntity.loadStatic(newPos, newState, tag, level.registryAccess());
                        if (newBe != null) {
                            level.setBlockEntity(newBe);
                        }
                    }

                    if (newState.getBlock() instanceof AbstractBogeyBlock<?>) {
                        placedBogeys.add(newPos);
                    }
                }
            }
        }
        return placedBogeys;
    }

    public static void purgeSubLevel(ServerLevel level, ServerSubLevel subLevel) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) return;

        // Remove the wheel/body joint before the bodies disappear from the pipeline.
        SableTrainController.forget(subLevel);

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos plotPos = new BlockPos(x, y, z);
                    level.removeBlockEntity(plotPos);
                    if (!level.getBlockState(plotPos).isAir()) {
                        level.setBlock(plotPos, Blocks.AIR.defaultBlockState(), 0);
                    }
                }
            }
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }

    private static void restoreGlue(ServerLevel level, StructureTransform transform, List<AABB> boxes) {
        for (AABB local : boxes) {
            Vec3[] corners = new Vec3[]{
                    new Vec3(local.minX, local.minY, local.minZ),
                    new Vec3(local.minX, local.minY, local.maxZ),
                    new Vec3(local.minX, local.maxY, local.minZ),
                    new Vec3(local.minX, local.maxY, local.maxZ),
                    new Vec3(local.maxX, local.minY, local.minZ),
                    new Vec3(local.maxX, local.minY, local.maxZ),
                    new Vec3(local.maxX, local.maxY, local.minZ),
                    new Vec3(local.maxX, local.maxY, local.maxZ)
            };

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (Vec3 corner : corners) {
                Vec3 world = transform.apply(corner);
                minX = Math.min(minX, world.x);
                minY = Math.min(minY, world.y);
                minZ = Math.min(minZ, world.z);
                maxX = Math.max(maxX, world.x);
                maxY = Math.max(maxY, world.y);
                maxZ = Math.max(maxZ, world.z);
            }

            level.addFreshEntity(new SuperGlueEntity(level, new AABB(minX, minY, minZ, maxX, maxY, maxZ)));
        }
    }

    /**
     * Puts the original bogey blocks back where the invisible bogey blocks were placed.
     *
     * @return true if the sub-level carried bogey entity data and the blocks were restored
     */
    private static boolean restoreBogeyBlocks(ServerLevel level, ServerSubLevel subLevel,
                                              StructureTransform transform) {
        var bogies = SableTrainController.bogiesFor(subLevel);
        if (bogies.isEmpty()) return false;

        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        for (var info : bogies) {
            Vector3d center = info.localPlotCenter();
            BlockPos plotPos = BlockPos.containing(center.x, center.y, center.z);
            BlockPos worldPos = transform.apply(plotPos.subtract(plotAnchor));

            level.setBlock(worldPos, transform.apply(info.originalState()), Block.UPDATE_ALL);
            if (level.getBlockEntity(worldPos) instanceof AbstractBogeyBlockEntity bogey) {
                bogey.setBogeyData(info.bogeyData().copy());
            }
        }
        return true;
    }

    private static void restoreBogeyData(ServerLevel level, Carriage carriage, List<BlockPos> placed,
                                         Direction assemblyDirection) {
        if (placed.isEmpty()) return;

        List<CarriageBogey> bogeys = new ArrayList<>(2);
        if (carriage.bogeys.getFirst() != null) bogeys.add(carriage.bogeys.getFirst());
        if (carriage.bogeys.getSecond() != null) bogeys.add(carriage.bogeys.getSecond());
        if (bogeys.isEmpty()) return;

        net.minecraft.core.Direction.Axis axis = assemblyDirection.getAxis();
        placed.sort((a, b) -> Integer.compare(VecHelper.getCoordinate(a, axis), VecHelper.getCoordinate(b, axis)));

        boolean leadingFirst = true;
        CarriageBogey leading = carriage.leadingBogey();
        CarriageBogey trailing = carriage.trailingBogey();
        var leadingPos = leading.getAnchorPosition();
        var trailingPos = trailing != null ? trailing.getAnchorPosition() : null;
        if (leadingPos != null && trailingPos != null) {
            leadingFirst = VecHelper.getCoordinate(leadingPos, axis)
                    <= VecHelper.getCoordinate(trailingPos, axis);
        }
        if (!leadingFirst && bogeys.size() == 2) {
            bogeys = List.of(bogeys.get(1), bogeys.get(0));
        }

        for (int i = 0; i < Math.min(placed.size(), bogeys.size()); i++) {
            BlockEntity be = level.getBlockEntity(placed.get(i));
            if (be instanceof AbstractBogeyBlockEntity bogey) {
                bogey.setBogeyData(bogeys.get(i).bogeyData);
            }
        }
    }
}
