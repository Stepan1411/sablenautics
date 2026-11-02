package ru.stepan1411.sablenautics.trains;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Pre;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import ru.stepan1411.sablenautics.Sablenautics;

@EventBusSubscriber(modid = Sablenautics.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class SableTrainTicker {
    @SubscribeEvent
    public static void onServerTick(Pre event) {
        // No-op: train ticking is now done via SubLevelMixin (SubLevel.tick())
        // which runs during Sable's physics tick, like Ferronautics
    }
}