package ru.stepan1411.sablenautics.index;

import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.client.SableBogeyStyleRenderer;
import ru.stepan1411.sablenautics.client.SableBogeyStyleVisual;

/**
 * Bogey style copied from the Railways invisible bogey: a valid bogey with no visible
 * renderer, so normal Create paths can handle the block even without Steam 'n' Rails.
 */
public final class SablenauticsBogeyStyles {

    private static final ResourceLocation SABLE_ID =
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "sable_bogey");
    private static final ResourceLocation SABLE_CYCLE_GROUP =
            ResourceLocation.fromNamespaceAndPath(Sablenautics.MOD_ID, "sable_bogey");

    public static final BogeyStyle SABLE = new BogeyStyle.Builder(SABLE_ID, SABLE_CYCLE_GROUP)
            .displayName(Component.translatable("bogey.sablenautics.sable_bogey"))
            .size(BogeySizes.SMALL, () -> SablenauticsBlocks.SABLE_BOGEY.get(),
                    () -> () -> new BogeyStyle.SizeRenderer(
                            new SableBogeyStyleRenderer(), SableBogeyStyleVisual::new))
            .build();

    private SablenauticsBogeyStyles() {
    }

    public static void init() {
    }
}
