package ru.stepan1411.sablenautics.index;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.util.entry.BlockEntry;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.PhysicsStickerBlock;

public class SablenauticsBlocks {
    private static final com.simibubi.create.foundation.data.CreateRegistrate REGISTRATE = Sablenautics.getRegistrate();

    public static final BlockEntry<PhysicsStickerBlock> PHYSICS_STICKER = REGISTRATE
            .block("physics_sticker", PhysicsStickerBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion())
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .tag(AllBlockTags.SAFE_NBT.tag)
            .transform(TagGen.pickaxeOnly())
            .recipe((ctx, prov) -> ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ctx.get())
                    .pattern("XXX")
                    .pattern("XOX")
                    .pattern("XXX")
                    .define('X', Items.SLIME_BALL)
                    .define('O', AllBlocks.STICKER.get())
                    .unlockedBy("has_sticker", prov.has(AllBlocks.STICKER.get()))
                    .save(prov))
            .simpleItem()
            .register();

    public static void init() {
    }
}
