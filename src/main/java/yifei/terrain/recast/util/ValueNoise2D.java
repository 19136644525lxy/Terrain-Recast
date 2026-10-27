package yifei.terrain.recast.util;

/**
 * 种子化 2D 值噪声（value noise）+ 多倍频叠加。
 *
 * <p>用于板块<b>内部</b>的低频起伏：软 Voronoi 只能保证板块间过渡连续，
 * 板块内部仍是平的，需要这层噪声制造次级山脉链与丘陵。
 *
 * <p>无状态、纯函数、可任意线程并发调用。
 */
public final class ValueNoise2D {

    private final long seed;

    public ValueNoise2D(long seed) {
        this.seed = seed;
    }

    /** 单层值噪声，返回 [-1, 1]。 */
    public double sample(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        // 平滑曲线，保证一阶导连续，避免格点棱线
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);

        double n00 = hash(x0, z0);
        double n10 = hash(x0 + 1, z0);
        double n01 = hash(x0, z0 + 1);
        double n11 = hash(x0 + 1, z0 + 1);

        double top = n00 + (n10 - n00) * sx;
        double bottom = n01 + (n11 - n01) * sx;
        return top + (bottom - top) * sz;
    }

    /**
     * 多倍频叠加，返回归一化到 [-1, 1]。
     *
     * @param scale     基础波长（方块）
     * @param octaves   倍频数
     * @param lacunarity 每层频率倍数
     * @param gain      每层振幅衰减
     */
    public double fbm(double x, double z, double scale, int octaves, double lacunarity, double gain) {
        double freq = 1.0 / scale;
        double amp = 1.0;
        double total = 0.0;
        double norm = 0.0;
        for (int i = 0; i < octaves; i++) {
            total += amp * sample(x * freq, z * freq);
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return norm == 0.0 ? 0.0 : total / norm;
    }

    /** 格点哈希 → [-1, 1]。 */
    private double hash(int x, int z) {
        long h = seed;
        h = h * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL;
        h = h * 0x9E3779B97F4A7C15L + z * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        return ((h & 0xFFFFFFFFL) / 2147483648.0) - 1.0;
    }
}
