package ru.stepan1411.sablenautics.trains;

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

/**
 * Ticks every Sable train sub-level on every server tick, even when its chunks are
 * unloaded. Without this, a train stops moving the moment its chunks unload because
 * {@link SubLevel#tick()} (which drives {@link SableTrainController#tickSubLevel}) only
 * runs for sub-levels in loaded chunks. Create's {@code GlobalRailwayManager} still ticks
 * the train's navigation in unloaded chunks, advancing its travelling points, but the
 * Sable sub-level bodies would lag behind and detach on reload.
 * <p>
 * This replaces the {@link ru.stepan1411.sablenautics.mixin.trains.SubLevelMixin} hook on
 * {@code SubLevel#tick()} so each sub-level is ticked exactly once per tick regardless of
 * chunk loading state.
 */
@EventBusSubscriber(modid = Sablenautics.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class SableTrainTicker {

    private static final String TRAIN_KEY = "sablenautics_train";

    @SubscribeEvent
    public static void onServerTick(Post event) {
        MinecraftServer server = event.getServer();
        if (server == null)
            return;

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
    }
}
