package ru.stepan1411.sablenautics.index;

import com.tterrag.registrate.util.entry.ItemEntry;
import net.minecraft.world.item.CreativeModeTabs;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.items.SuspensionHeightToolItem;

public class SablenauticsItems {
    private static final com.simibubi.create.foundation.data.CreateRegistrate REGISTRATE = Sablenautics.getRegistrate();

    public static final ItemEntry<SuspensionHeightToolItem> SUSPENSION_HEIGHT_TOOL = REGISTRATE
            .item("suspension_height_tool", SuspensionHeightToolItem::new)
            .properties(p -> p.stacksTo(1))
            .lang("Suspension Height Tool")
            .register();

    /**
     * Adds the tool to the vanilla tools tab. Doing this here (instead of using the
     * Registrate tab modifier) keeps creative-tab rebuilds from adding the same stack twice,
     * which used to crash the client when opening the inventory.
     */
    public static void onBuildCreativeModeTabContents(
            net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) return;

        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(SUSPENSION_HEIGHT_TOOL.get());
        if (event.getParentEntries().contains(stack) || event.getSearchEntries().contains(stack)) {
            return;
        }
        event.accept(stack);
    }

    public static void init() {
    }
}
