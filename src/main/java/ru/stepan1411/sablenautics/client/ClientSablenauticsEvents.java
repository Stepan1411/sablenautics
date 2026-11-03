package ru.stepan1411.sablenautics.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterBlockEntityLayerRenderersEvent;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

@EventBusSubscriber(modid = Sablenautics.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSablenauticsEvents {

    private ClientSablenauticsEvents() {
    }

    @SubscribeEvent
    public static void registerBlockEntityRenderers(RegisterBlockEntityLayerRenderersEvent event) {
        event.registerBlockEntityRenderer(SableBogeyBlockEntity.class, SableBogeyBlockEntityRenderer::new);
    }
}
