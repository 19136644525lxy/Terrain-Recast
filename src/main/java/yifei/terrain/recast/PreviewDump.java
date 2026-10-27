package yifei.terrain.recast;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import yifei.terrain.recast.client.preview.TerrainPreviewSampler;
import yifei.terrain.recast.worldgen.biome.WhittakerBiome;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.RealCellLookup;

/**
 * 临时代码：把预览采样结果导出成 PNG，便于离线调色/验证 L3 河网。验证后删除。
 */
public final class PreviewDump {

    private PreviewDump() {}

    public static void main(String[] args) throws Exception {
        long[] seeds = {1L, 42L, 12345L, -8068941305560221624L};
        File dir = new File("temp/preview_dump");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        for (long seed : seeds) {
            long start = System.currentTimeMillis();
            int[] pixels = TerrainPreviewSampler.sample(seed);
            long elapsed = System.currentTimeMillis() - start;
            BufferedImage image = new BufferedImage(
                    TerrainPreviewSampler.WIDTH, TerrainPreviewSampler.HEIGHT, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, TerrainPreviewSampler.WIDTH, TerrainPreviewSampler.HEIGHT,
                    pixels, 0, TerrainPreviewSampler.WIDTH);
            File out = new File(dir, "seed_" + seed + ".png");
            ImageIO.write(image, "png", out);
            System.out.println("wrote " + out + " in " + elapsed + " ms");
            printHeightStats(seed);
            dumpBiomes(dir, seed);
            dumpLandforms(dir, seed);
        }
    }

