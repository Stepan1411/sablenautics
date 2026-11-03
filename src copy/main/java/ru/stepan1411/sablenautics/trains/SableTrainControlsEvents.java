package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Starts a Sable train control session when the player uses a Create train controls block
 * that lives inside a sub-level.
 */
public final class SableTrainControlsEvents {

    private SableTrainControlsEvents() {
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (AllItems.WRENCH.isIn(player.getItemInHand(event.getHand()))) return;

        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof ControlsBlock)) return;

        if (SableTrainControlManager.startSession(serverLevel, event.getPos(), player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            player.swing(event.getHand(), true);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SableTrainController.clear();
        SableTrainControlManager.clear();
        SableCarriageRegistry.clear();
        SableTrainAssembler.clear();
    }

    /**
     * Old saves (or other paths) may still contain carriage contraption entities.
     * Only Sable carriages must never enter the world; normal Create carriages are allowed.
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof CarriageContraptionEntity entity
                && SableTrainController.isSableCarriage(entity.getCarriage())) {
            event.setCanceled(true);
        }
    }
}
