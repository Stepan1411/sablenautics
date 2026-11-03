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

    private SablenauticsEntities() {
    }

    public static void init() {
    }
}
