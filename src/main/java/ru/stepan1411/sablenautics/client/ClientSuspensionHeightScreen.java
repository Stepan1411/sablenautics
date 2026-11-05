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

    private ClientSuspensionHeightScreen(BlockPos pos, ValueSettingsBoard board, ValueSettings settings) {
        super(pos, board, settings, value -> {
        }, 0);
        this.bogeyPos = pos;
    }

    public static void open(BlockPos pos, float height) {
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
                pos, board, new ValueSettings(row, index)));
    }

    @Override
    protected void saveAndClose(double mouseX, double mouseY) {
        ValueSettings closest = getClosestCoordinate((int) mouseX, (int) mouseY);
        float height = closest.value() * STEP;
        if (closest.row() == 1) height = -height;
        SablenauticsNetwork.sendSuspensionHeightUpdate(bogeyPos, height);
        onClose();
    }
}
