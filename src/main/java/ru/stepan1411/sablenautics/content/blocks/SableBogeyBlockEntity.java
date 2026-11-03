package ru.stepan1411.sablenautics.content.blocks;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import ru.stepan1411.sablenautics.index.SablenauticsBogeyStyles;

import java.util.UUID;

public class SableBogeyBlockEntity extends AbstractBogeyBlockEntity {

    private UUID trainId;
    private int carriageIndex = -1;
    private int bogieIndex = -1;

    public SableBogeyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return SablenauticsBogeyStyles.SABLE;
    }

    public void setTrainInfo(UUID trainId, int carriageIndex, int bogieIndex) {
        this.trainId = trainId;
        this.carriageIndex = carriageIndex;
        this.bogieIndex = bogieIndex;
    }

    public UUID getTrainId() {
        return trainId;
    }

    public int getCarriageIndex() {
        return carriageIndex;
    }

    public int getBogieIndex() {
        return bogieIndex;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, Provider registries) {
        super.saveAdditional(tag, registries);
        if (trainId != null) {
            tag.putUUID("TrainId", trainId);
        }
        tag.putInt("CarriageIndex", carriageIndex);
        tag.putInt("BogieIndex", bogieIndex);
    }

    @Override
    public void loadAdditional(CompoundTag tag, Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("TrainId")) {
            trainId = tag.getUUID("TrainId");
        }
        carriageIndex = tag.getInt("CarriageIndex");
        bogieIndex = tag.getInt("BogieIndex");
    }
}
