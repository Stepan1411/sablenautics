package ru.stepan1411.sablenautics.client;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
import ru.stepan1411.sablenautics.network.SablenauticsNetwork;

import java.util.List;
import java.util.Locale;

/**
 * Create's value-selection screen (the one from the creative motor), configured with two
 * rows for positive and negative suspension heights.
 */
public class ClientSuspensionHeightScreen extends ValueSettingsScreen {

    private static final int STEPS_PER_BLOCK = 4;
    private static final float STEP = 1.0f / STEPS_PER_BLOCK;
    private static final int MAX_INDEX = Math.round(
            SableBogeyBlockEntity.MAX_SUSPENSION_OFFSET / STEP);

    private final BlockPos bogeyPos;
    private boolean ignoreFirstUseRelease;

    private ClientSuspensionHeightScreen(BlockPos pos, ValueSettingsBoard board, ValueSettings settings,
                                         boolean ignoreFirstUseRelease) {
        super(pos, board, settings, value -> {
        }, 0);
        this.bogeyPos = pos;
        this.ignoreFirstUseRelease = ignoreFirstUseRelease;
    }

    public static void open(BlockPos pos, float height) {
        open(pos, height, false);
    }

    public static void open(BlockPos pos, float height, boolean ignoreFirstUseRelease) {
        int row = height < 0.0f ? 1 : 0;
        int index = Mth.clamp(Math.round(Math.abs(height) / STEP), 0, MAX_INDEX);

        ValueSettingsBoard board = new ValueSettingsBoard(
                Component.translatable("sablenautics.suspension_height.gui"),
                MAX_INDEX,
                STEPS_PER_BLOCK,
                List.of(
                        Component.translatable("sablenautics.suspension_height.positive"),
                        Component.translatable("sablenautics.suspension_height.negative")),
                new ValueSettingsFormatter(settings -> {
                    float value = settings.value() * STEP;
                    if (settings.row() == 1) value = -value;
                    return Component.literal(String.format(Locale.ROOT, "%+.2f", value));
                }));

        Minecraft.getInstance().setScreen(new ClientSuspensionHeightScreen(
                pos, board, new ValueSettings(row, index), ignoreFirstUseRelease));
    }

    @Override
    protected void saveAndClose(double mouseX, double mouseY) {
        ValueSettings closest = getClosestCoordinate((int) mouseX, (int) mouseY);
        float height = closest.value() * STEP;
        if (closest.row() == 1) height = -height;
        SablenauticsNetwork.sendSuspensionHeightUpdate(bogeyPos, height);
        onClose();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // The click that opened the screen is not a confirmation; the next use click (or any
        // left click) confirms the value. Use releases themselves never close the screen.
        if (button == 0) {
            saveAndClose(mouseX, mouseY);
            return true;
        }
        if (minecraft != null && minecraft.options.keyUse.matchesMouse(button)) {
            if (ignoreFirstUseRelease) {
                ignoreFirstUseRelease = false;
                return true;
            }
            saveAndClose(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyUse.matches(keyCode, scanCode)) {
            if (ignoreFirstUseRelease) {
                ignoreFirstUseRelease = false;
            }
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }
}
