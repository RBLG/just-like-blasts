package teluri.mods.jlblasts.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public class BetterStackCollector {
    public final long pos;
    public ItemStack stack;
    public final int maxStackSize;

    public BetterStackCollector(final BlockPos pos, final ItemStack stack) {
        this.pos = pos.asLong();
        this.stack = stack;
        this.maxStackSize = stack.getMaxStackSize();
    }

    public boolean unsafeTryMerge(final ItemStack input) {
        //both stacks have been checked to not be empty, so check can be skipped
        boolean mergeable = input.count + stack.count > maxStackSize ? false
                : ItemStack.isSameItemSameComponents(stack, input);
        if (mergeable) {
            int delta = Math.min(Math.min(maxStackSize, 16) - stack.count, input.count);

            //inlined copyWithCount to remove is empty check
            //inlined copy to remove isEmpty check
            ItemStack newToStack = new ItemStack(stack.typeHolder(), stack.count + delta, stack.components.copy());

            newToStack.setPopTime(stack.getPopTime());

            input.shrink(delta);
            this.stack = newToStack;
        }
        return mergeable;
    }
}
