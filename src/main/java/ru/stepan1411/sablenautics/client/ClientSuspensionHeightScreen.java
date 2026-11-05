package ru.stepan1411.sablenautics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;
import ru.stepan1411.sablenautics.network.SablenauticsNetwork;

import java.util.Locale;

/**
 * Small wrench screen for choosing the suspension height of one Sable bogey.
 */
public class ClientSuspensionHeightScreen extends Screen {

    private final BlockPos pos;
    private float suspensionHeight;

    public ClientSuspensionHeightScreen(BlockPos pos, float height) {
        super(Component.translatable("sablenautics.suspension_height.gui"));
        this.pos = pos;
        this.suspensionHeight = clamp(height);
    }

    public static void open(BlockPos pos, float height) {
        Minecraft.getInstance().setScreen(new ClientSuspensionHeightScreen(pos, height));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        addRenderableWidget(button("-1", cx - 104, cy - 10, 44, () -> adjust(-1.0f)));
        addRenderableWidget(button("-0.25", cx - 56, cy - 10, 50, () -> adjust(-SableBogeyBlockEntity.SUSPENSION_STEP)));
        addRenderableWidget(button("+0.25", cx + 6, cy - 10, 50, () -> adjust(SableBogeyBlockEntity.SUSPENSION_STEP)));
        addRenderableWidget(button("+1", cx + 60, cy - 10, 44, () -> adjust(1.0f)));

        addRenderableWidget(Button.builder(Component.translatable("sablenautics.suspension_height.reset"),
                        b -> setHeight(0.0f))
                .bounds(cx - 100, cy + 24, 96, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("sablenautics.suspension_height.done"),
                        b -> onClose())
                .bounds(cx + 4, cy + 24, 96, 20)
                .build());
    }

    private Button button(String label, int x, int y, int width, Runnable action) {
        return Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, width, 20)
                .build();
    }

    private void adjust(float delta) {
        setHeight(suspensionHeight + delta);
    }

    private void setHeight(float value) {
        float clamped = clamp(value);
        if (Math.abs(clamped - suspensionHeight) < 1.0e-5f) return;
        suspensionHeight = clamped;
        SablenauticsNetwork.sendSuspensionHeightUpdate(pos, suspensionHeight);
    }

    private static float clamp(float value) {
        return Math.max(SableBogeyBlockEntity.MIN_SUSPENSION_OFFSET,
                Math.min(SableBogeyBlockEntity.MAX_SUSPENSION_OFFSET, value));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 44, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("sablenautics.suspension_height.value",
                        String.format(Locale.ROOT, "%+.2f", suspensionHeight)),
                width / 2, height / 2 - 30, 0xDDDDDD);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
