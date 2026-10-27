package yifei.terrain.recast.util;

/**
 * Worley F2−F1 边界距离场生成器（L0 板块推演用）。
 *
 * <p>核心思路（简化版 Worley/Voronoi）：
 * <ol>
 *   <li>把世界按固定尺度（{@code cellSize} 方块）划成 Voronoi 单元网格</li>
 *   <li>每个单元格有一个"板块中心"坐标——由种子化随机偏移决定（不是在单元中心）</li>
 *   <li>对任意 (x, z) 坐标，在 3×3 邻域单元格里找 9 个板块中心</li>
 *   <li>取最近 2 个中心的距离，差 = F2−F1：0 表示正好在板块边界，越大越稳定</li>
 * </ol>
 *
 * <p>线程安全：WorleyDistance 实例无状态（每次调用都从 worldSeed 重算），
 * 可以被任意多个线程同时调用。
 */
public final class WorleyDistance {

    private final long worldSeed;

    public WorleyDistance(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    /**
     * 取 (tx, tz) 单元格的板块中心 X 坐标。
     * 中心位置 = 单元起点 + [0, cellSize) 的种子化随机偏移。
     */
    private double centerX(int tx, int tz, int cellSize) {
        double offset = seededRand(tx, tz, worldSeed, 0) * cellSize;
        return tx * cellSize + offset;
    }

    private double centerZ(int tx, int tz, int cellSize) {
        double offset = seededRand(tx, tz, worldSeed, 1) * cellSize;
        return tz * cellSize + offset;
    }

    /** 板块中心 X（方块坐标）。供 CellLookup 按板块网格取代表点。 */
    public double plateCenterX(int px, int pz, int cellSize) { return centerX(px, pz, cellSize); }

    /** 板块中心 Z（方块坐标）。 */
    public double plateCenterZ(int px, int pz, int cellSize) { return centerZ(px, pz, cellSize); }

    /** 取最近板块中心的 ID（即 (tx, tz) 的 Hash）。 */
    public int nearestPlateId(double x, double z, int cellSize) {
        int tx = (int) Math.floor(x / cellSize);
        int tz = (int) Math.floor(z / cellSize);
        int bestId = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int px = tx + dx;
                int pz = tz + dz;
                double cx = centerX(px, pz, cellSize);
                double cz = centerZ(px, pz, cellSize);
                double dist = sqDist(x, z, cx, cz);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestId = (px * 374761393 + pz * 668265263) & 0x7FFFFFFF;
                }
            }
        }
        return bestId;
    }

    /** 最近中心距离。 */
    public double nearestDistance(double x, double z, int cellSize) {
        return nearestAndSecond(x, z, cellSize)[0];
    }

    /** 第二近中心距离。 */
    public double secondNearestDistance(double x, double z, int cellSize) {
        return nearestAndSecond(x, z, cellSize)[1];
    }

    private double[] nearestAndSecond(double x, double z, int cellSize) {
        int tx = (int) Math.floor(x / cellSize);
        int tz = (int) Math.floor(z / cellSize);
        double d1 = Double.POSITIVE_INFINITY;
        double d2 = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                double cx = centerX(tx + dx, tz + dz, cellSize);
                double cz = centerZ(tx + dx, tz + dz, cellSize);
                double d = Math.sqrt(sqDist(x, z, cx, cz));
                if (d < d1) { d2 = d1; d1 = d; }
                else if (d < d2) { d2 = d; }
            }
        }
        return new double[]{d1, d2};
    }

    /** 综合查询结果——一次 3×3 遍历拿到所有板块数据。 */
    public record PlateSample(int id1, int id2, double f1, double f2) {}

    /**
     * 一次遍历拿到最近两个板块的 ID 和距离。
     * 替代多次分别调用 nearestPlateId/nearestDistance/secondNearestDistance。
     */
    public PlateSample sample(double x, double z, int cellSize) {
        int tx = (int) Math.floor(x / cellSize);
        int tz = (int) Math.floor(z / cellSize);
        int id1 = -1, id2 = -1;
        double d1 = Double.POSITIVE_INFINITY, d2 = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int px = tx + dx, pz = tz + dz;
                double cx = centerX(px, pz, cellSize), cz = centerZ(px, pz, cellSize);
                double d = Math.sqrt(sqDist(x, z, cx, cz));
                int id = (px * 374761393 + pz * 668265263) & 0x7FFFFFFF;
                if (d < d1) { d2 = d1; id2 = id1; d1 = d; id1 = id; }
                else if (d < d2) { d2 = d; id2 = id; }
            }
        }
        return new PlateSample(id1, id2, d1, d2);
    }

    /**
     * 三近板块邻域——软 Voronoi 混合用。
     *
     * <p>用扁平 record 而不是数组，避免在 NoiseRouter 热路径上产生数组分配。
     * 顺序保证 d1 ≤ d2 ≤ d3。
     */
    public record PlateNeighbourhood(
            int px1, int pz1, double d1,
            int px2, int pz2, double d2,
            int px3, int pz3, double d3) {}

    /**
     * 一次 3×3 遍历取最近三个板块单元及其距离。
     *
     * <p>这是软 Voronoi（distance-weighted blend）的数据基础：
     * 用这三个邻居到采样点的距离做权重，把「离散板块属性」变成「连续场」，
     * 从而消除区块级阶跃（参考 BigGlobe 的 Worley 连续距离场思想）。
     */
    public PlateNeighbourhood sampleNeighbourhood(double x, double z, int cellSize) {
        int tx = (int) Math.floor(x / cellSize);
        int tz = (int) Math.floor(z / cellSize);
        int p1x = tx, p1z = tz, p2x = tx, p2z = tz, p3x = tx, p3z = tz;
        double e1 = Double.POSITIVE_INFINITY;
        double e2 = Double.POSITIVE_INFINITY;
        double e3 = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int px = tx + dx, pz = tz + dz;
                double cx = centerX(px, pz, cellSize), cz = centerZ(px, pz, cellSize);
                double d = Math.sqrt(sqDist(x, z, cx, cz));
                if (d < e1) {
                    e3 = e2; p3x = p2x; p3z = p2z;
                    e2 = e1; p2x = p1x; p2z = p1z;
                    e1 = d; p1x = px; p1z = pz;
                } else if (d < e2) {
                    e3 = e2; p3x = p2x; p3z = p2z;
                    e2 = d; p2x = px; p2z = pz;
                } else if (d < e3) {
                    e3 = d; p3x = px; p3z = pz;
                }
            }
        }
        return new PlateNeighbourhood(p1x, p1z, e1, p2x, p2z, e2, p3x, p3z, e3);
    }

    /** 返回最近和第二近板块单元的 ID（用于判断边界两侧板块类型）。 */
    public int[] nearestTwoPlateIds(double x, double z, int cellSize) {
        int tx = (int) Math.floor(x / cellSize);
        int tz = (int) Math.floor(z / cellSize);
        int id1 = -1, id2 = -1;
        double d1 = Double.POSITIVE_INFINITY, d2 = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int px = tx + dx, pz = tz + dz;
                double cx = centerX(px, pz, cellSize), cz = centerZ(px, pz, cellSize);
                double d = Math.sqrt(sqDist(x, z, cx, cz));
                int id = (px * 374761393 + pz * 668265263) & 0x7FFFFFFF;
                if (d < d1) { d2 = d1; id2 = id1; d1 = d; id1 = id; }
                else if (d < d2) { d2 = d; id2 = id; }
            }
        }
        return new int[]{id1, id2};
    }

    /** 单元类型：大陆（≥0.5）还是海洋（<0.5）。种子化确定性。 */
    public boolean isContinent(int plateId) {
        return seededRand(plateId, 0, worldSeed, 2) >= 0.5;
    }

    private static double sqDist(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /**
     * 种子化伪随机数（[0, 1)）。xorshift64 + 参数混合。
     * 无依赖、线程安全、确定性——同 (tx, tz, seed, salt) 永远返回同值。
     */
    private static double seededRand(int tx, int tz, long seed, int salt) {
        long h = seed ^ ((long) tx * 374761393L) ^ ((long) tz * 668265263L) ^ ((long) salt * 2147483647L);
        h ^= h >>> 12;
        h ^= h << 25;
        h ^= h >>> 27;
        h *= 0x2545F4914F6CDD1DL;
        long r = (h ^ (h >>> 32)) & 0xFFFFFFFFL;
        return r / 4294967296.0;
    }
}
