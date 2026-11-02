package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.*;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stepan1411.sablenautics.mixin.trains.ContraptionAccessor;

import java.util.*;

public class SableTrainAssembler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sablenautics");

    // Temporary storage for assembled sublevels before train is created.
    // Keyed by contraption, filled during phase 1 (assemble), consumed during phase 2 (registerTrain).
    // Insertion order matches Create's carriage order, so it can be used for carriage indexing.
    private static final Map<CarriageContraption, SubLevelData> PENDING_SUBLEVELS = new LinkedHashMap<>();

    private static class SubLevelData {
        final ServerSubLevel subLevel;
        final ServerSubLevel bogieSubLevel;
        final CarriageContraption contraption;
        List<AABB> superglue = List.of();
        SubLevelData(ServerSubLevel subLevel, ServerSubLevel bogieSubLevel, CarriageContraption contraption) {
            this.subLevel = subLevel;
            this.bogieSubLevel = bogieSubLevel;
            this.contraption = contraption;
        }
    }

    /**
     * Phase 1: Assemble blocks into a Sable sublevel BEFORE they are removed from world.
     * Called from mixin wrapping removeBlocksFromWorld.
     * Train doesn't exist yet — store sublevel for later registration.
     */
    public static void assemble(CarriageContraption contraption, Level level) {
        LOGGER.debug("Sablenautics: Start Sublevel assembly");

        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos anchor = contraption.anchor;
        Set<BlockPos> globalPosSet = new LinkedHashSet<>();
        Set<BlockPos> bogeyPosSet = new LinkedHashSet<>();

        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks == null) {
            LOGGER.warn("Sablenautics: contraption blocks is null");
            return;
        }

        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            BlockPos localPos = entry.getKey();
            BlockPos globalPos = localPos.offset(anchor);
            StructureBlockInfo info = entry.getValue();

            if (info.state().getBlock() instanceof AbstractBogeyBlock<?>) {
                bogeyPosSet.add(globalPos);
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

        // The wheels are a separate rail-bound sub-level. They keep their own block entities so
        // Create's bogey renderer can still draw the wheel model.
        ServerSubLevel bogieSublevel = null;
        if (!bogeyPosSet.isEmpty()) {
            SubLevel bogieLevel = SubLevelAssemblyHelper.assembleBlocks(serverLevel, anchor, bogeyPosSet, bounds);
            if (bogieLevel instanceof ServerSubLevel serverBogie) {
                bogieSublevel = serverBogie;
            } else {
                LOGGER.error("Sablenautics: Couldn't cast bogie sublevel to ServerSubLevel");
            }
        }

        // Make sure the controls model is open inside the plot. The same state was written into
        // the world before assembly, but block updates during the move may have reset it.
        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        for (BlockPos local : blocks.keySet()) {
            if (blocks.get(local).state().getBlock() instanceof AbstractBogeyBlock<?>) {
                continue;
            }
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

        // Store for phase 2 (train registration after train is created)
        PENDING_SUBLEVELS.put(contraption, new SubLevelData(serverSublevel, bogieSublevel, contraption));
        LOGGER.info("Sablenautics: Assembled Sable sublevel {} (bogie: {}) for contraption (stored for registration)",
                serverSublevel.getUniqueId(), bogieSublevel != null ? bogieSublevel.getUniqueId() : "none");

        LOGGER.debug("Sablenautics: End Sublevel assembly");
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
     * The train now exists in Create.RAILWAYS.trains.
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

            if (data == null && !PENDING_SUBLEVELS.isEmpty()) {
                Map.Entry<CarriageContraption, SubLevelData> entry =
                        PENDING_SUBLEVELS.entrySet().iterator().next();
                data = PENDING_SUBLEVELS.remove(entry.getKey());
            }
            if (data == null) continue;

            SableTrainController.registerSubLevel(train, data.subLevel, data.bogieSubLevel, data.contraption,
                    carriageIndex, data.superglue);
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
    }
}