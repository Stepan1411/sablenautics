package ru.stepan1411.sablenautics.client;

import com.simibubi.create.AllItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

/**
 * Client-side half of the wrench suspension interaction: opens Create's value settings UI
 * after the wrench click is released, so the initial button release does not immediately
 * confirm the unchanged value.
 */
@EventBusSubscriber(modid = Sablenautics.MOD_ID, value = Dist.CLIENT)
public final class ClientSuspensionHeightEvents {

    private static BlockPos pendingPos;
    private static float pendingHeight;

    private ClientSuspensionHeightEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!AllItems.WRENCH.isIn(event.getItemStack())) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SableBogeyBlockEntity bogey)) return;

        pendingPos = event.getPos();
        pendingHeight = bogey.getSuspensionOffset();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingPos == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            pendingPos = null;
            return;
        }
        if (minecraft.options.keyUse.isDown()) return;

        BlockPos pos = pendingPos;
        float height = pendingHeight;
        pendingPos = null;
        ClientSuspensionHeightScreen.open(pos, height);
    }
}
