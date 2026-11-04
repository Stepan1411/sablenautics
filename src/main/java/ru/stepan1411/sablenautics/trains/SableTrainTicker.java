package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import ru.stepan1411.sablenautics.Sablenautics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ticks every Sable train sub-level on every server tick, even when its chunks are
 * unloaded. Without this, a train stops moving the moment its chunks unload because
 * {@link SubLevel#tick()} (which drives {@link SableTrainController#tickSubLevel}) only
 * runs for sub-levels in loaded chunks. Create's {@code GlobalRailwayManager} still ticks
 * the train's navigation in unloaded chunks, advancing its travelling points, but the
 * Sable sub-level bodies would lag behind and detach on reload.
 * <p>
 * Additionally, this loads chunks around moving trains so they can travel through
 * unloaded areas, and stops loading them 30 seconds after the train stops so chunks
 * can unload naturally.
 */
@EventBusSubscriber(modid = Sablenautics.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class SableTrainTicker {

    private static final String TRAIN_KEY = "sablenautics_train";

    /** Chunk radius around each carriage to load while moving. */
    private static final int LOAD_RADIUS = 2;
    /** Ticks after which chunk loading stops when the train is stationary (30 seconds). */
    private static final int UNLOAD_AFTER_TICKS = 30 * 20;

    /** Last game tick each train was observed moving (non-zero speed). */
    private static final Map<UUID, Long> LAST_MOVE_TICK = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(Post event) {
        MinecraftServer server = event.getServer();
        if (server == null)
            return;

        long now = server.getTickCount();

        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null)
                continue;

            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (!(subLevel instanceof ServerSubLevel serverSubLevel))
                    continue;
                if (subLevel.isRemoved())
                    continue;

                CompoundTag userData = serverSubLevel.getUserDataTag();
                if (userData == null || !userData.contains(TRAIN_KEY))
                    continue;

                SableTrainController.tickSubLevel(subLevel, level);
            }
        }

        // Handle chunk loading for trains
        for (Train train : Create.RAILWAYS.trains.values()) {
            updateTrainChunkLoading(server, train, now);
        }

        // Periodically remove orphan sub-levels and invisible Sable trains before other
        // trains can collide with them.
        if (now % 20 == 0) {
            SableTrainController.validateServer(server);
        }
        if (now % 200 == 0) {
            SableTrainController.discardPhantomEntities(server);
        }

        // Clean up trains that no longer exist
        LAST_MOVE_TICK.keySet().removeIf(uuid -> !Create.RAILWAYS.trains.containsKey(uuid));
    }

    /**
     * Loads chunks around a moving train so it can travel through unloaded areas.
     * Releases chunks 30 seconds after the train stops.
     */
    private static void updateTrainChunkLoading(MinecraftServer server, Train train, long now) {
        UUID trainId = train.id;
        boolean moving = Math.abs(train.speed) > 0.001;

        if (moving) {
            LAST_MOVE_TICK.put(trainId, now);
            loadChunksAroundTrain(server, train);
            return;
        }

        Long lastMove = LAST_MOVE_TICK.get(trainId);
        if (lastMove == null) return;

        if (now - lastMove > UNLOAD_AFTER_TICKS) {
            LAST_MOVE_TICK.remove(trainId);
            return;
        }

        // Keep chunks loaded for a short while after a Sable train derailed so it can settle
        // and find a nearby track to re-attach to before its bodies leave simulation range.
        if (train.derailed && SableTrainController.isSableTrain(train)) {
            loadChunksAroundTrain(server, train);
        }
    }

    /**
     * Loads chunks around every carriage of the train so long trains can travel through
     * unloaded areas without their rear carriages lagging behind and tearing off.
     */
    private static void loadChunksAroundTrain(MinecraftServer server, Train train) {
        for (int i = 0; i < train.carriages.size(); i++) {
            var subLevel = SableTrainController.subLevelFor(train, i);
            if (subLevel == null || subLevel.isRemoved()) continue;

            ServerLevel level = subLevel.getLevel();
            var pose = subLevel.logicalPose();
            int cx = chunkCoord((int) pose.position().x());
            int cz = chunkCoord((int) pose.position().z());

            for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
                for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) {
                    level.getChunk(cx + dx, cz + dz);
                }
            }
        }
    }

    private static int chunkCoord(int blockCoord) {
        return blockCoord >> 4;
    }
}
