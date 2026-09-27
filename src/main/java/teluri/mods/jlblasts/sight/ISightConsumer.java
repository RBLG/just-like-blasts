package teluri.mods.jlblasts.sight;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface ISightConsumer {
	void consume(BlockPos xyz, float visi, BlockState bs);
}