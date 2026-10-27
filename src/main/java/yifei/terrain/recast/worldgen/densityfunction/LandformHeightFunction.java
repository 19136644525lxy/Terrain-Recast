package yifei.terrain.recast.worldgen.densityfunction;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import yifei.terrain.recast.TerrainRecast;
import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.worldgen.cell.Cell;

/**
 * 大地貌高度密度函数——返回该 (x,z) 相对海平面的地表高度（方块）。
 *
 * <p>域轴：只依赖 XZ（大地貌高度是水平位置的函数）。
 *
 * <p>取值 ≈ [-110, 3000+]：负值=海沟/深海，正值=山脉/高原。
 * 区间随高度域与振幅上限放大，可支撑 ~2000 方块的高峰。
 */
public record LandformHeightFunction() implements DensityFunction {

    public static final MapCodec<LandformHeightFunction> CODEC = MapCodec.unit(new LandformHeightFunction());

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new LandformHeightSampler();
    }

    @Override
    public Interval range() { return Interval.of(-300f, 3700f); }

    @Override
    public @Axes int domainAxes() { return AXIS_X | AXIS_Z; }

    @Override
    public MapCodec<LandformHeightFunction> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    public static final class LandformHeightSampler implements DensitySampler {

        // 单列缓存：sampleVolumeNaive 按 z→x→y 遍历，同一 (x,z) 的整列会连续采样。
        // 本函数仅依赖 XZ，缓存后每列只计算一次 Worley+fbm，避免新高度域下逐 Y 重复。
        private boolean cached;
        private int cacheX;
        private int cacheZ;
        private float cacheValue;

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            if (cached && blockX == cacheX && blockZ == cacheZ) {
                return cacheValue;
            }
            cacheX = blockX;
            cacheZ = blockZ;
            cached = true;

            var lookup = TerrainRecastApi.getCellLookup();
            if (lookup == null) {
                // lookup 未注入时不能返回 0（= 海平面）：那会让该区域被削成绝对平面，
                // 与周边正常地形在区块前沿形成垂直断崖。返回大陆基准高度作为兜底。
                if (NULL_WARNED.compareAndSet(false, true)) {
                    TerrainRecast.LOGGER.warn("CellLookup 为空，区块在注入前被生成，地形退化为基准高度");
                }
                return cacheValue = 32f;
            }
            return cacheValue = (float) lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, blockX, blockZ);
        }

        private static final java.util.concurrent.atomic.AtomicBoolean NULL_WARNED =
                new java.util.concurrent.atomic.AtomicBoolean();
    }
}
