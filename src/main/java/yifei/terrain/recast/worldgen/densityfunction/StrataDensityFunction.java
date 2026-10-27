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
 * 地层密度函数——Y 方向层状岩性 + Cell 基底岩性驱动。
 *
 * <p>域轴：ALL_AXES（3D 空间函数）。
 *
 * <p>岩性编码（与 {@link Cell#lithology} ordinal 一致）：
 * <pre>
 * 0=NONE 1=GRANITE 2=BASALT 3=LIMESTONE 4=SANDSTONE
 * 5=SHALE 6=GNEISS 7=SLATE 8=DEEPSLATE 9=BEDROCK
 * </pre>
 *
 * <p>层状规则：
 * <ul>
 *   <li>Y ≤ -56 → BEDROCK</li>
 *   <li>Y ∈ (-56, -32] → DEEPSLATE（深地壳）</li>
 *   <li>Y ∈ (-32, terrainHeight-16] → 基底岩性（Cell.lithology）</li>
 *   <li>Y ∈ (terrainHeight-16, terrainHeight) → 表层沉积（SANDSTONE/SHALE）</li>
 *   <li>Y ≥ terrainHeight → NONE（空气）</li>
 * </ul>
 */
public record StrataDensityFunction() implements DensityFunction {

    public static final MapCodec<StrataDensityFunction> CODEC = MapCodec.unit(new StrataDensityFunction());

    public static final class Lithology {
        public static final int NONE = 0;
        public static final int GRANITE = 1;
        public static final int BASALT = 2;
        public static final int LIMESTONE = 3;
        public static final int SANDSTONE = 4;
        public static final int SHALE = 5;
        public static final int GNEISS = 6;
        public static final int SLATE = 7;
        public static final int DEEPSLATE = 8;
        public static final int BEDROCK = 9;
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new StrataSampler();
    }

    /**
     * 由方块坐标计算岩性编码（DRY：统一判定逻辑，供 StrataSampler 与
     * {@link yifei.terrain.recast.worldgen.materialrule.LithologyRule} 复用）。
     *
     * <p>岩性由<b>连续场</b>决定（不读板块常数，避免整板块单一大色块）：
     * <ul>
     *   <li>海/陆大类用 CONTINENTALNESS（已绑定真实海拔，<0 海洋、>0 大陆）</li>
     *   <li>板块内部细分用 WEIRDNESS（块级连续噪声）→ 花岗岩/砂岩/玄武岩/石灰岩空间交替</li>
     * </ul>
     * 全部经原版 interpolated 平滑，保证跨板块连续、无跳变。
     *
     * @return 岩性编码 0..9（NONE..BEDROCK）
     */
    public static float evaluate(int blockX, int blockY, int blockZ) {
        var lookup = TerrainRecastApi.getCellLookup();
        double surface = lookup != null
                ? lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, blockX, blockZ) : 0.0;
        double marine = lookup != null
                ? lookup.sampleField(Cell.Field.CONTINENTALNESS, blockX, blockZ) : 0.0;
        double weird = lookup != null
                ? lookup.sampleField(Cell.Field.WEIRDNESS, blockX, blockZ) : 0.0;
        return evaluate(blockX, blockY, blockZ, surface, marine, weird);
    }

    /**
     * 岩性求值（字段预取版）。前三个 cell 字段仅依赖 XZ，可由调用方按列缓存后传入，
     * 避免材料规则在每列上千方块高度上重复计算 —— 新高度域下收益显著。
     */
    public static float evaluate(int blockX, int blockY, int blockZ,
                                 double surface, double marine, double weird) {
        if (blockY <= -56) return Lithology.BEDROCK;
        if (blockY <= -32) return Lithology.DEEPSLATE;

        // 表层过渡区（surface-16 到 surface）→ 沉积岩
        if (blockY > surface - 16 && blockY < surface) {
            // 海沟（surface < 0）→ 石灰岩；大陆表层→砂岩
            if (surface < 0) return Lithology.LIMESTONE;
            return Lithology.SANDSTONE;
        }
        int baseLith;
        if (marine < 0) {
            // 洋壳：玄武岩（洋中脊/火成）与石灰岩（海相沉积）按噪声交替
            baseLith = weird > 0.1 ? Lithology.BASALT : Lithology.LIMESTONE;
        } else {
            // 大陆地壳：结晶岩（花岗岩）与沉积岩（砂岩/页岩）按噪声交替
            if (weird > 0.15) baseLith = Lithology.GRANITE;
            else if (weird > -0.2) baseLith = Lithology.SANDSTONE;
            else baseLith = Lithology.SHALE;
        }

        // 深层基底：根据 Y 深度做轻微变质过渡，越深层变质程度越高
        if (blockY < -16) {
            // 深层基底：GNEISS/SLATE 替换 GRANITE/BASALT
            return switch (baseLith) {
                case 1 -> Lithology.GNEISS;    // GRANITE → GNEISS
                case 2 -> Lithology.SLATE;     // BASALT  → SLATE
                default -> baseLith;           // 沉积岩不变
            };
        }

        return baseLith;
    }

    @Override
    public Interval range() { return Interval.of(0f, 9f); }

    @Override
    public @Axes int domainAxes() { return ALL_AXES; }

    @Override
    public MapCodec<StrataDensityFunction> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    public static final class StrataSampler implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return evaluate(blockX, blockY, blockZ);
        }
    }
}
