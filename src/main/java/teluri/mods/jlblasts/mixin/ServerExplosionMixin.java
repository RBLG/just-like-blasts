package teluri.mods.jlblasts.mixin;

import org.joml.RoundingMode;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import teluri.mods.jlblasts.JustLikeBlasts;
import teluri.mods.jlblasts.misc.BetterStackCollector;
import teluri.mods.jlblasts.sight.IResistanceProvider;
import teluri.mods.jlblasts.sight.ISightConsumer;
import teluri.mods.jlblasts.sight.SightEngine;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin implements Explosion {
    private static float FALLOFF = 0.22500001f;

    @Overwrite
    public int explode() {
        this.level().gameEvent(this.source, GameEvent.EXPLODE, this.center());
        Vector3i source = this.center().toVector3f().get(RoundingMode.FLOOR, new Vector3i());

        JustLikeBlasts.LOGGER.info("calculating blast with a strength of " + this.radius());
        LongArrayList hits = propagateBlast(source);
        this.hurtEntities();
        if (this.interactsWithBlocks()) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("explosion_blocks");
            applyBlast(hits);
            profiler.pop();
        }
        if (this.fire) {
            betterCreateFire(hits);
        }
        JustLikeBlasts.LOGGER.info("blocks impacted by the blast " + hits.size());
        return hits.size();
    }

    private LongArrayList propagateBlast(Vector3i source) {
        LongArrayList allhits = new LongArrayList();
        // float startingPower = this.radius() * (0.7f + this.level().getRandom().nextFloat() * 0.6f);
        float startingPower = this.radius() * 0.4f;
        int reach = Mth.ceil(startingPower / FALLOFF);
        SightEngine.forEachQuadrants((quadrant) -> {
            LongArrayList hits = new LongArrayList();
            ISightConsumer scons = (pos, value, block) -> {
                value = startingPower - value;
                if (0 < value && this.damageCalculator.shouldBlockExplode(this, this.level(), pos, block, value)) {
                    hits.add(pos.asLong());
                }
            };
            IResistanceProvider aprov = (pos, bs) -> {
                BlockState block = this.level().getBlockState(pos);
                bs[0] = block;
                if (!this.level().isInWorldBounds(pos)) {
                    return Float.MAX_VALUE;
                }
                FluidState fluid = this.level().getFluidState(pos);

                float resis = 0;
                Optional<Float> resistance = this.damageCalculator.getBlockExplosionResistance(this, this.level(), pos, block, fluid);
                if (resistance.isPresent()) {
                    resis += (resistance.get() + 0.3F) * 0.3F;
                }
                return resis;
            };
            SightEngine.traceQuadrant(source, reach, quadrant, aprov, scons, startingPower);
            syncAdd(allhits, hits);
        });
        return allhits;
    }

    private static synchronized void syncAdd(LongArrayList list, LongArrayList list2) {
        list.addAll(list2);
    }

    private void applyBlast(LongArrayList hits) {
        List<BetterStackCollector> stacks = new ArrayList<>();
        MutableBlockPos mpos = new MutableBlockPos();
        //Vec3 source = this.center();
        //TODO deduplicate reach calculation
        //float startingPower = this.radius() * 0.4f;
        //int reach = Mth.ceil(startingPower / FALLOFF);
        //int dropLimit = reach - 16;
        //int reachSqr = dropLimit * dropLimit;

        for (long hit : hits) {
            mpos.set(hit);
            //double distSqr = mpos.distToLowCornerSqr(source.x, source.y, source.z);
            //TODO technically obliteration should be based on remaining power but its not stored to there so cant do that for now
            //could prob be optimized to use a fast path to destroying the block if obliterated
            //boolean isObliterated = distSqr < reachSqr;
            this.level().getBlockState(mpos).onExplosionHit(this.level(), mpos, this, (stackx, position) -> {
                //if (!isObliterated) {
                betterAddOrAppendStack(stacks, stackx, position);
                //}
            });
        }

        JustLikeBlasts.LOGGER.info("stacks dropped " + stacks.size());

        for (var stack : stacks) {
            Block.popResource(this.level(), mpos.set(stack.pos), stack.stack);
        }
    }

    private void betterCreateFire(LongArrayList hits) {
        MutableBlockPos mpos = new MutableBlockPos();

        for (long hit : hits) {
            mpos.set(hit);
            boolean rnd = this.level().getRandom().nextInt(3) == 0;
            if (rnd && this.level().getBlockState(mpos).isAir() && this.level().getBlockState(mpos.below()).isSolidRender()) {
                this.level().setBlockAndUpdate(mpos, BaseFireBlock.getState(this.level(), mpos));
            }
        }

    }


    private static void betterAddOrAppendStack(final List<BetterStackCollector> stacks, final ItemStack stack, final BlockPos pos) {
        if (stack.isEmpty()) { //early check so unsafeTryMerge doesnt have to do empty checks
            return;
        }
        for (var stackCollector : stacks) {
            if (!stackCollector.unsafeTryMerge(stack)) {
                continue;
            }
            if (stack.isEmpty()) {
                return;
            }
        }

        stacks.add(new BetterStackCollector(pos.immutable(), stack));
    }

    /// ///////////////////////////////////////////////////////////

    // private static final ExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new ExplosionDamageCalculator();
    // private static final int MAX_DROPS_PER_COMBINED_STACK = 16;
    // private static final float LARGE_EXPLOSION_RADIUS = 2.0F;
    @Shadow
    private final boolean fire;
    @Shadow
    private final @Nullable Entity source;
    @Shadow
    private final ExplosionDamageCalculator damageCalculator;

    @Shadow
    private boolean interactsWithBlocks() {
        return false;
    }

    @Shadow
    private void hurtEntities() {
    }

    public ServerExplosionMixin() {
        this.fire = false;
        this.source = null;
        this.damageCalculator = new ExplosionDamageCalculator();

    }

}
