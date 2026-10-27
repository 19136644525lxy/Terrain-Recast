package yifei.terrain.recast.worldgen.densityfunction;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.worldgen.cell.Cell;

/**
 * 板块边界距离密度函数——直接从 Cell 缓存取 plateBoundaryDistance 字段。
 *
 * <p>域轴：只依赖 XZ（板块边界是水平结构）。
 *
 * <p>取值 ∈ [0, 1]：0 = 板块边界（深海/海沟），1 = 板块中心（稳定大陆）。
 */
public record PlateBoundaryDistanceFunction() implements DensityFunction {

    public static final MapCodec<PlateBoundaryDistanceFunction> CODEC = MapCodec.unit(new PlateBoundaryDistanceFunction());

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new PlateBoundaryDistanceSampler();
    }

    @Override
    public Interval range() { return Interval.of(0f, 1f); }

    @Override
    public @Axes int domainAxes() { return AXIS_X | AXIS_Z; }

    @Override
    public MapCodec<PlateBoundaryDistanceFunction> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    public static final class PlateBoundaryDistanceSampler implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            var lookup = TerrainRecastApi.getCellLookup();
            if (lookup == null) return 0.5f;
            return (float) lookup.sampleField(Cell.Field.PLATE_BOUNDARY_DISTANCE, blockX, blockZ);
        }
    }
}
