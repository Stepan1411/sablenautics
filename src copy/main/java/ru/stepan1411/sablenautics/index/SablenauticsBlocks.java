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
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlock;

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

    /**
     * Sable bogey block, copied from the Railways invisible bogey. It is used as the
     * placeholder bogey inside a Sable carriage and can also be placed in the world.
     */
    public static final BlockEntry<SableBogeyBlock> SABLE_BOGEY = REGISTRATE
            .block("sable_bogey", SableBogeyBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(net.minecraft.world.level.material.MapColor.PODZOL)
                    .sound(net.minecraft.world.level.block.SoundType.NETHERITE_BLOCK)
                    .noOcclusion())
            .tag(AllBlockTags.SAFE_NBT.tag)
            .transform(TagGen.pickaxeOnly())
            .loot((p, l) -> p.dropOther(l, AllBlocks.RAILWAY_CASING.get()))
            .lang("Sable Bogey")
            .register();


    public static void init() {
    }
}
