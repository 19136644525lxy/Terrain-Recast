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

/**
 * 下界火山熔岩世界密度函数——把原版「噪声洞穴+熔岩海」重塑为
 * 「熔岩海托起的破火山口火山群」，3D 空间密度场。
 *
 * <p>接入：覆盖 data/minecraft/worldgen/density_function/nether/base_3d_noise.json。
 * 该函数是 {@code slideNetherLike} 唯一消费的地形源，最小改动即可串进下界 final_density。
 *
 * <p>场定义（连续性优先，禁止断崖）：
 * <ul>
 *   <li>底部基床：BED_LEVEL 之下始终为实心（岩浆床），之上为空腔</li>
 *   <li>火山锥：按 2D 网格节点 + 世界种子确定性哈希生成。口径海拔呈
 *       「中心破火山口下凹、内壁陡、外缘缓」剖面，全部用 Hermite 光滑曲线</li>
 *   <li>熔岩海：基床之上、火山锥之外的空腔，被下界默认熔岩在低海平面填满</li>
 *   <li>熔岩裂谷：大尺度有向网格对整列做垂直负向切割，切开陆地与火山壁成可见峡谷，熔岩填充成河</li>
 * </ul>
 *
 * <p>取并集用 max：连续函数的最大值仍连续，故无跳变。域轴 ALL_AXES（3D 空间函数）。
 */
public record NetherVolcanoDensityFunction() implements DensityFunction {

    public static final MapCodec<NetherVolcanoDensityFunction> CODEC = MapCodec.unit(new NetherVolcanoDensityFunction());

