package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
import ru.stepan1411.sablenautics.network.SablenauticsNetwork;

/**
 * Server-side half of the wrench suspension interaction: opens Create's value screen and
 * cancels the normal block use.
 */
public final class SuspensionHeightEvents {

    private SuspensionHeightEvents() {
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AllItems.WRENCH.isIn(event.getItemStack())) return;

        BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof SableBogeyBlockEntity bogey)) return;

        SablenauticsNetwork.sendOpenSuspensionHeight(player, pos, bogey.getSuspensionOffset());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
