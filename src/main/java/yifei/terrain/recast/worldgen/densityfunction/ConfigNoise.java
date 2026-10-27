package yifei.terrain.recast.worldgen.densityfunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import yifei.terrain.recast.config.ConfigHandler;

import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 配置化噪声密度函数：噪声采样 scale/offset 从 ConfigHandler 实时读取，
 * 让预设里的"大陆尺度"/"山脉因子"/"海岸偏移"等参数直接影响噪声分布。
 *
 * <p>设计思路（参考 Tectonic {@code ConfigNoise} + 原版 {@code NoiseFunction}）：
 * <ul>
 *   <li>持有 {@code Holder<NormalNoise>} + scaleKey（ConfigHandler key）+ fallback scale</li>
 *   <li>{@code domainAxes()} = ALL_AXES：噪声需要 XYZ 三轴采样</li>
 *   <li>{@code compileSampler()} 里把 scale 和 shift 从 ConfigHandler 取值，
 *       预设切换即时生效（不缓存配置值）</li>
 *   <li>scale/offset 为 0 时退化为零函数（不采样噪声）</li>
 * </ul>
 */
public record ConfigNoise(
        Holder<NormalNoise> noise,
        String scaleKey,
        float fallbackScale,
        String shiftXKey,
        String shiftZKey
) implements DensityFunction {

    public static final MapCodec<ConfigNoise> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            NormalNoise.CODEC.fieldOf("noise").forGetter(ConfigNoise::noise),
            Codec.STRING.fieldOf("scale_key").forGetter(ConfigNoise::scaleKey),
            Codec.FLOAT.optionalFieldOf("fallback_scale", 1.0f).forGetter(ConfigNoise::fallbackScale),
            Codec.STRING.optionalFieldOf("shift_x_key", "").forGetter(ConfigNoise::shiftXKey),
            Codec.STRING.optionalFieldOf("shift_z_key", "").forGetter(ConfigNoise::shiftZKey)
    ).apply(i, ConfigNoise::new));

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        Noise sampler = context.createNoiseSampler(noise);
        return new ConfigNoiseSampler(sampler, scaleKey, fallbackScale, shiftXKey, shiftZKey);
    }

    @Override
    public Interval range() {
        return noise.value().range();
    }

    @Override
    public @Axes int domainAxes() { return ALL_AXES; }

    @Override
    public MapCodec<ConfigNoise> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    /** 运行时采样器。每次 sampleValue 都从 ConfigHandler 实时取 scale/offset。 */
    public record ConfigNoiseSampler(
            Noise noise, String scaleKey, float fallbackScale, String shiftXKey, String shiftZKey
    ) implements DensitySampler {

        private float currentScale() {
            return scaleKey.isEmpty() ? fallbackScale : (float) ConfigHandler.getValue(scaleKey);
        }

        private float currentShiftX() {
            return shiftXKey.isEmpty() ? 0f : (float) ConfigHandler.getValue(shiftXKey);
        }

        private float currentShiftZ() {
            return shiftZKey.isEmpty() ? 0f : (float) ConfigHandler.getValue(shiftZKey);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            float scale = currentScale();
            if (scale == 0f) return 0f;
            double x = (blockX + currentShiftX()) * scale;
            double z = (blockZ + currentShiftZ()) * scale;
            return noise.get(x, blockY * scale, z);
        }
    }
}
