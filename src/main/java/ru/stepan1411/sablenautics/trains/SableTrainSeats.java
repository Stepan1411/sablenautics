package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.Train;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * Keeps Create's seat entities together with a carriage sub-level.
 * <p>
 * Normally Create moves the passenger from the world {@link SeatEntity} onto the
 * {@code CarriageContraptionEntity}. Sable carriages have no such entity, so the seat
 * entity would stay in the world, see no seat block below it and eject its passenger.
 * Instead, the seat entity is moved into the sub-level plot (and restored to the world on
 * disassembly). The seat entity type is listed in {@code sable:retain_in_sub_level}, so
 * Sable leaves it inside the plot while the carriage moves.
 */
public final class SableTrainSeats {

    private SableTrainSeats() {
    }

    /**
     * Moves every occupied (and empty) seat of a freshly assembled carriage onto the
     * matching seat block inside the sub-level plot.
     *
     * @param anchor     the carriage anchor in the world
     * @param plotAnchor the anchor the carriage blocks were moved to in the plot
     */
    public static void moveIntoSubLevel(ServerLevel level, BlockPos anchor, BlockPos plotAnchor,
                                        Map<BlockPos, StructureBlockInfo> blocks) {
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            if (!AllBlockTags.SEATS.matches(entry.getValue().state())) continue;

            BlockPos local = entry.getKey();
            moveSeat(level, local.offset(anchor), local.offset(plotAnchor));
        }
    }

    /**
     * Moves a seat entity from the sub-level plot back onto its restored world block.
     */
    public static void restoreToWorld(ServerLevel level, BlockPos plotSeat, BlockPos worldSeat) {
        moveSeat(level, plotSeat, worldSeat);
    }

    private static void moveSeat(ServerLevel level, BlockPos from, BlockPos to) {
        List<SeatEntity> seats = level.getEntitiesOfClass(SeatEntity.class, new AABB(from));
        for (SeatEntity seat : seats) {
            seat.setPos(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            seat.setOldPosAndRot();
        }
    }

    /**
     * Counts all entities sitting on the train's seats, including entities riding a
     * passenger (e.g. a player riding a Blaze that sits on a seat).
     *
     * @return {@code -1} when the train has no Sable sub-levels, so Create's own
     *         passenger count should be used instead
     */
    public static int countSeatedPassengers(Train train) {
        boolean anySableCarriage = false;
        int count = 0;

        for (int i = 0; i < train.carriages.size(); i++) {
            ServerSubLevel subLevel = SableTrainController.subLevelFor(train, i);
            if (subLevel == null) continue;
            anySableCarriage = true;

            if (!(subLevel.getLevel() instanceof ServerLevel level)) continue;
            BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
            if (bounds == null) continue;

            for (SeatEntity seat : level.getEntitiesOfClass(SeatEntity.class, bounds.toAABB())) {
                for (Entity passenger : seat.getIndirectPassengers()) {
                    if (passenger.isSpectator()) continue;
                    count++;
                }
            }
        }

        return anySableCarriage ? count : -1;
    }
}