    // 网格间距：约 64 格一个潜在火山节点
    private static final float SPACING = 64.0f;
    // 基床（岩浆床）基准海拔，之上为空腔
    private static final float BED_LEVEL = 14.0f;
    // 基床低频起伏网格间距与振幅：火山之间分成岛弧/浅滩/深熔岩湖层次
    private static final float BED_GRID = 48.0f;
    private static final float BED_AMP = 7.0f;
    // 熔岩裂谷：大尺度有向网格对整列做垂直负向切割（3D 峡谷），熔岩填充成河。
    // 只下切床面不可见（床面在熔岩海面下）；必须切开陆地与火山壁才能看到峡谷。
    private static final float TRENCH_GRID = 96.0f;
    private static final float TRENCH_HALF = 16.0f;   // 半宽
    private static final float TRENCH_LEN = 96.0f;    // 有效半长
    private static final float TRENCH_CUT = 0.8f;     // 垂直切割密度单位（穿透火山壁）
    private static final float TRENCH_OCCUR = 0.48f;  // 稀疏化
    private static final int DIR_SHIFT = 1;           // 相邻 2^DIR_SHIFT 格共享方向，裂谷连贯
    // 火山影响半径范围（口径加大，山体连片成陆）
    private static final float MIN_R = 21.0f;
    private static final float MAX_R = 30.0f;
    // 火山峰顶相对基床的高度范围（山脉强化）
    private static final float MIN_H = 44.0f;
    private static final float MAX_H = 116.0f;
    // 火山出现哈希阈值（稀疏化：约 70% 节点成山，火山口更密）
    private static final float SPAWN_THRESHOLD = 0.30f;

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        long seed = context.createRandom(TerrainRecast.id("nether_volcano")).nextLong();
        return new Sampler(seed);
    }

    @Override
    public Interval range() { return Interval.of(-2f, 2f); }

    @Override
    public @Axes int domainAxes() { return ALL_AXES; }

    @Override
    public MapCodec<NetherVolcanoDensityFunction> codec() { return CODEC; }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }

    /** 确定性 2D 整数哈希（世界种子混入格点坐标，返回 [0,1)）。 */
    private static float hash(long seed, int gx, int gz) {
        long h = seed + (long) gx * 0x9E3779B97F4A7C15L + (long) gz * -0x7EE8BB1DD12345E1L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h & 0xFFFFFFFFL) / 4294967296f;
    }

    /** Hermite 光滑插值，用于口径剖面与半径衰减。 */
    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    private static final class Sampler implements DensitySampler {
        private final long seed;

        Sampler(long seed) { this.seed = seed; }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            // 基床：BED 基准海拔叠低频起伏，其下实心、其上空腔
            float bedY = BED_LEVEL + ripple(blockX, blockZ);
            float bed = Math.max(-1f, Math.min(1f, (bedY - blockY) * 0.10f + 0.35f));
            float density = bed;

            // 采样点周边 3x3 网格节点的火山贡献，取并集（max 保持连续）
            int gx = Math.floorDiv(blockX, (int) SPACING);
            int gz = Math.floorDiv(blockZ, (int) SPACING);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = gx + dx;
                    int nz = gz + dz;
                    float r = hash(seed, nx, nz);
                    if (r >= SPAWN_THRESHOLD) continue;   // 稀疏化
                    // 节点内抖动出火山口心 (hx,hz) 与山体参数
                    float hx = (nx + 0.5f + (hash(seed, nx, nz ^ 0x1B3) - 0.5f) * 0.6f) * SPACING;
                    float hz = (nz + 0.5f + (hash(seed, nz, nx ^ 0x51C) - 0.5f) * 0.6f) * SPACING;
                    float amp = MIN_H + r * (MAX_H - MIN_H);
                    float rad = MIN_R + hash(seed, nx ^ 0x2A7, nz ^ 0x8F1) * (MAX_R - MIN_R);

                    float odx = blockX - hx;
                    float odz = blockZ - hz;
                    float dist = (float) Math.sqrt(odx * odx + odz * odz);
                    if (dist > rad) continue;

                    float u = dist / rad;   // 0=山口心, 1=外缘
                    float m;
                    if (u < 0.35f) {
                        // 中央破火山口下凹，向壁抬升
                        m = 0.18f + 0.82f * smooth(u / 0.35f);
                    } else {
                        // 内壁近满高→外缘缓落
                        m = 1f - smooth((u - 0.35f) / 0.65f);
                    }
                    float surface = bedY + amp * m;   // 该 (x,z) 处火山地表海拔
                    float cone = Math.max(-1f, Math.min(1f, (surface - blockY) * 0.085f));
                    if (cone > density) density = cone;
                }
            }
            // 3D 垂直切割：裂谷带内对整列密度做负向削减，切开陆地与火山壁成可见峡谷
            // （高处切口随 Y 衰减收窄，形成真实裂谷 V 形截面）
            density -= trench(blockX, blockZ) * TRENCH_CUT * cutScaleY(blockY);
            return density;
        }

        /** 裂谷高位归零：下界 slide 在 Y≈100-128 造实心天花板，高位切割会凿出沟槽。
         *  故仅切地板地形（Y≤90），Y>90 完全不动，保证天花板完整。 */
        private float cutScaleY(int y) {
            float t = (90 - y) / 60f;
            t = t < 0f ? 0f : (t > 1f ? 1f : t);
            return smooth(t);
        }

        /** smoothstep 双线性插值的低频床面起伏，返回 [-BED_AMP, +BED_AMP]。 */
        private float ripple(float x, float z) {
            int ax = (int) Math.floor(x / BED_GRID);
            int az = (int) Math.floor(z / BED_GRID);
            float fx = x / BED_GRID - ax;
            float fz = z / BED_GRID - az;
            float sx = smooth(fx), sz = smooth(fz);
            float v00 = hash(seed, ax, az), v10 = hash(seed, ax + 1, az);
            float v01 = hash(seed, ax, az + 1), v11 = hash(seed, ax + 1, az + 1);
            float v = lerp(lerp(v00, v10, sx), lerp(v01, v11, sx), sz);   // [0,1)
            return (v * 2f - 1f) * BED_AMP;
        }

        /**
         * 熔岩裂谷场：大尺度有向网格中部分格子生出一条带方向的下切裂缝。
         * 每 2^DIR_SHIFT 格共享方向角形成连贯条带，半宽/半长用 smooth 渐隐保证连续。
         * 返回该处裂谷下切比例 [0,1]。
         */
        private float trench(float x, float z) {
            float maxS = 0f;
            int gx = (int) Math.floor(x / TRENCH_GRID);
            int gz = (int) Math.floor(z / TRENCH_GRID);
            for (int az = gz; az <= gz + 1; az++) {
                for (int ax = gx; ax <= gx + 1; ax++) {
                    if (hash(seed, ax, az) < TRENCH_OCCUR) continue;   // 稀疏化
                    float cx = (ax + 0.5f) * TRENCH_GRID;
                    float cz = (az + 0.5f) * TRENCH_GRID;
                    int sx = ax >> DIR_SHIFT, sz = az >> DIR_SHIFT;
                    float ang = hash(seed, sx, sz) * (float) Math.PI;  // [0,π)
                    float ux = (float) Math.cos(ang), uz = (float) Math.sin(ang);
                    float ox = x - cx, oz = z - cz;
                    float perp = Math.abs(ox * uz - oz * ux);          // 到裂谷线垂直距离
                    float along = ox * ux + oz * uz;                   // 沿方向距离(以裁剪段长度)
                    float w = 1f - smooth(Math.min(1f, perp / TRENCH_HALF));
                    float af = 1f - smooth(Math.min(1f, Math.abs(along) / TRENCH_LEN));
                    float s = Math.max(0f, w * af);
                    if (s > maxS) maxS = s;
                }
            }
            return maxS;
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}