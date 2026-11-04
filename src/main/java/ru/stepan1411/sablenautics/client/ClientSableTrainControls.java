package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.HonkPacket;
import com.simibubi.create.content.trains.TrainHUD;
import com.simibubi.create.content.trains.TrainHUDUpdatePacket;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.utility.ControlsUtil;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.createmod.catnip.placement.PlacementClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;
import ru.stepan1411.sablenautics.network.SablenauticsNetwork;
import ru.stepan1411.sablenautics.network.TrainControlStartPayload;
import ru.stepan1411.sablenautics.network.TrainControlStatusPayload;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Self-contained replacement for Create's {@code ControlsHandler}/{@code TrainHUD} pair.
 * No carriage entity is involved: input goes straight to the server and the HUD reads the
 * client-side {@link Train} kept up to date by {@link TrainHUDUpdatePacket}.
 */
public final class ClientSableTrainControls {
    private static final LerpedFloat DISPLAYED_SPEED = LerpedFloat.linear();
    private static final LerpedFloat DISPLAYED_THROTTLE = LerpedFloat.linear();
    private static final LerpedFloat DISPLAYED_PROMPT_SIZE = LerpedFloat.linear();

    private static UUID trainId;
    private static int carriageIndex;
    private static boolean inverted;
    private static boolean derailed;
    private static float trainYaw;

    private static final Set<Integer> PRESSED = new HashSet<>();
    private static int lastMask;
    private static int inputCooldown;
    private static int throttleCooldown;
    private static double editedThrottle = -1;
    private static boolean usedToHonk;

    private ClientSableTrainControls() {
    }

    public static void start(TrainControlStartPayload payload) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        trainId = payload.trainId();
        carriageIndex = payload.carriageIndex();
        inverted = payload.inverted();
        derailed = false;
        trainYaw = 0.0f;
        PRESSED.clear();
        lastMask = 0;
        inputCooldown = 0;
        throttleCooldown = 0;
        editedThrottle = -1;
        usedToHonk = false;

