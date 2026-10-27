package yifei.terrain.recast.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.CellLookup;

/**
 * 粗网格扫描 + 局部爬山，找出附近最高的几座山。
 *
 * <p>为什么不在区块里找：地形高度是解析场（{@link Cell.Field#TERRAIN_HEIGHT}），
 * 不需要生成区块就能算，所以几千格半径也只要几十毫秒。
 */
public final class PeakSearch {

    private PeakSearch() {}

    /** 一个山峰结果。{@code height} 是相对海平面的格数。 */
    public record Peak(int x, int z, double height) {}

    /** 粗扫步长（方块）。 */
    private static final int COARSE_STEP = 32;
    /** 两个峰之间至少要隔这么远才算"不同的山"，否则会被同一座山重复占位。 */
    private static final int MIN_SEPARATION = 256;
    /** 候选池倍数：多收一些再爬山，否则一座山会把名额占满。 */
    private static final int CANDIDATE_FACTOR = 8;

    /**
     * @param centerX    搜索中心（方块）
     * @param centerZ    搜索中心（方块）
     * @param radius     半径（方块）
     * @param minHeight  只保留高度 ≥ 该值的点（相对海平面）
     * @param limit      返回多少个
     */
    public static List<Peak> search(CellLookup lookup, int centerX, int centerZ,
                                    int radius, double minHeight, int limit) {
        int capacity = Math.max(limit, limit * CANDIDATE_FACTOR);
        // 小顶堆：保留最高的 capacity 个候选
        PriorityQueue<Peak> top = new PriorityQueue<>(Comparator.comparingDouble(Peak::height));
        for (int dx = -radius; dx <= radius; dx += COARSE_STEP) {
            for (int dz = -radius; dz <= radius; dz += COARSE_STEP) {
                int x = centerX + dx;
                int z = centerZ + dz;
                double h = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, x, z);
                if (h < minHeight) continue;
                top.add(new Peak(x, z, h));
                if (top.size() > capacity) top.poll();
            }
        }
        List<Peak> candidates = new ArrayList<>(top.size());
        while (!top.isEmpty()) {
            candidates.add(refine(lookup, top.poll()));
        }
        candidates.sort(Comparator.comparingDouble(Peak::height).reversed());

        // 去重：同一座山的多个候选会爬到同一个峰，按间隔筛掉
        List<Peak> out = new ArrayList<>(limit);
        for (Peak p : candidates) {
            boolean tooClose = false;
            for (Peak kept : out) {
                if (Math.hypot(p.x() - kept.x(), p.z() - kept.z()) < MIN_SEPARATION) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            out.add(p);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 从粗扫候选点出发，用逐步减半的步长向高处爬，逼近真实峰顶。 */
    private static Peak refine(CellLookup lookup, Peak start) {
        int x = start.x();
        int z = start.z();
        double best = start.height();
        int step = COARSE_STEP / 2;
        while (step >= 2) {
            boolean moved = false;
            for (int dx = -step; dx <= step; dx += step) {
                for (int dz = -step; dz <= step; dz += step) {
                    if (dx == 0 && dz == 0) continue;
                    double h = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, x + dx, z + dz);
                    if (h > best) {
                        best = h;
                        x += dx;
                        z += dz;
                        moved = true;
                    }
                }
            }
            if (!moved) step /= 2;
        }
        return new Peak(x, z, best);
    }
}
