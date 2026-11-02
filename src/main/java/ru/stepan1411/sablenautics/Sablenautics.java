package ru.stepan1411.sablenautics;

import com.simibubi.create.foundation.data.CreateRegistrate;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import ru.stepan1411.sablenautics.client.ClientSableTrainControls;
import ru.stepan1411.sablenautics.index.SablenauticsBlocks;
import ru.stepan1411.sablenautics.index.SablenauticsBlockEntityTypes;
import ru.stepan1411.sablenautics.network.SablenauticsNetwork;
import ru.stepan1411.sablenautics.trains.SableTrainCommands;
import ru.stepan1411.sablenautics.trains.SableTrainControlManager;
import ru.stepan1411.sablenautics.trains.SableTrainControlsEvents;

@Mod(Sablenautics.MOD_ID)
public class Sablenautics {
    public static final String MOD_ID = "sablenautics";

    private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public static CreateRegistrate getRegistrate() {
        return REGISTRATE;
    }

    public Sablenautics(final IEventBus modEventBus) {
        REGISTRATE.registerEventListeners(modEventBus);
        SablenauticsBlocks.init();
        SablenauticsBlockEntityTypes.init();

        modEventBus.addListener(SablenauticsNetwork::register);

        NeoForge.EVENT_BUS.addListener(SableTrainControlsEvents::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(SableTrainControlsEvents::onServerStopped);
        NeoForge.EVENT_BUS.addListener(SableTrainControlsEvents::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(SableTrainControlManager::serverTick);
        NeoForge.EVENT_BUS.addListener(SableTrainCommands::register);

        if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            NeoForge.EVENT_BUS.register(ClientSableTrainControls.class);
        }
    }
}
