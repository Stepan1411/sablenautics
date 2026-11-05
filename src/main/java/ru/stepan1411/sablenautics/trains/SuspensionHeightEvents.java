package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

/**
 * Server-side half of the wrench suspension interaction. Cancels the normal block use so
 * the client-side screen can handle the selection.
 */
public final class SuspensionHeightEvents {

    private SuspensionHeightEvents() {
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!AllItems.WRENCH.isIn(event.getItemStack())) return;

        BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof SableBogeyBlockEntity)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
