package ru.stepan1411.sablenautics.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.index.SablenauticsBlockEntityTypes;

@EventBusSubscriber(modid = Sablenautics.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSablenauticsEvents {

    private ClientSablenauticsEvents() {
    }

    @SubscribeEvent
    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SablenauticsBlockEntityTypes.SABLE_BOGEY.get(),
                SableBogeyBlockEntityRenderer::new);
    }
}
