package ru.stepan1411.sablenautics.compact.mrcrayfishsfurnituremod;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MrCrayfishsFurnitureModMixinPlugin implements IMixinConfigPlugin {
    private static final String MOD_ID = "refurbished_furniture";
    private boolean modLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        this.modLoaded = FMLLoader.getLoadingModList() != null
                && FMLLoader.getLoadingModList().getModFileById(MOD_ID) != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.modLoaded;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
