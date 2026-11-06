package ru.stepan1411.sablenautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBox;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;
import ru.stepan1411.sablenautics.Sablenautics;
import ru.stepan1411.sablenautics.content.blocks.SableBogeyBlockEntity;

import java.util.Locale;

/**
 * Draws the Create-style value box over a Sable bogey while a wrench is held.
 */
@EventBusSubscriber(modid = Sablenautics.MOD_ID, value = Dist.CLIENT)
public final class ClientSuspensionHeightEvents {

    private static final Object VALUE_BOX_KEY = new Object();

    private static final ValueBoxTransform VALUE_BOX_TRANSFORM = new ValueBoxTransform() {
        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            return new Vec3(0.5, 0.75, 0.5);
        }

        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            ms.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        }

        @Override
        public boolean shouldRender(LevelAccessor level, BlockPos pos, BlockState state) {
            return true;
        }
    };

    private ClientSuspensionHeightEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        HitResult target = minecraft.hitResult;

        if (minecraft.level == null || minecraft.player == null
                || !(target instanceof BlockHitResult hit)
                || !AllItems.WRENCH.isIn(minecraft.player.getMainHandItem())
                || !(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof SableBogeyBlockEntity bogey)) {
            Outliner.getInstance().remove(VALUE_BOX_KEY);
            return;
        }

        BlockPos blockPos = hit.getBlockPos();
        BlockPos renderPos = blockPos;

        // The block entity lives in the sub-level plot; move the value box to the rendered
        // world position of the bogey instead of leaving it at the plot coordinates.
        ClientSubLevelAccess subLevel = Sable.HELPER.getContainingClient(bogey);
        if (subLevel != null) {
            Pose3dc pose = subLevel.renderPose();
            Vector3d center = new Vector3d(blockPos.getX() + 0.5,
                    blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
            pose.transformPosition(center, center);
            renderPos = BlockPos.containing(center.x, center.y, center.z);
        }

        AABB bb = new AABB(Vec3.ZERO, Vec3.ZERO)
                .inflate(0.5)
                .contract(0, 0, -0.5)
                .move(0, 0, -0.125);

        ValueBox box = new ValueBox.TextValueBox(
                Component.translatable("sablenautics.suspension_height.gui"),
                bb,
                renderPos,
                Component.literal(String.format(Locale.ROOT, "%+.2f", bogey.getSuspensionOffset())));
        box.passive(false).wideOutline().transform(VALUE_BOX_TRANSFORM);
        Outliner.getInstance().showOutline(VALUE_BOX_KEY, box);
    }
}
