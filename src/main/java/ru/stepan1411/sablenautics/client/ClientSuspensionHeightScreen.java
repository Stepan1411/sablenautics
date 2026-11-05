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
 * Uses Create's own value-selection screen (the one from the creative motor) for the
 * suspension height of a single Sable bogey.
 */
public class ClientSuspensionHeightScreen extends ValueSettingsScreen {

    private static final int STEPS_PER_BLOCK = 4;
    private static final float STEP = 1.0f / STEPS_PER_BLOCK;
    private static final int MAX_INDEX = Math.round(
            (SableBogeyBlockEntity.MAX_SUSPENSION_OFFSET - SableBogeyBlockEntity.MIN_SUSPENSION_OFFSET)
                    / STEP);

    private final BlockPos bogeyPos;

    private ClientSuspensionHeightScreen(BlockPos pos, ValueSettingsBoard board, ValueSettings settings) {
        super(pos, board, settings, value -> {
        }, 0);
        this.bogeyPos = pos;
    }

    public static void open(BlockPos pos, float height) {
        int index = Mth.clamp(Math.round(
                        (height - SableBogeyBlockEntity.MIN_SUSPENSION_OFFSET) / STEP),
                0, MAX_INDEX);

        ValueSettingsBoard board = new ValueSettingsBoard(
                Component.translatable("sablenautics.suspension_height.gui"),
                MAX_INDEX,
                STEPS_PER_BLOCK,
                List.of(Component.translatable("sablenautics.suspension_height.row")),
                new ValueSettingsFormatter(settings -> Component.literal(String.format(Locale.ROOT,
                        "%+.2f", SableBogeyBlockEntity.MIN_SUSPENSION_OFFSET
                                + settings.value() * STEP))));

        Minecraft.getInstance().setScreen(new ClientSuspensionHeightScreen(
                pos, board, new ValueSettings(0, index)));
    }

    @Override
    protected void saveAndClose(double mouseX, double mouseY) {
        ValueSettings closest = getClosestCoordinate((int) mouseX, (int) mouseY);
        float height = SableBogeyBlockEntity.MIN_SUSPENSION_OFFSET + closest.value() * STEP;
        SablenauticsNetwork.sendSuspensionHeightUpdate(bogeyPos, height);
        onClose();
    }
}
