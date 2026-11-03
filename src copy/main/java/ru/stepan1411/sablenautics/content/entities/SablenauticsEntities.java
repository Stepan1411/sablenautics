package ru.stepan1411.sablenautics.content.entities;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.stepan1411.sablenautics.Sablenautics;

public final class SablenauticsEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Sablenautics.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<SableBogeyEntity>> SABLE_BOGEY =
            ENTITY_TYPES.register("sable_bogey", () -> EntityType.Builder
                    .of(SableBogeyEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(Sablenautics.MOD_ID + ":sable_bogey"));

    private SablenauticsEntities() {
    }

    public static void init() {
    }
}
