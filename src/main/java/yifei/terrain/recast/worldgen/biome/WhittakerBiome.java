package yifei.terrain.recast.worldgen.biome;

import net.minecraft.util.RandomSource;

/**
 * L6 群系分类——Whittaker 图（温度 × 湿度）查表 + 过渡带混合。
 *
 * <p>设计（对应设计文档 5.5 的 L6）：群系不是噪声标签，而是气候的<b>结果</b>。
 * 这里维护一张 10×10 的温度-湿度表，每个格心先按气候定一个基础群系；
 * 采样时用双线性权重在相邻四格之间混合，得到"过渡带"而不是硬切边。
 *
 * <p>海拔是独立一维（垂直地带性）：海岸、高山草甸、雪线直接按高度覆盖，
 * 与水平气候带不冲突。
 *
 * <p>当前接入点：{@code Cell.Field.BIOME}（供附属/调试查询）与植被/地表覆盖物
 * （用 {@link #pick} 按权重抽树种）。**尚未**替换原版 {@code MultiNoiseBiomeSource}——
 * 那需要自定义 BiomeSource 或 TerraBlender（见设计文档 7.4），属于 P2。
 */
public final class WhittakerBiome {

    public static final int OCEAN = 0;
    public static final int BEACH = 1;
    public static final int TUNDRA = 2;
    public static final int TAIGA = 3;
    public static final int GRASSLAND = 4;
    public static final int FOREST = 5;
    public static final int SAVANNA = 6;
    public static final int DESERT = 7;
    public static final int ALPINE = 8;
    public static final int SNOW = 9;
    /** 群系种类数。 */
    public static final int COUNT = 10;

    /** 温度轴格数 / 湿度轴格数。 */
    private static final int T_STEPS = 10;
    private static final int M_STEPS = 10;
    /** 气候轴范围（与 Cell.temperature / moisture 的语义一致）。 */
    private static final double T_MIN = -1.0;
    private static final double T_MAX = 1.0;
    private static final double M_MIN = -1.0;
    private static final double M_MAX = 1.0;

    /** 海拔分界：海岸 / 高山草甸 / 雪线（格，相对海平面）。 */
    private static final double BEACH_HEIGHT = 6.0;
    private static final double ALPINE_HEIGHT = 850.0;
    private static final double SNOW_HEIGHT = 1150.0;

    /** 查表：格心 (t,m) 对应的基础群系（不含海陆与海拔覆盖）。 */
    private static final int[] TABLE = buildTable();

    private WhittakerBiome() {}

    private static int[] buildTable() {
        int[] table = new int[T_STEPS * M_STEPS];
        for (int ti = 0; ti < T_STEPS; ti++) {
            double t = T_MIN + (T_MAX - T_MIN) * (ti + 0.5) / T_STEPS;
            for (int mi = 0; mi < M_STEPS; mi++) {
                double m = M_MIN + (M_MAX - M_MIN) * (mi + 0.5) / M_STEPS;
                table[ti * M_STEPS + mi] = baseBiome(t, m);
            }
        }
        return table;
    }

    /**
     * Whittaker 基础分类（只看温度/湿度）。
     *
     * <p>阈值按本模组实际气候分布标定：海拔递减很强，陆地温度多在 -0.2 以下，
     * 所以"冷"档的阈值不能按原版 [-1,1] 的直觉取。
     */
    private static int baseBiome(double temperature, double moisture) {
        if (temperature < -0.55) return TUNDRA;
        if (temperature < -0.22) return TAIGA;
        if (moisture < -0.35) return temperature > 0.15 ? DESERT : TUNDRA;
        if (moisture < -0.05) return temperature > 0.25 ? SAVANNA : GRASSLAND;
        if (moisture < 0.28) return temperature > 0.10 ? FOREST : TAIGA;
        return FOREST;
    }

    /** 主分类（最近格，不做混合）。供调试/API 使用。 */
    public static int classify(double temperature, double moisture, double elevation) {
        if (elevation < 0.0) return OCEAN;
        if (elevation < BEACH_HEIGHT) return BEACH;
        if (elevation > SNOW_HEIGHT) return SNOW;
        if (elevation > ALPINE_HEIGHT) return ALPINE;
        return TABLE[tIndex(temperature) * M_STEPS + mIndex(moisture)];
    }

    /**
     * 过渡带采样：按双线性权重在相邻四格之间抽一个群系。
     *
     * <p>同一个 (t,m) 每次调用结果可能不同（随机），这正是我们要的——
     * 边界附近两个群系的物种会交错出现，形成自然过渡，而不是一条直线切开。
     *
     * @param random 生成期随机源（Feature 自带，线程安全）
     */
    public static int pick(double temperature, double moisture, double elevation, RandomSource random) {
        if (elevation < 0.0) return OCEAN;
        if (elevation < BEACH_HEIGHT) return BEACH;
        if (elevation > SNOW_HEIGHT) return SNOW;
        if (elevation > ALPINE_HEIGHT) return ALPINE;

        double tf = (clamp(temperature) - T_MIN) / (T_MAX - T_MIN) * T_STEPS - 0.5;
        double mf = (clamp(moisture) - M_MIN) / (M_MAX - M_MIN) * M_STEPS - 0.5;
        int t0 = (int) Math.floor(tf);
        int m0 = (int) Math.floor(mf);
        double ft = tf - t0;
        double fm = mf - m0;
        int t1 = clampIndex(t0 + 1, T_STEPS);
        int m1 = clampIndex(m0 + 1, M_STEPS);
        t0 = clampIndex(t0, T_STEPS);
        m0 = clampIndex(m0, M_STEPS);

        double w00 = (1 - ft) * (1 - fm);
        double w10 = ft * (1 - fm);
        double w01 = (1 - ft) * fm;
        double w11 = ft * fm;
        double roll = random.nextDouble();
        double acc = 0;
        int b00 = TABLE[t0 * M_STEPS + m0];
        int b10 = TABLE[t1 * M_STEPS + m0];
        int b01 = TABLE[t0 * M_STEPS + m1];
        int b11 = TABLE[t1 * M_STEPS + m1];
        if ((acc += w00) > roll) return b00;
        if ((acc += w10) > roll) return b10;
        if ((acc += w01) > roll) return b01;
        return b11;
    }

    /** 群系 → 语言键后缀（调试/日志用）。 */
    public static String nameOf(int biome) {
        return switch (biome) {
            case OCEAN -> "ocean";
            case BEACH -> "beach";
            case TUNDRA -> "tundra";
            case TAIGA -> "taiga";
            case GRASSLAND -> "grassland";
            case FOREST -> "forest";
            case SAVANNA -> "savanna";
            case DESERT -> "desert";
            case ALPINE -> "alpine";
            case SNOW -> "snow";
            default -> "unknown";
        };
    }

    private static int tIndex(double temperature) {
        double n = (clamp(temperature) - T_MIN) / (T_MAX - T_MIN) * T_STEPS;
        return clampIndex((int) n, T_STEPS);
    }

    private static int mIndex(double moisture) {
        double n = (clamp(moisture) - M_MIN) / (M_MAX - M_MIN) * M_STEPS;
        return clampIndex((int) n, M_STEPS);
    }

    private static int clampIndex(int v, int steps) {
        return v < 0 ? 0 : Math.min(v, steps - 1);
    }

    private static double clamp(double v) {
        return v < -1.0 ? -1.0 : Math.min(v, 1.0);
    }
}
