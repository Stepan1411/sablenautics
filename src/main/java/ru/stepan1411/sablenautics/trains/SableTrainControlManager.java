package ru.stepan1411.sablenautics.trains;

import com.google.common.base.Strings;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.trains.TrainHUDUpdatePacket;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.platform.CatnipServices;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stepan1411.sablenautics.network.SablenauticsNetwork;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Complete replacement for Create's {@code CarriageContraptionEntity} control path.
 * <p>
 * Input, HUD updates and the actual train control math live here, decoupled from any entity.
 */
public final class SableTrainControlManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sablenautics");
    private static final int HUD_INTERVAL = 5;
    private static final int INPUT_TIMEOUT = 10;

    private static final Map<UUID, Session> BY_TRAIN = new HashMap<>();
    private static final Map<UUID, UUID> PLAYER_TRAIN = new HashMap<>();

    private static final class Session {
        final UUID trainId;
        final UUID playerId;
        final int carriageIndex;
        final boolean inverted;
        final ServerSubLevel subLevel;
        final net.minecraft.core.BlockPos plotPos;
        final long startedTick;

        int mask;
        long lastInputTick;
        int hudCooldown;
        double navDistanceTotal;

        Session(UUID trainId, UUID playerId, int carriageIndex, boolean inverted, ServerSubLevel subLevel,
                net.minecraft.core.BlockPos plotPos, long startedTick) {
            this.trainId = trainId;
            this.playerId = playerId;
            this.carriageIndex = carriageIndex;
            this.inverted = inverted;
            this.subLevel = subLevel;
            this.plotPos = plotPos;
            this.startedTick = startedTick;
        }

        boolean pressed(int index) {
            return (mask & (1 << index)) != 0;
        }
    }

    private SableTrainControlManager() {
    }

    /**
     * @return true if the click started or stopped a control session
     */
    public static boolean startSession(ServerLevel level, net.minecraft.core.BlockPos plotPos, ServerPlayer player) {
        if (player.isSpectator()) return false;

        SableTrainController.SubLevelData data = SableTrainController.dataAt(level, plotPos);
        if (data == null) {
            LOGGER.debug("Sablenautics: no Sable train data at {}", plotPos);
            return false;
        }

        Train train = Create.RAILWAYS.trains.get(data.trainId());
        if (train == null) {
            LOGGER.info("Sablenautics: train {} for controls at {} is not loaded",
                    data.trainId(), plotPos);
            return false;
        }
        if (train.derailed) {
            player.displayClientMessage(Component.literal(
                    "Поезд сошёл с рельсов — разберите его на станции или очистите командой /sablenautics purge_trains"),
                    true);
            LOGGER.info("Sablenautics: train {} is derailed, controls refused", train.id);
            return false;
        }
        if (data.carriageIndex() < 0 || data.carriageIndex() >= train.carriages.size()) {
            LOGGER.debug("Sablenautics: invalid carriage index {} for train {}", data.carriageIndex(), train.id);
            return false;
        }

        BlockState state = level.getBlockState(plotPos);
        if (!(state.getBlock() instanceof ControlsBlock)) {
            LOGGER.debug("Sablenautics: clicked block at {} is not a train controls block", plotPos);
            return false;
        }

        boolean inverted = state.hasProperty(ControlsBlock.FACING)
                && !state.getValue(ControlsBlock.FACING).equals(data.assemblyDirection());

        long now = level.getGameTime();
        Session existing = BY_TRAIN.get(train.id);
        if (existing != null) {
            if (existing.playerId.equals(player.getUUID())) {
                if (now - existing.startedTick < 5) {
                    return true; // echo of the same click
                }
                stopSession(existing, level.getServer(), true);
                return true;
            }
            stopSession(existing, level.getServer(), true);
        }

        UUID previousTrain = PLAYER_TRAIN.get(player.getUUID());
        if (previousTrain != null && !previousTrain.equals(train.id)) {
            Session previous = BY_TRAIN.get(previousTrain);
            if (previous != null) stopSession(previous, level.getServer(), true);
        }

        // Same manual takeover Create performs in CarriageContraptionEntity#startControlling.
        if (train.runtime.getSchedule() != null && !train.runtime.paused) {
            train.status.manualControls();
        }
        train.navigation.cancelNavigation();
        train.runtime.paused = true;
        train.navigation.waitingForSignal = null;

        Session session = new Session(train.id, player.getUUID(), data.carriageIndex(), inverted,
                data.subLevel(), plotPos, now);
        session.lastInputTick = now;
        BY_TRAIN.put(train.id, session);
        PLAYER_TRAIN.put(player.getUUID(), train.id);

        SablenauticsNetwork.sendStartControl(player, train.id, data.carriageIndex(), inverted);
        LOGGER.info("Sablenautics: {} started controlling train {} via sublevel {}",
                player.getGameProfile().getName(), train.id, data.subLevel().getUniqueId());
        return true;
    }

    public static void handleInput(ServerPlayer player, UUID trainId, int mask) {
        Session session = BY_TRAIN.get(trainId);
        if (session == null || !session.playerId.equals(player.getUUID())) return;
        session.mask = mask;
        session.lastInputTick = player.serverLevel().getGameTime();
    }

    public static void stopForPlayer(ServerPlayer player, UUID trainId) {
        Session session = BY_TRAIN.get(trainId);
        if (session == null || !session.playerId.equals(player.getUUID())) return;
        stopSession(session, player.server, false);
    }

    public static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Iterator<Map.Entry<UUID, Session>> iterator = BY_TRAIN.entrySet().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next().getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            Train train = Create.RAILWAYS.trains.get(session.trainId);

            if (player == null || train == null) {
                iterator.remove();
                PLAYER_TRAIN.remove(session.playerId, session.trainId);
                if (player != null) {
                    SablenauticsNetwork.sendStopControl(player, session.trainId);
                }
                continue;
            }

            long now = player.serverLevel().getGameTime();
            if (now - session.lastInputTick > INPUT_TIMEOUT) {
                session.mask = 0;
            }

            if (!control(session, player, train, now)) {
                iterator.remove();
                PLAYER_TRAIN.remove(session.playerId, session.trainId);
                SablenauticsNetwork.sendStopControl(player, session.trainId);
                player.displayClientMessage(
                        CreateLang.translateDirect("contraption.controls.stop_controlling"), true);
            }
        }
    }

    private static boolean control(Session session, ServerPlayer player, Train train, long now) {
        if (player.isSpectator()) return false;
        if (train.derailed) return false;
        if (session.subLevel.isRemoved()) return false;
        Vec3 controlsWorld = SableTrainController.plotBlockCenter(session.subLevel, session.plotPos);
        if (player.position().distanceToSqr(controlsWorld) > 16 * 16) return false;
        if (session.pressed(5)) return false;

        if (session.hudCooldown-- <= 0) {
            CatnipServices.NETWORK.sendToClient(player, new TrainHUDUpdatePacket.Clientbound(train));
            session.hudCooldown = HUD_INTERVAL;
        }

        int targetSpeed = (session.pressed(0) ? 1 : 0) - (session.pressed(1) ? 1 : 0);
        int targetSteer = (session.pressed(2) ? 1 : 0) - (session.pressed(3) ? 1 : 0);
        if (session.inverted) {
            targetSpeed *= -1;
            targetSteer *= -1;
        }

        if (targetSpeed != 0) train.burnFuel();

        boolean slow = session.inverted ^ targetSpeed < 0;
        boolean spaceDown = session.pressed(4);

        GlobalStation currentStation = train.getCurrentStation();
        if (currentStation != null && spaceDown) {
            sendPrompt(player, CreateLang.translateDirect("train.arrived_at",
                    Component.literal(currentStation.name).withStyle(s -> s.withColor(0x704630))), false);
            return true;
        }

        if (train.speedBeforeStall != null && targetSpeed != 0
                && Math.signum(train.speedBeforeStall) != Math.signum(targetSpeed)) {
            train.cancelStall();
        }

        if (currentStation != null && targetSpeed != 0) {
            sendPrompt(player, CreateLang.translateDirect("train.departing_from",
                    Component.literal(currentStation.name).withStyle(s -> s.withColor(0x704630))), false);
        }

        if (currentStation == null) {
            Navigation nav = train.navigation;
            if (nav.destination != null) {
                if (!spaceDown) {
                    nav.cancelNavigation();
                    session.navDistanceTotal = 0;
                }
                if (spaceDown) {
                    if (session.navDistanceTotal > 0) {
                        double f = nav.distanceToDestination / session.navDistanceTotal;
                        int progress = (int) (Mth.clamp(1 - ((1 - f) * (1 - f)), 0, 1) * 30);
                        boolean arrived = progress == 0;
                        MutableComponent white = Component.literal(Strings.repeat("|", progress));
                        MutableComponent green = Component.literal(Strings.repeat("|", 30 - progress));
                        int mixed = Color.mixColors(0x00529915, 0x00FFC244, progress / 30f);
                        int target = arrived ? 0x00529915 : 0x00544D45;
                        sendPrompt(player, green.withStyle(st -> st.withColor(mixed))
                                .append(white.withStyle(st -> st.withColor(target))), true);
                    }
                    train.manualTick = true;
                    return true;
                }
            }

            double directedSpeed = targetSpeed != 0 ? targetSpeed : train.speed;
            GlobalStation lookAhead = nav.findNearestApproachable(
                    !train.doubleEnded || (directedSpeed != 0 ? directedSpeed > 0 : !session.inverted));
            if (lookAhead != null && spaceDown) {
                train.manualTick = true;
                nav.startNavigation(nav.findPathTo(lookAhead, -1));
                train.manualTick = false;
                session.navDistanceTotal = nav.distanceToDestination;
                return true;
            }
        }

        train.manualSteer = targetSteer < 0 ? SteerDirection.RIGHT
                : targetSteer > 0 ? SteerDirection.LEFT : SteerDirection.NONE;

        double topSpeed = train.maxSpeed() * AllConfigs.server().trains.manualTrainSpeedModifier.getF();
        double cappedTopSpeed = topSpeed * train.throttle;

        var carriage = train.carriages.get(session.carriageIndex);
        if (carriage.getLeadingPoint().edge != null && carriage.getLeadingPoint().edge.isTurn()
                || carriage.getTrailingPoint().edge != null && carriage.getTrailingPoint().edge.isTurn()) {
            topSpeed = train.maxTurnSpeed();
        }

        if (slow) topSpeed /= 4;
        train.targetSpeed = Math.min(topSpeed, cappedTopSpeed) * targetSpeed;

        boolean counteringAcceleration =
                Math.abs(Math.signum(targetSpeed) - Math.signum(train.speed)) > 1.5f;
        if (slow && !counteringAcceleration) train.backwardsDriver = player;

        train.manualTick = true;
        train.approachTargetSpeed(counteringAcceleration ? 2 : 1);
        return true;
    }

    private static void sendPrompt(ServerPlayer player, MutableComponent text, boolean shadow) {
        CatnipServices.NETWORK.sendToClient(player,
                new com.simibubi.create.content.trains.entity.TrainPromptPacket(text, shadow));
    }

    public static void stopForTrain(MinecraftServer server, UUID trainId) {
        Session session = BY_TRAIN.get(trainId);
        if (session == null) return;
        stopSession(session, server, true);
    }

    private static void stopSession(Session session, MinecraftServer server, boolean notify) {
        BY_TRAIN.remove(session.trainId, session);
        PLAYER_TRAIN.remove(session.playerId, session.trainId);
        if (notify && server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            if (player != null) {
                SablenauticsNetwork.sendStopControl(player, session.trainId);
            }
        }
    }

    public static void clear() {
        BY_TRAIN.clear();
        PLAYER_TRAIN.clear();
    }
}