        Train train = train();
        Component name = train != null ? train.name : Component.translatable("train");
        player.displayClientMessage(
                CreateLang.translateDirect("contraption.controls.start_controlling", name), true);
    }

    public static void stop() {
        if (trainId == null) return;
        reset();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(
                    CreateLang.translateDirect("contraption.controls.stop_controlling"), true);
        }
    }

    private static void reset() {
        trainId = null;
        derailed = false;
        trainYaw = 0.0f;
        PRESSED.clear();
        editedThrottle = -1;
        usedToHonk = false;
        lastMask = 0;
        inputCooldown = 0;
        throttleCooldown = 0;
    }

    public static void applyStatus(TrainControlStatusPayload payload) {
        if (trainId == null || !trainId.equals(payload.trainId())) return;
        derailed = payload.derailed();
        trainYaw = payload.yaw();
    }

    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void requestStop() {
        if (trainId != null) {
            SablenauticsNetwork.requestStop(trainId);
        }
        stop();
    }

    private static Train train() {
        if (trainId == null) return null;
        return Create.RAILWAYS.sided(null).trains.get(trainId);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (trainId == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            stop();
            return;
        }

        Train train = train();

        if (InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            requestStop();
            return;
        }

        List<KeyMapping> controls = ControlsUtil.getControls();
        Set<Integer> pressed = new HashSet<>();
        for (int i = 0; i < controls.size(); i++) {
            if (ControlsUtil.isActuallyPressed(controls.get(i))) {
                pressed.add(i);
            }
        }

        if (pressed.contains(5)) {
            requestStop();
            return;
        }

        int mask = 0;
        for (int index : pressed) mask |= 1 << index;

        if (inputCooldown > 0) inputCooldown--;
        if (inputCooldown == 0 || mask != lastMask) {
            SablenauticsNetwork.sendInput(trainId, mask);
            lastMask = mask;
            inputCooldown = 5;
        }

        PRESSED.clear();
        PRESSED.addAll(pressed);

        // Intercept movement keys exactly like Create's controls handler.
        controls.forEach(key -> key.setDown(false));

        if (train == null) {
            // The client-side train may not have been synced yet; keep the session and wait,
            // but keep intercepting movement keys.
            return;
        }

        boolean sprinting = ControlsUtil.isActuallyPressed(minecraft.options.keySprint);
        if (sprinting && !usedToHonk) {
            CatnipServices.NETWORK.sendToServer(new HonkPacket.Serverbound(train, true));
            usedToHonk = true;
        } else if (!sprinting && usedToHonk) {
            CatnipServices.NETWORK.sendToServer(new HonkPacket.Serverbound(train, false));
            usedToHonk = false;
        }

        if (editedThrottle >= 0 && throttleCooldown-- <= 0) {
            CatnipServices.NETWORK.sendToServer(new TrainHUDUpdatePacket.Serverbound(train, editedThrottle));
            throttleCooldown = 5;
            if (Mth.equal(editedThrottle, train.throttle)) {
                editedThrottle = -1;
            }
        }

        tickHud(train);
    }

    private static void tickHud(Train train) {
        Minecraft minecraft = Minecraft.getInstance();
        double value = Math.abs(train.speed)
                / (train.maxSpeed() * AllConfigs.server().trains.manualTrainSpeedModifier.getF());
        value = Mth.clamp(value + 0.05f, 0, 1);

        DISPLAYED_SPEED.chase((int) (value * 18) / 18f, .5f, Chaser.EXP);
        DISPLAYED_SPEED.tickChaser();
        DISPLAYED_THROTTLE.chase(editedThrottle >= 0 ? editedThrottle : train.throttle, .75f, Chaser.EXP);
        DISPLAYED_THROTTLE.tickChaser();

        DISPLAYED_PROMPT_SIZE.chase(TrainHUD.currentPrompt != null
                ? minecraft.font.width(TrainHUD.currentPrompt) + 17 : 0, .5f, Chaser.EXP);
        DISPLAYED_PROMPT_SIZE.tickChaser();
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (trainId == null) return;
        if (Minecraft.getInstance().screen != null) return;
        Train train = train();
        if (train == null) return;

        double previous = editedThrottle >= 0 ? editedThrottle : train.throttle;
        editedThrottle = Mth.clamp(previous + (event.getScrollDeltaY() > 0 ? 1 : -1) / 18f, 1 / 18f, 1);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (trainId == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.gameMode == null
                || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }
        Train train = train();
        if (train == null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        DeltaTracker delta = event.getPartialTick();
        float partial = delta.getGameTimeDeltaPartialTick(false);

        graphics.pose().pushPose();
        graphics.pose().translate(graphics.guiWidth() / 2 - 91, graphics.guiHeight() - 29, 0);

        AllGuiTextures.TRAIN_HUD_FRAME.render(graphics, -2, 1);
        AllGuiTextures.TRAIN_HUD_SPEED_BG.render(graphics, 0, 0);

        int width = (int) (AllGuiTextures.TRAIN_HUD_SPEED.getWidth() * DISPLAYED_SPEED.getValue(partial));
        int height = AllGuiTextures.TRAIN_HUD_SPEED.getHeight();
        graphics.blit(AllGuiTextures.TRAIN_HUD_SPEED.location, 0, 0, 0,
                AllGuiTextures.TRAIN_HUD_SPEED.getStartX(), AllGuiTextures.TRAIN_HUD_SPEED.getStartY(),
                width, height, 256, 256);

        int promptSize = (int) DISPLAYED_PROMPT_SIZE.getValue(partial);
        if (promptSize > 1 && TrainHUD.currentPrompt != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(promptSize / -2f + 91, -27, 0);
            AllGuiTextures.TRAIN_PROMPT_L.render(graphics, -3, 0);
            AllGuiTextures.TRAIN_PROMPT_R.render(graphics, promptSize, 0);
            graphics.blit(AllGuiTextures.TRAIN_PROMPT.location, 0, 0, 0,
                    AllGuiTextures.TRAIN_PROMPT.getStartX() + (128 - promptSize / 2f),
                    AllGuiTextures.TRAIN_PROMPT.getStartY(), promptSize,
                    AllGuiTextures.TRAIN_PROMPT.getHeight(), 256, 256);
            graphics.pose().popPose();

            if (minecraft.font.width(TrainHUD.currentPrompt) < promptSize - 10) {
                graphics.pose().pushPose();
                graphics.pose().translate(
                        minecraft.font.width(TrainHUD.currentPrompt) / -2f + 82, -27, 100);
                if (TrainHUD.currentPromptShadow) {
                    graphics.drawString(minecraft.font, TrainHUD.currentPrompt, 9, 4, 0x544D45);
                } else {
                    graphics.drawString(minecraft.font, TrainHUD.currentPrompt, 9, 4, 0x544D45, false);
                }
                graphics.pose().popPose();
            }
        }

        width = (int) (AllGuiTextures.TRAIN_HUD_THROTTLE.getWidth()
                * (1 - DISPLAYED_THROTTLE.getValue(partial)));
        int invWidth = AllGuiTextures.TRAIN_HUD_THROTTLE.getWidth() - width;
        graphics.blit(AllGuiTextures.TRAIN_HUD_THROTTLE.location, invWidth, 0, 0,
                AllGuiTextures.TRAIN_HUD_THROTTLE.getStartX() + invWidth,
                AllGuiTextures.TRAIN_HUD_THROTTLE.getStartY(), width, height, 256, 256);
        AllGuiTextures.TRAIN_HUD_THROTTLE_POINTER.render(graphics,
                Math.max(1, AllGuiTextures.TRAIN_HUD_THROTTLE.getWidth() - width) - 3, -2);

        // Direction arrow, ported from Create's TrainHUD.
        AllGuiTextures.TRAIN_HUD_DIRECTION.render(graphics, 77, -20);
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity != null) {
            boolean reversing = PRESSED.contains(1);
            boolean arrowInverted = inverted ^ reversing;
            int angleOffset = (PRESSED.contains(2) ? -45 : 0) + (PRESSED.contains(3) ? 45 : 0);
            if (reversing) {
                angleOffset *= -1;
            }

            float snapSize = 22.5f;
            float diff = AngleHelper.getShortestAngleDiff(cameraEntity.getYRot(), trainYaw)
                    + (arrowInverted ? -90 : 90);
            if (Math.abs(diff) < 60) {
                diff = 0;
            }

            float angle = diff + angleOffset;
            float snappedAngle = (snapSize * Math.round(angle / snapSize)) % 360f;

            graphics.pose().pushPose();
            graphics.pose().translate(91, -9, 0);
            graphics.pose().scale(0.925f, 0.925f, 1);
            PlacementClient.textured(graphics.pose(), 0, 0, 1, snappedAngle);
            graphics.pose().popPose();
        }

        // While derailed the controls remain open, but the HUD shows a wrench hint instead
        // of the old chat message.
        if (derailed) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.renderItem(new ItemStack(AllItems.WRENCH.get()), 91 - 8, -45);
            graphics.pose().popPose();
        }

        graphics.pose().popPose();
    }

    public static boolean isControlling() {
        return trainId != null;
    }

    public static int getCarriageIndex() {
        return carriageIndex;
    }

    public static boolean isInverted() {
        return inverted;
    }
}
