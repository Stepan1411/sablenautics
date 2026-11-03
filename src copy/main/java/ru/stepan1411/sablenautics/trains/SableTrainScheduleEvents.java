package ru.stepan1411.sablenautics.trains;

import com.simibubi.create.AllItems;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.data.Couple;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Allows handing a {@code create:schedule} to an entity sitting on a seat of a Sable train,
 * and taking it back / resuming a paused schedule the same way as on normal Create trains.
 * <p>
 * Create's own interaction needs the entity's root vehicle to be a
 * {@code CarriageContraptionEntity}. Sable carriages use a retained {@link SeatEntity}
 * inside the sub-level instead, so the train has to be resolved from the seat position.
 */
public final class SableTrainScheduleEvents {

    private SableTrainScheduleEvents() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        Entity target = event.getTarget();
        Player player = event.getEntity();
        if (player == null || target == null || player.isSpectator()) return;
        if (!(target instanceof LivingEntity)) return;
        if (player.getCooldowns().isOnCooldown(AllItems.SCHEDULE.get())) return;
        if (!(target.getRootVehicle() instanceof SeatEntity seat)) return;

        // The state change is authoritative on the server; the client will receive the result.
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        SableTrainController.SubLevelData data = SableTrainController.dataAt(level, seat.blockPosition());
        if (data == null) return;
        Train train = com.simibubi.create.Create.RAILWAYS.trains.get(data.trainId());
        if (train == null) return;

        BlockPos plotAnchor = data.subLevel().getPlot().getCenterBlock();
        BlockPos localSeat = seat.blockPosition().subtract(plotAnchor);
        Couple<Boolean> sides = data.conductorSidesAt(localSeat);

        ItemStack stack = event.getItemStack();
        if (stack.getItem() instanceof ScheduleItem scheduleItem) {
            Schedule schedule = ScheduleItem.getSchedule(player.registryAccess(), stack);
            if (schedule == null) return;

            if (sides == null) {
                deny(player, "schedule.non_controlling_seat");
                finish(event, player);
                return;
            }
            if (train.runtime.getSchedule() != null) {
                deny(player, "schedule.remove_with_empty_hand");
                finish(event, player);
                return;
            }
            if (schedule.entries.isEmpty()) {
                deny(player, "schedule.no_stops");
                finish(event, player);
                return;
            }

            train.runtime.setSchedule(schedule, false);
            AllAdvancements.CONDUCTOR.awardTo(player);
            AllSoundEvents.CONFIRM.playOnServer(level, player.blockPosition(), 1, 1);
            player.displayClientMessage(CreateLang.translateDirect("schedule.applied_to_train")
                    .withStyle(ChatFormatting.GREEN), true);

            stack.shrink(1);
            player.setItemInHand(event.getHand(), stack.isEmpty() ? ItemStack.EMPTY : stack);
            finish(event, player);
            return;
        }

        if (event.getHand() == InteractionHand.OFF_HAND) return;
        if (train.runtime.getSchedule() == null || sides == null) return;

        if (train.runtime.paused && !train.runtime.completed) {
            train.runtime.paused = false;
            AllSoundEvents.CONFIRM.playOnServer(level, player.blockPosition(), 1, 1);
            player.displayClientMessage(CreateLang.translateDirect("schedule.continued"), true);
            finish(event, player);
            return;
        }

        if (!stack.isEmpty()) {
            deny(player, "schedule.remove_with_empty_hand");
            finish(event, player);
            return;
        }

        AllSoundEvents.playItemPickup(player);
        player.displayClientMessage(CreateLang.translateDirect(
                train.runtime.isAutoSchedule ? "schedule.auto_removed_from_train"
                        : "schedule.removed_from_train"), true);
        player.getInventory().placeItemBackInInventory(train.runtime.returnSchedule(player.registryAccess()));
        finish(event, player);
    }

    /**
     * Same interaction for a Blaze Burner block placed next to the train controls. Create
     * normally routes this through {@code ConductorBlockInteractionBehavior}, which also
     * requires a {@code CarriageContraptionEntity}.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Player player = event.getEntity();
        if (player == null || player.isSpectator()) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BlazeBurnerBlock)) return;
        if (!state.hasProperty(BlazeBurnerBlock.HEAT_LEVEL)) return;

        SableTrainController.SubLevelData data = SableTrainController.dataAt(level, pos);
        if (data == null) return;
        if (!SableTrainController.isConductorAdjacent(level, pos, data)) return;

        Train train = com.simibubi.create.Create.RAILWAYS.trains.get(data.trainId());
        if (train == null) return;

        ItemStack stack = event.getItemStack();

        if (train.runtime.getSchedule() != null) {
            if (train.runtime.paused && !train.runtime.completed) {
                train.runtime.paused = false;
                AllSoundEvents.CONFIRM.playOnServer(level, player.blockPosition(), 1, 1);
                player.displayClientMessage(CreateLang.translateDirect("schedule.continued"), true);
                finish(event, player);
                return;
            }

            if (!stack.isEmpty()) {
                deny(player, "schedule.remove_with_empty_hand");
                finish(event, player);
                return;
            }

            AllSoundEvents.playItemPickup(player);
            player.displayClientMessage(CreateLang.translateDirect(
                    train.runtime.isAutoSchedule ? "schedule.auto_removed_from_train"
                            : "schedule.removed_from_train"), true);
            player.setItemInHand(event.getHand(), train.runtime.returnSchedule(player.registryAccess()));
            finish(event, player);
            return;
        }

        if (!(stack.getItem() instanceof ScheduleItem)) return;

        Schedule schedule = ScheduleItem.getSchedule(player.registryAccess(), stack);
        if (schedule == null) return;
        if (schedule.entries.isEmpty()) {
            deny(player, "schedule.no_stops");
            finish(event, player);
            return;
        }

        // A level-1 Blaze Burner can drive the train but does not heat anything above it.
        SableTrainController.promoteBlazeBurner(level, pos, state);
        SableTrainController.refreshConductors(data);
        train.runtime.setSchedule(schedule, false);
        AllAdvancements.CONDUCTOR.awardTo(player);
        AllSoundEvents.CONFIRM.playOnServer(level, player.blockPosition(), 1, 1);
        player.displayClientMessage(CreateLang.translateDirect("schedule.applied_to_train")
                .withStyle(ChatFormatting.GREEN), true);

        stack.shrink(1);
        player.setItemInHand(event.getHand(), stack.isEmpty() ? ItemStack.EMPTY : stack);
        finish(event, player);
    }

    private static void deny(Player player, String key) {
        AllSoundEvents.DENY.playOnServer(player.level(), player.blockPosition(), 1, 1);
        player.displayClientMessage(CreateLang.translateDirect(key), true);
    }

    private static void finish(PlayerInteractEvent.EntityInteractSpecific event, Player player) {
        finishCooldown(player);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void finish(PlayerInteractEvent.RightClickBlock event, Player player) {
        finishCooldown(player);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void finishCooldown(Player player) {
        player.getCooldowns().addCooldown(AllItems.SCHEDULE.get(), 5);
    }
}
