package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.track.FakeTrackBlock;
import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wrench-based relocation for Sable trains.
 * <p>
 * Create's own relocation needs a {@code CarriageContraptionEntity}; Sable carriages do not have
 * one. This controller reproduces the interaction on the server: right-click a Sable carriage
 * with a wrench to select the train, then right-click any rail to place it there.
 */
public final class SableTrainRelocation {

    private static final long TIMEOUT_TICKS = 30L * 20L;

    private static final Map<UUID, PendingRelocation> PENDING = new HashMap<>();

    private record PendingRelocation(UUID trainId, long startedTick) {
    }

    private SableTrainRelocation() {
    }

    public static boolean onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return false;
        if (!(event.getLevel() instanceof ServerLevel level)) return false;
        if (!(event.getEntity() instanceof ServerPlayer player)) return false;
        if (player.isSpectator()) return false;

        ItemStack stack = player.getItemInHand(event.getHand());
        if (!AllItems.WRENCH.isIn(stack)) return false;

        long now = level.getGameTime();
        PendingRelocation pending = PENDING.get(player.getUUID());
        if (pending != null && now - pending.startedTick() > TIMEOUT_TICKS) {
            PENDING.remove(player.getUUID());
            pending = null;
        }

        BlockPos pos = event.getPos();
        BlockState clickedState = level.getBlockState(pos);
        boolean clickedTrack = clickedState.getBlock() instanceof ITrackBlock
                || clickedState.getBlock() instanceof FakeTrackBlock;

        if (pending != null && clickedTrack) {
            if (player.isShiftKeyDown()) {
                PENDING.remove(player.getUUID());
                player.displayClientMessage(Component.translatable("sablenautics.relocate.cancelled"), true);
                return true;
            }

            Train train = Create.RAILWAYS.trains.get(pending.trainId());
            if (train == null) {
                PENDING.remove(player.getUUID());
                player.displayClientMessage(Component.translatable("sablenautics.relocate.no_train"), true);
                return true;
            }

            if (!player.canInteractWithBlock(pos, 32)) {
                player.displayClientMessage(Component.translatable("sablenautics.relocate.too_far"), true);
                return true;
            }

            // Keep the selection if the placement failed, so the player can try another rail.
            boolean moved = SableTrainController.relocateToTrackBlock(train, level, pos, player.getLookAngle());
            if (moved) {
                PENDING.remove(player.getUUID());
                player.displayClientMessage(Component.translatable("sablenautics.relocate.success",
                        train.name.copy().withStyle(ChatFormatting.GREEN)), true);
            } else {
                player.displayClientMessage(Component.translatable("sablenautics.relocate.invalid")
                        .withStyle(ChatFormatting.RED), true);
            }
            cancel(event);
            return true;
        }

        // Clicking a Sable carriage with a wrench selects it for the next rail click.
        if (player.isShiftKeyDown()) return false;

        SableTrainController.SubLevelData data = SableTrainController.dataAt(level, pos);
        if (data == null) {
            // Clicking a small or non-collidable block can miss the sub-level lookup;
            // fall back to the closest Sable carriage body.
            data = SableTrainController.dataNear(level, net.minecraft.world.phys.Vec3.atCenterOf(pos), 6.0);
        }
        if (data == null) return false;

        Train train = Create.RAILWAYS.trains.get(data.trainId());
        if (train == null) return false;

        if (pending != null && pending.trainId().equals(train.id)) {
            PENDING.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("sablenautics.relocate.cancelled"), true);
            cancel(event);
            return true;
        }

        PENDING.put(player.getUUID(), new PendingRelocation(train.id, now));
        player.displayClientMessage(Component.translatable("sablenautics.relocate.selected",
                train.name.copy().withStyle(ChatFormatting.GOLD)), true);
        cancel(event);
        return true;
    }

    public static void clear() {
        PENDING.clear();
    }

    private static void cancel(PlayerInteractEvent.RightClickBlock event) {
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
