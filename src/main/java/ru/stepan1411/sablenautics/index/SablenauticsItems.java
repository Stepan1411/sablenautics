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
            .tab(CreativeModeTabs.TOOLS_AND_UTILITIES)
            .lang("Suspension Height Tool")
            .register();

    public static void init() {
    }
}
