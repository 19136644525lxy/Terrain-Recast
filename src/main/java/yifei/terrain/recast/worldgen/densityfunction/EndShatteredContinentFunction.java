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
 * 末地碎裂大陆密度函数。返回指定 (x, y, z) 处的岛屿密度值（>0 有陆地，<0 是虚空）。
 *
 * <p>域轴：ALL_AXES——碎裂岛屿是 3D 空间函数（岛屿有厚度，下方是虚空）。
 *
 * <p>设计（第六章"末地重塑·碎裂大陆"）：
 * <ul>
 *   <li>主岛碎裂为数十块大小不一的岛屿（火山喷发 + 裂隙分割）</li>
 *   <li>每块岛屿有独立的 Whittaker 群系（火山岛/热带岛/冻土岛/沙漠岛……）</li>
 *   <li>虚空深不见底，可能有飘浮遗迹或水晶矿脉</li>
 *   <li>末影龙巢穴在最大岛屿的最陡处</li>
 * </ul>
 *
 * <p>三期 stub：返回原版岛屿密度(0)。四期用 Worley + OpenSimplex 碎裂替换。
 */
public record EndShatteredContinentFunction() implements DensityFunction {

    public static final MapCodec<EndShatteredContinentFunction> CODEC = MapCodec.unit(new EndShatteredContinentFunction());

    @Override
    public DensitySampler compileSampler(CompileContext context) { return Sampler.INSTANCE; }

    @Override
    public Interval range() { return Interval.of(-10f, 10f); }

    @Override
    public @Axes int domainAxes() { return ALL_AXES; }

    @Override
    public MapCodec<EndShatteredContinentFunction> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    public static final class Sampler implements DensitySampler {
        public static final Sampler INSTANCE = new Sampler();
        @Override public void sampleVolume(SamplerContext ctx, DensityBuffer buf, DensityVolume vol) { buf.fill(0f); }
        @Override public float sampleValue(SamplerContext ctx, int x, int y, int z) { return 0f; }
    }
}