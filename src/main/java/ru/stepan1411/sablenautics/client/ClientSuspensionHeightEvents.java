package ru.stepan1411.sablenautics.client;

import com.simibubi.create.AllItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

import java.util.Locale;

/**
 * Client-side half of the wrench suspension interaction: shows a small Create-style value
 * box while the wrench is held and opens the value-selection screen after a short hold.
 */
@EventBusSubscriber(modid = Sablenautics.MOD_ID, value = Dist.CLIENT)
public final class ClientSuspensionHeightEvents {

    private static final int OPEN_AFTER_TICKS = 5;

    private static BlockPos pendingPos;
    private static float pendingHeight;
    private static int holdTicks;

    private ClientSuspensionHeightEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!AllItems.WRENCH.isIn(event.getItemStack())) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SableBogeyBlockEntity bogey)) return;

        pendingPos = event.getPos();
        pendingHeight = bogey.getSuspensionOffset();
        holdTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingPos == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            clearPending();
            return;
        }
        if (minecraft.player == null
                || !AllItems.WRENCH.isIn(minecraft.player.getMainHandItem())) {
            clearPending();
            return;
        }

        if (minecraft.options.keyUse.isDown()) {
            holdTicks++;
            if (holdTicks > OPEN_AFTER_TICKS) {
                openPending();
            }
        } else {
            // A quick click also opens the screen; the player confirms with a second click.
            openPending();
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (pendingPos == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null
                || !minecraft.options.keyUse.isDown()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Component text = Component.translatable("sablenautics.suspension_height.hud",
                String.format(Locale.ROOT, "%+.2f", pendingHeight));

        int width = minecraft.font.width(text) + 14;
        int height = 18;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 72;

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF3B3B3B);
        graphics.fill(x, y, x + width, y + height, 0xE0181818);
        graphics.drawCenteredString(minecraft.font, text, graphics.guiWidth() / 2, y + 5, 0xFFFBDC7D);
    }

    private static void openPending() {
        BlockPos pos = pendingPos;
        float height = pendingHeight;
        clearPending();
        if (pos != null) {
            ClientSuspensionHeightScreen.open(pos, height);
        }
    }

    private static void clearPending() {
        pendingPos = null;
        holdTicks = 0;
    }
}
