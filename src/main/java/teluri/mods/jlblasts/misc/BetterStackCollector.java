package teluri.mods.jlblasts.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

public class BetterStackCollector {
    public final long pos;
    private boolean copied = false;
    public ItemStack stack;
    public final int maxStackSize;

    public BetterStackCollector(final BlockPos pos, final ItemStack stack) {
        this.pos = pos.asLong();
        this.stack = stack;
        this.maxStackSize = stack.getMaxStackSize();
    }

    public boolean unsafeTryMerge(final ItemStack input) {
        //both stacks have been checked to not be empty, so check can be skipped
        //replaced GetCount() by count to skip isEmpty check
        boolean mergeable = input.count + stack.count <= maxStackSize;
        if (mergeable) {
            mergeable = unsafeIsSameItemSameComponents(stack, input);
        }
        if (mergeable) {
            int delta = Math.min(Math.min(maxStackSize, 16) - stack.count, input.count);
            //if copied then the stack object has never seen a function outside of this mod and should be safe to assume to be unique
            if (!copied) {
                //replaced copyWithCount to remove isEmpty check
                this.stack = unsafeCopy(stack);
                this.copied = true;
            }
            this.stack.grow(delta);
            input.shrink(delta);
        }
        return mergeable;
    }

    private static boolean unsafeIsSameItemSameComponents(ItemStack a, ItemStack b) {
        //inlined again to skip 4 isEmpty checks
        final Item aItem = a.item.value();
        final Item bItem = b.item.value();
        return (aItem != bItem) ? false : Objects.equals(a.components, b.components);
    }

    private static ItemStack unsafeCopy(ItemStack stack) {
        //inlined copy to remove isEmpty check
        ItemStack copy = new ItemStack(stack.typeHolder(), stack.count, stack.components.copy());
        copy.setPopTime(stack.getPopTime());
        return copy;
    }
}
