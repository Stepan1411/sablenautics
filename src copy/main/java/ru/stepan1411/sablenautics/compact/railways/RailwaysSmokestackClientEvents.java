package ru.stepan1411.sablenautics.compact.railways;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.stepan1411.sablenautics.Sablenautics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Railways' smokestack smoke is driven by Create's {@code MovementBehaviour}, which is never
 * ticked for blocks inside a Sable sub-level. Without this compat a locomotive's smokestack
 * would only emit the slow stationary smoke and no moving plume.
 * <p>
 * This reproduces the relevant part of {@code SmokeStackMovementBehaviour.tick}: the same
 * {@code SmokeEmissionParams.makeParticles(...)} call, with the chance and particle count
 * scaled by the sub-level's real speed while the train is moving.
 * <p>
 * Railways is optional, so every access to it goes through reflection.
 */
@EventBusSubscriber(modid = Sablenautics.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class RailwaysSmokestackClientEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sablenautics/Railways");

    private static final String RAILWAYS_MOD_ID = "railways";

    /** Railways' default client config value, used because reading the config reflectively is fragile. */
    private static final double SMOKE_PERCENTAGE = 0.75;

    private static final Map<Class<?>, Optional<Field>> EMISSION_FIELDS = new HashMap<>();
    private static final Map<Class<?>, Optional<Method>> COLOR_GETTERS = new HashMap<>();
    private static final Map<Class<?>, Optional<Method>> SOUL_GETTERS = new HashMap<>();
    private static final Map<Class<?>, Optional<Field>> HEIGHT_FIELDS = new HashMap<>();
    private static final Map<Class<?>, Optional<Method>> CHANCE_GETTERS = new HashMap<>();
    private static final Map<Class<?>, Optional<Method>> MIN_PARTICLE_GETTERS = new HashMap<>();
    private static final Map<Class<?>, Optional<Method>> MAX_PARTICLE_GETTERS = new HashMap<>();
    private static final Map<ClientSubLevel, CachedStacks> CACHES = new WeakHashMap<>();

    private static Optional<Method> makeParticles = Optional.empty();
    private static boolean makeParticlesResolved;
    private static boolean reflectionFailed;

    private RailwaysSmokestackClientEvents() {
    }

    private static final class CachedStacks {
        long nextScan;
        List<BlockPos> positions = List.of();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        if (!ModList.get().isLoaded(RAILWAYS_MOD_ID) || reflectionFailed) return;

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) continue;

            // Blocks per tick; the pose delta is one client tick.
            double speed = clientSubLevel.logicalPose().position()
                    .distance(clientSubLevel.lastPose().position());
            if (speed < 0.002) continue;

            CachedStacks cache = CACHES.computeIfAbsent(clientSubLevel, ignored -> new CachedStacks());
            long gameTime = level.getGameTime();
            if (gameTime >= cache.nextScan) {
                cache.positions = scan(clientSubLevel);
                cache.nextScan = gameTime + 20;
            }

            if (cache.positions.isEmpty()) continue;

            for (BlockPos plotPos : cache.positions) {
                emit(clientSubLevel, plotPos, speed);
            }
        }
    }

    private static List<BlockPos> scan(ClientSubLevel subLevel) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null || bounds.volume() <= 0) return List.of();

        ClientLevel level = subLevel.getLevel();
        List<BlockPos> found = new ArrayList<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (emissionField(state.getBlock().getClass()).isPresent()) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    private static void emit(ClientSubLevel subLevel, BlockPos plotPos, double speed) {
        ClientLevel level = subLevel.getLevel();
        BlockState state = level.getBlockState(plotPos);
        if (state.isAir()) return;
        if (state.hasProperty(BlockStateProperties.ENABLED)
                && !state.getValue(BlockStateProperties.ENABLED)) {
            return;
        }

        Optional<Field> optionalField = emissionField(state.getBlock().getClass());
        if (optionalField.isEmpty()) return;

        RandomSource random = level.getRandom();
        try {
            Object emissionParams = optionalField.get().get(state.getBlock());
            Optional<Method> optionalParticles = makeParticlesMethod(emissionParams.getClass());
            if (optionalParticles.isEmpty()) return;
            Method particleMethod = optionalParticles.get();

            // Railways feeds Create's animation speed into this formula; it is basically
            // blocks-per-tick * 1000, so a moving train always gets the large burst values.
            double chanceModifier = ((speed * 1000.0 + 100.0) / 800.0);
            chanceModifier *= chanceModifier;
            chanceModifier = Math.min(chanceModifier, 8.0);

            int maxModifier = 0;
            int minModifier = 0;
            if (chanceModifier > 2.0) {
                maxModifier += (int) (chanceModifier + 0.5) - 1;
                minModifier = chanceModifier > 3.0
                        ? (int) (chanceModifier + 0.5) - 2
                        : 1;
            } else if (chanceModifier > 1.0) {
                maxModifier++;
            }
            // Matches the non-cartoon branch (the default cartoon style ignores the count).
            minModifier += 5;
            maxModifier += 15;

            double spawnChance = chanceGetter(emissionParams.getClass())
                    .map(method -> invokeDouble(method, emissionParams, 1.0))
                    .orElse(1.0);
            if (random.nextFloat() >= spawnChance * chanceModifier * SMOKE_PERCENTAGE) {
                return;
            }

            int minParticles = minParticleGetter(emissionParams.getClass())
                    .map(method -> invokeInt(method, emissionParams, 1))
                    .orElse(1);
            int maxParticles = maxParticleGetter(emissionParams.getClass())
                    .map(method -> invokeInt(method, emissionParams, 1))
                    .orElse(1);

            int min = minParticles + minModifier;
            int max = maxParticles + maxModifier;
            int count = Math.min(40, min + random.nextInt(Math.max(1, max - min)));

            BlockEntity blockEntity = level.getBlockEntity(plotPos);
            DyeColor color = blockEntity == null ? null : readColor(blockEntity);
            boolean soul = blockEntity != null && readSoul(blockEntity);
            double heightOffset = blockEntity == null ? 0.0 : readHeight(blockEntity);

            Vec3 local = new Vec3(
                    plotPos.getX() + (random.nextDouble() - 0.5) * 0.5,
                    plotPos.getY() + heightOffset,
                    plotPos.getZ() + (random.nextDouble() - 0.5) * 0.5);
            Vec3 world = subLevel.logicalPose().transformPosition(local);
            double speedMultiplier = Math.min(10.0, 2.5 + speed * 5.0);

            for (int i = 0; i < count; i++) {
                particleMethod.invoke(emissionParams, level, world, random.nextBoolean(),
                        speedMultiplier, true, color, null, soul);
            }
        } catch (Throwable throwable) {
            reflectionFailed = true;
            LOGGER.warn("Sablenautics: Railways smokestack compat disabled", throwable);
        }
    }

    private static Optional<Field> emissionField(Class<?> blockClass) {
        return EMISSION_FIELDS.computeIfAbsent(blockClass, type -> {
            try {
                Field field = type.getField("emissionParams");
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
                return Optional.empty();
            }
        });
    }

    private static Optional<Method> makeParticlesMethod(Class<?> paramsClass) {
        if (!makeParticlesResolved || makeParticles.isEmpty()) {
            makeParticlesResolved = true;
            try {
                makeParticles = Optional.of(paramsClass.getMethod("makeParticles",
                        net.minecraft.world.level.Level.class,
                        Vec3.class,
                        boolean.class,
                        double.class,
                        boolean.class,
                        DyeColor.class,
                        Boolean.class,
                        boolean.class));
            } catch (Throwable throwable) {
                reflectionFailed = true;
                LOGGER.warn("Sablenautics: Railways SmokeEmissionParams API not found", throwable);
                makeParticles = Optional.empty();
            }
        }
        return makeParticles;
    }

    private static Optional<Method> chanceGetter(Class<?> paramsClass) {
        return CHANCE_GETTERS.computeIfAbsent(paramsClass, type -> noArgMethod(type, "particleSpawnChance"));
    }

    private static Optional<Method> minParticleGetter(Class<?> paramsClass) {
        return MIN_PARTICLE_GETTERS.computeIfAbsent(paramsClass, type -> noArgMethod(type, "minParticles"));
    }

    private static Optional<Method> maxParticleGetter(Class<?> paramsClass) {
        return MAX_PARTICLE_GETTERS.computeIfAbsent(paramsClass, type -> noArgMethod(type, "maxParticles"));
    }

    private static Optional<Method> noArgMethod(Class<?> type, String name) {
        try {
            return Optional.of(type.getMethod(name));
        } catch (NoSuchMethodException ignored) {
            return Optional.empty();
        }
    }

    private static double invokeDouble(Method method, Object target, double fallback) {
        try {
            return ((Number) method.invoke(target)).doubleValue();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int invokeInt(Method method, Object target, int fallback) {
        try {
            return ((Number) method.invoke(target)).intValue();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static DyeColor readColor(BlockEntity blockEntity) {
        Optional<Method> method = COLOR_GETTERS.computeIfAbsent(blockEntity.getClass(),
                type -> noArgMethod(type, "getColor"));
        if (method.isEmpty()) return null;
        try {
            return (DyeColor) method.get().invoke(blockEntity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean readSoul(BlockEntity blockEntity) {
        Optional<Method> method = SOUL_GETTERS.computeIfAbsent(blockEntity.getClass(),
                type -> noArgMethod(type, "isSoul"));
        if (method.isEmpty()) return false;
        try {
            return Boolean.TRUE.equals(method.get().invoke(blockEntity));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double readHeight(BlockEntity blockEntity) {
        Optional<Field> field = HEIGHT_FIELDS.computeIfAbsent(blockEntity.getClass(), type -> {
            try {
                Field height = type.getDeclaredField("height");
                height.setAccessible(true);
                return Optional.of(height);
            } catch (NoSuchFieldException ignored) {
                return Optional.empty();
            }
        });
        if (field.isEmpty()) return 0.0;
        try {
            int height = field.get().getInt(blockEntity);
            return height / 2.0 + (height % 2 == 1 ? 0.5 : 0.0);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }
}
