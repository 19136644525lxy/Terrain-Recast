package yifei.terrain.recast.worldgen.densityfunction;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * 末地重塑预留入口（第六章）。
 * 目标：碎裂大陆、熔岩核心岛、虚空群岛、龙巢。当前阶段 stub，恒返回 0，不生效。
 */
public record EndShatteredContinentFunction() implements DensityFunction {

    public static final MapCodec<EndShatteredContinentFunction> CODEC = MapCodec.unit(new EndShatteredContinentFunction());

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new Sampler();
    }

    @Override
    public Interval range() { return Interval.of(-1f, 1f); }

    @Override
    public @Axes int domainAxes() { return ALL_AXES; }

    @Override
    public MapCodec<EndShatteredContinentFunction> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    private static final class Sampler implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            outputBuffer.fill(0f);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return 0f;
        }
    }
}