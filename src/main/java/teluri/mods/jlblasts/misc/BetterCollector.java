package teluri.mods.jlblasts.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public class BetterCollector {
    public final long pos;
    public ItemStack stack;
    public final int maxStackSize;

    public BetterCollector(final BlockPos pos, final ItemStack stack) {
        this.pos = pos.asLong();
        this.stack = stack;
        this.maxStackSize = stack.getMaxStackSize();
    }

    public static final int MAX_COLLECTED_STACK_SIZE = 16;

    public boolean tryMerge(final ItemStack input) {
        int inputCount = input.getCount();
        int thisCount = this.stack.getCount();
        boolean mergeable = inputCount + thisCount > maxStackSize ? false : ItemStack.isSameItemSameComponents(stack, input);
        if (mergeable) {
            int delta = Math.min(Math.min(maxStackSize, MAX_COLLECTED_STACK_SIZE) - thisCount, inputCount);
            ItemStack newToStack = this.stack.copyWithCount(thisCount + delta);
            input.shrink(delta);
            this.stack = newToStack;
        }
        return mergeable;
    }


}
