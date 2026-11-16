package teluri.mods.jlblasts.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.RoundingMode;
import org.joml.Vector3i;

import java.util.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import teluri.mods.jlblasts.JustLikeBlasts;
import teluri.mods.jlblasts.misc.BetterCollector;
import teluri.mods.jlblasts.sight.IResistanceProvider;
import teluri.mods.jlblasts.sight.ISightConsumer;
import teluri.mods.jlblasts.sight.SightEngine;

import static net.minecraft.world.level.Explosion.getSeenPercent;
import static teluri.mods.jlblasts.misc.BetterCollector.MAX_COLLECTED_STACK_SIZE;

@Mixin(Explosion.class)
public abstract class ServerExplosionMixin {
    private static float FALLOFF = 0.22500001f;

    @WrapMethod(method = "explode()V")
    public void betterExplode(Operation<Void> original) {
        this.level().gameEvent(this.source, GameEvent.EXPLODE, this.center());
        Vector3i source = this.center().toVector3f().get(RoundingMode.FLOOR, new Vector3i());

        JustLikeBlasts.LOGGER.info("calculating blast with a strength of " + this.radius());
        LongArrayList hits = propagateBlast(source);
        this.hurtEntities();
        if (this.interactsWithBlocks()) {
            applyBlast(hits);
        }
        if (this.fire) {
            betterCreateFire(hits);
        }
        JustLikeBlasts.LOGGER.info("blocks impacted by the blast " + hits.size());
        //return hits.size();
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
                if (0 < value && this.damageCalculator.shouldBlockExplode((Explosion)(Object)this, this.level(), pos, block, value)) {
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
                Optional<Float> resistance = this.damageCalculator.getBlockExplosionResistance((Explosion)(Object)this, this.level(), pos, block, fluid);
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
        List<BetterCollector> stacks = new ArrayList<>();
        List<BetterCollector> fstacks = new ArrayList<>();
        MutableBlockPos mpos = new MutableBlockPos();

        for (long hit : hits) {
            mpos.set(hit);
            this.level().getBlockState(mpos).onExplosionHit(this.level(), mpos, (Explosion)(Object)this, (stackx, position) -> {
                betterAddOrAppendStack(fstacks, stacks, stackx, position);
            });
        }

        JustLikeBlasts.LOGGER.info("stacks dropped: " + stacks.size() + " full: " + fstacks.size());

        for (var stack : fstacks) {
            Block.popResource(this.level(), mpos.set(stack.pos), stack.stack);
        }
        for (var stack : stacks) {
            Block.popResource(this.level(), mpos.set(stack.pos), stack.stack);
        }
    }

    private void betterCreateFire(LongArrayList hits) {
        MutableBlockPos mpos = new MutableBlockPos();

        for (long hit : hits) {
            mpos.set(hit);
            boolean rnd = this.level().getRandom().nextInt(3) == 0;
            if (rnd && this.level().getBlockState(mpos).isAir() && this.level().getBlockState(mpos.below()).isSolidRender(level(),mpos.below())) {
                this.level().setBlockAndUpdate(mpos, BaseFireBlock.getState(this.level(), mpos));
            }
        }

    }


    private static void betterAddOrAppendStack(final List<BetterCollector> fstacks, final List<BetterCollector> stacks, final ItemStack stack, final BlockPos pos) {
        for (Iterator<BetterCollector> iterator = stacks.iterator(); iterator.hasNext(); ) {
            var collector = iterator.next();
            if (!collector.tryMerge(stack)) {
                continue;
            }
            if (Math.min(collector.maxStackSize, MAX_COLLECTED_STACK_SIZE) <= collector.stack.getCount()) {
                iterator.remove();
                fstacks.add(collector);
            }
            if (stack.isEmpty()) {
                return;
            }
        }

        stacks.add(new BetterCollector(pos.immutable(), stack));
    }

    /// ///////////////////////////////////////////////////////////

    // private static final ExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new ExplosionDamageCalculator();
    // private static final int MAX_DROPS_PER_COMBINED_STACK = 16;
    // private static final float LARGE_EXPLOSION_RADIUS = 2.0F;
    @Shadow
    private final boolean fire;
    @Shadow
    private final Entity source;
    @Shadow
    private final ExplosionDamageCalculator damageCalculator;

    @Shadow
    private boolean interactsWithBlocks() {
        return false;
    }


    private void hurtEntities() {
        float q = this.radius() * 2.0F;
        Vec3 vec3 = center();
        int k = Mth.floor(vec3.x - q - 1.0);
        int l = Mth.floor(vec3.x + q + 1.0);
        int r = Mth.floor(vec3.y - q - 1.0);
        int s = Mth.floor(vec3.y + q + 1.0);
        int t = Mth.floor(vec3.z - q - 1.0);
        int u = Mth.floor(vec3.z + q + 1.0);
        List<Entity> list = this.level.getEntities(this.source, new AABB(k, r, t, l, s, u));

        for (Entity entity : list) {
            if (!entity.ignoreExplosion((Explosion)(Object)this)) {
                double v = Math.sqrt(entity.distanceToSqr(vec3)) / q;
                if (v <= 1.0) {
                    double w = entity.getX() - vec3.x;
                    double x = (entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY()) - vec3.y;
                    double y = entity.getZ() - vec3.z;
                    double z = Math.sqrt(w * w + x * x + y * y);
                    if (z != 0.0) {
                        w /= z;
                        x /= z;
                        y /= z;
                        if (this.damageCalculator.shouldDamageEntity((Explosion)(Object)this, entity)) {
                            entity.hurt(this.damageSource, this.damageCalculator.getEntityDamageAmount((Explosion)(Object)this, entity));
                        }

                        double aa = (1.0 - v) * getSeenPercent(vec3, entity) * this.damageCalculator.getKnockbackMultiplier(entity);
                        double ab;
                        if (entity instanceof LivingEntity livingEntity) {
                            ab = aa * (1.0 - livingEntity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE));
                        } else {
                            ab = aa;
                        }

                        w *= ab;
                        x *= ab;
                        y *= ab;
                        Vec3 vec32 = new Vec3(w, x, y);
                        entity.setDeltaMovement(entity.getDeltaMovement().add(vec32));
                        if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
                            this.getHitPlayers().put(player, vec32);
                        }

                        entity.onExplosionHit(this.source);
                    }
                }
            }
        }
    }

    public ServerExplosionMixin() {
        this.level = null;
        this.fire = false;
        this.source = null;
        this.damageCalculator = null;
        this.damageSource = null;

    }

    @Shadow
    public float radius() {
        return 0;
    }

    @Shadow
    public Vec3 center() {
        return null;
    }

    @Shadow
    private final Level level;
    @Shadow
    private final DamageSource damageSource;

    public Level level() {
        return level;
    }

    @Shadow
    public Map<Player, Vec3> getHitPlayers() {
        return null;
    }

}