    /** 临时：把 L1 大地貌画出来——裂谷红、火山弧橙、高原黄、平原灰、海蓝。 */
    private static void dumpLandforms(File dir, long seed) throws Exception {
        final int w = 480;
        final int h = 240;
        final int bpp = 8;
        final int centerX = 6000;
        final int centerZ = 0;
        int[] px = new int[w * h];
        int[] counts = new int[4]; // plain / plateau / arc / rift
        long start = System.currentTimeMillis();
        RealCellLookup lookup = new RealCellLookup(seed);
        try {
            for (int y = 0; y < h; y++) {
                int bz = centerZ + (y - h / 2) * bpp;
                for (int x = 0; x < w; x++) {
                    int bx = centerX + (x - w / 2) * bpp;
                    double code = lookup.sampleField(Cell.Field.LANDFORM, bx, bz);
                    int color;
                    if (code > 0.55) {
                        color = 0xFFE07A2B; // 火山弧
                        counts[2]++;
                    } else if (code > 0.2) {
                        color = 0xFFE8D07A; // 高原
                        counts[1]++;
                    } else if (code < -0.2) {
                        color = 0xFFC0392B; // 裂谷
                        counts[3]++;
                    } else {
                        color = 0xFF7F8C8D;
                        counts[0]++;
                    }
                    double height = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, bx, bz);
                    if (height < 0) color = 0xFF1E3A55;
                    px[y * w + x] = color;
                }
            }
        } finally {
            lookup.close();
        }
        long elapsed = System.currentTimeMillis() - start;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(image, "png", new File(dir, "landform_" + seed + ".png"));
        System.out.printf("landform seed=%d  %d ms  plain=%d plateau=%d arc=%d rift=%d%n",
                seed, elapsed, counts[0], counts[1], counts[2], counts[3]);
    }

    /** 临时：把 L6 群系分类画出来（每种群系一个颜色），检查气候带是否合理。 */
    private static void dumpBiomes(File dir, long seed) throws Exception {
        final int w = 480;
        final int h = 240;
        final int bpp = 8;
        final int centerX = 6000;
        final int centerZ = 0;
        int[] px = new int[w * h];
        int[] counts = new int[WhittakerBiome.COUNT];
        double[] temps = new double[w * h];
        long start = System.currentTimeMillis();
        RealCellLookup lookup = new RealCellLookup(seed);
        try {
            for (int y = 0; y < h; y++) {
                int bz = centerZ + (y - h / 2) * bpp;
                for (int x = 0; x < w; x++) {
                    int bx = centerX + (x - w / 2) * bpp;
                    int biome = (int) Math.round(lookup.sampleField(Cell.Field.BIOME, bx, bz));
                    counts[Math.max(0, Math.min(counts.length - 1, biome))]++;
                    px[y * w + x] = BIOME_COLORS[Math.max(0, Math.min(BIOME_COLORS.length - 1, biome))];
                    temps[y * w + x] = lookup.sampleField(Cell.Field.TEMPERATURE, bx, bz);
                }
            }
        } finally {
            lookup.close();
        }
        long elapsed = System.currentTimeMillis() - start;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, px, 0, w);
        File out = new File(dir, "biomes_" + seed + ".png");
        ImageIO.write(image, "png", out);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(WhittakerBiome.nameOf(i)).append('=')
                    .append(String.format("%.0f%%", 100.0 * counts[i] / (w * h)));
        }
        System.out.printf("biomes seed=%d  %d ms  %s%n", seed, elapsed, sb);
        java.util.Arrays.sort(temps);
        int tn = temps.length;
        System.out.printf("   temp: p10=%.2f p25=%.2f p50=%.2f p75=%.2f p90=%.2f p99=%.2f%n",
                temps[(int) (tn * 0.10)], temps[(int) (tn * 0.25)], temps[(int) (tn * 0.50)],
                temps[(int) (tn * 0.75)], temps[(int) (tn * 0.90)], temps[(int) (tn * 0.99)]);
    }

    /** 群系配色（与 WhittakerBiome 的 id 对齐）。 */
    private static final int[] BIOME_COLORS = {
            0xFF2C5578, // OCEAN
            0xFFE0D2A0, // BEACH
            0xFFA8B0AA, // TUNDRA
            0xFF4F6B4A, // TAIGA
            0xFF8FB055, // GRASSLAND
            0xFF356B32, // FOREST
            0xFFB3A44E, // SAVANNA
            0xFFD8C88A, // DESERT
            0xFF8C8C84, // ALPINE
            0xFFF2F5F8  // SNOW
    };

    /** 临时：打印高度分布，用于重新标定色带。 */
    private static void printHeightStats(long seed) {
        float[] hs = TerrainPreviewSampler.sampleHeights(seed);
        float[] sorted = hs.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        System.out.printf(
                "seed=%d  p1=%.0f p10=%.0f p25=%.0f p50=%.0f p75=%.0f p90=%.0f p99=%.0f max=%.0f  oceanPct=%.1f%%%n",
                seed,
                sorted[(int) (n * 0.01)], sorted[(int) (n * 0.10)], sorted[(int) (n * 0.25)],
                sorted[(int) (n * 0.50)], sorted[(int) (n * 0.75)], sorted[(int) (n * 0.90)],
                sorted[(int) (n * 0.99)], sorted[n - 1],
                100.0 * countBelow(hs, 0f) / n);
        printSlopeStats(seed, hs);
    }

    /** 临时：打印坡度分布（方块/方块），用于标定"陡坡露岩"的阈值。 */
    private static void printSlopeStats(long seed, float[] hs) {
        int w = TerrainPreviewSampler.WIDTH;
        int h = TerrainPreviewSampler.HEIGHT;
        int bpp = TerrainPreviewSampler.BLOCKS_PER_PIXEL;
        float[] slopes = new float[hs.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1);
                int x1 = Math.min(w - 1, x + 1);
                int y0 = Math.max(0, y - 1);
                int y1 = Math.min(h - 1, y + 1);
                float dx = (hs[y * w + x1] - hs[y * w + x0]) / (2f * bpp);
                float dz = (hs[y1 * w + x] - hs[y0 * w + x]) / (2f * bpp);
                slopes[y * w + x] = (float) Math.hypot(dx, dz);
            }
        }
        java.util.Arrays.sort(slopes);
        int n = slopes.length;
        System.out.printf(
                "   slope: p10=%.2f p25=%.2f p50=%.2f p75=%.2f p90=%.2f p95=%.2f p99=%.2f max=%.2f%n",
                slopes[(int) (n * 0.10)], slopes[(int) (n * 0.25)], slopes[(int) (n * 0.50)],
                slopes[(int) (n * 0.75)], slopes[(int) (n * 0.90)], slopes[(int) (n * 0.95)],
                slopes[(int) (n * 0.99)], slopes[n - 1]);
    }

    private static int countBelow(float[] values, float limit) {
        int c = 0;
        for (float v : values) {
            if (v < limit) c++;
        }
        return c;
    }
}
