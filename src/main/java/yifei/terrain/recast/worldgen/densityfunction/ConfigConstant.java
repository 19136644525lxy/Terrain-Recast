package yifei.terrain.recast.worldgen.densityfunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import yifei.terrain.recast.config.ConfigHandler;

import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * 配置常量密度函数：把预设里的一个数值选项暴露为 DensityFunction 节点，
 * 直接插进 NoiseRouter。
 *
 * <p>设计要点（MC 26.3 新接口适配）：
 * <ul>
 *   <li>{@code domainAxes()} = {@code NO_AXES}：配置值与坐标无关</li>
 *   <li>{@code compileSampler()} 返回内部 {@link ConfigConstantSampler}，
 *       {@code sampleValue()} 每次实时查 {@link ConfigHandler#getValue(String)} ——
 *       预设切换即时生效，不缓存 value</li>
 *   <li>Codec 只序列化 key 字符串，ConfigHandler 静态入口在反序列化时提供当前值</li>
 * </ul>
 *
 * <p>参考 Tectonic {@code ConfigConstant} + 原版 {@code ConstantFunction}。
 */
public record ConfigConstant(String key, float fallback) implements DensityFunction {

    public static final MapCodec<ConfigConstant> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("key").forGetter(ConfigConstant::key)
    ).apply(i, k -> new ConfigConstant(k, 0f)));

    /** 固定值构造（无预设场景）。 */
    public ConfigConstant(float value) { this("", value); }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new ConfigConstantSampler(key, fallback);
    }

    @Override
    public Interval range() {
        return Interval.of(-1_000_000f, 1_000_000f);
    }

    @Override
    public @Axes int domainAxes() { return DensityFunction.NO_AXES; }

    @Override
    public MapCodec<ConfigConstant> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    /** 运行时采样器。每次调用都实时查 ConfigHandler，不缓存。 */
    public record ConfigConstantSampler(String key, float fallback) implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            float v = key.isEmpty() ? fallback : (float) ConfigHandler.getValue(key);
            outputBuffer.fill(v);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return key.isEmpty() ? fallback : (float) ConfigHandler.getValue(key);
        }
    }
}
