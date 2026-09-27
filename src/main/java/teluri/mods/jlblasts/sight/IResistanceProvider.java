package teluri.mods.jlblasts.sight;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface IResistanceProvider {
	float getResistance(BlockPos xyz, BlockState[] bs);
}
