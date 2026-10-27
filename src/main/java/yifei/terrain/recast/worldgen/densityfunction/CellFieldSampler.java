package yifei.terrain.recast.worldgen.densityfunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.worldgen.cell.Cell;

import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Cell 字段采样器——把 Cell 的某一个数值字段暴露为 DensityFunction 节点，
 * 直接插进 NoiseRouter。
 *
 * <p>采样逻辑：{@code (blockX, blockZ) → 区块坐标 → CellLookup.getCell() → Cell.Field.get()}
 * Cell 尚未计算时返回 0，由 NoiseRouter 上层的 {@code interpolated} 节点做平滑。
 *
 * <p>设计要点（MC 26.3 新接口）：
 * <ul>
 *   <li>{@code domainAxes()} = {@code ALL_AXES}：依赖 XZ 坐标定位 Cell</li>
 *   <li>{@code compileSampler()} 返回内部 {@link CellFieldSamplerImpl}</li>
 *   <li>CellLookup 通过 {@link TerrainRecastApi#getCellLookup()} 静态入口获取</li>
 *   <li>Cell.Field 解析失败或 Cell 未就绪时返回 0（DensityFunction fallback）</li>
 * </ul>
 */
public record CellFieldSampler(String fieldName) implements DensityFunction {

    public static final MapCodec<CellFieldSampler> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("cell_field").forGetter(CellFieldSampler::fieldName)
    ).apply(i, CellFieldSampler::new));

    private Cell.Field resolveField() {
        for (Cell.Field f : Cell.Field.values()) {
            if (f.name().equalsIgnoreCase(fieldName)) return f;
        }
        return null;
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        Cell.Field field = resolveField();
        return new CellFieldSamplerImpl(field);
    }

    @Override
    public Interval range() { return Interval.of(-1f, 1f); }

    @Override
    public @Axes int domainAxes() { return ALL_AXES; }

    @Override
    public MapCodec<CellFieldSampler> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    /** 运行时采样器——返回区块级常数 Cell 值，由上层 {@code minecraft:interpolated} 做三线性平滑。 */
    public record CellFieldSamplerImpl(Cell.Field field) implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            if (field == null) return 0;
            var lookup = TerrainRecastApi.getCellLookup();
            if (lookup == null) return 0;
            return (float) lookup.sampleField(field, blockX, blockZ);
        }
    }
}
