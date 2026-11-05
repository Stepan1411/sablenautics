package ru.stepan1411.sablenautics.content.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

import java.util.List;
import java.util.Locale;

/**
 * Adjusts the vertical suspension offset of a single Sable bogey block.
 * <p>
 * The offset is stored in the block entity NBT, so every bogey can have a different height
 * and the value survives world reloads.
 */
public class SuspensionHeightToolItem extends Item {

    /** One click moves the wheels by one sixteenth of a block. */
    public static final float STEP = 1.0f / 16.0f;
    public static final float MIN_OFFSET = -8.0f / 16.0f;
    public static final float MAX_OFFSET = 8.0f / 16.0f;

    public SuspensionHeightToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof SableBogeyBlockEntity bogey)) {
            return InteractionResult.PASS;
        }

        Player player = context.getPlayer();
        boolean lowering = player != null && player.isShiftKeyDown();

        // Only the server owns the value; the block update syncs it to the client.
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        float previous = bogey.getSuspensionOffset();
        float next = Mth.clamp(previous + (lowering ? -STEP : STEP), MIN_OFFSET, MAX_OFFSET);
        if (Math.abs(next - previous) < 1.0e-5f) {
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("sablenautics.suspension_height.limit"), true);
            }
            return InteractionResult.SUCCESS;
        }

        bogey.setSuspensionOffset(next);
        if (player != null) {
            player.displayClientMessage(Component.translatable("sablenautics.suspension_height.value",
                    String.format(Locale.ROOT, "%+.0f", next * 16.0f)), true);
        }
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS,
                0.6f, lowering ? 0.8f : 1.2f);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("sablenautics.suspension_height.raise")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("sablenautics.suspension_height.lower")
                .withStyle(ChatFormatting.GRAY));
    }
}
