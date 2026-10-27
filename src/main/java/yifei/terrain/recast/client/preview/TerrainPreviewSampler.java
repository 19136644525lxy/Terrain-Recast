package yifei.terrain.recast.client.preview;

import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.RealCellLookup;

/**
 * 创建世界界面的地形预览采样器——直接读 TRC 的连续场画高度图，<b>不生成任何区块</b>。
 *
 * <p>与参考实现（world-preview）的区别：那边要起 DummyServer + 真的生成区块再采样，
 * 90 个文件、多套 Mixin；本模组的地形本身就是连续场，一个 {@link RealCellLookup}
 * 在后台线程跑一遍即可，几十毫秒出图，且不碰任何原版生成内部结构。
 *
 * <p>配色按本模组的真实高度分布标定（实测中位 340~410 格、p90 约 750、峰值 1000+），
 * 而不是按"海平面附近为主"的常规地形——否则整张图会糊成岩石色。
 *
 * <p>线程契约：{@link #sample(long)} 无全局状态，可在任意后台线程调用；
 * 返回的 int[] 为行主序 ARGB，交给渲染线程转 {@code NativeImage}。
 */
public final class TerrainPreviewSampler {

    /** 预览图宽度（像素）。接近页签可用宽度，参考实现也是把地图铺满页签。 */
    public static final int WIDTH = 640;
    /** 预览图高度（像素）。高度受页签区域限制（480p 下可用高度约 370）。 */
    public static final int HEIGHT = 300;
    /** 每像素代表的方块数。640×4≈2560 格宽，约 6.7 个板块尺度。 */
    public static final int BLOCKS_PER_PIXEL = 4;
    /**
     * 取景中心。不要用原点：{@code RealCellLookup} 在出生点半径 1400 内强制造陆
     * （spawn land expansion），原点附近整片都是陆地，预览会变成"一坨山"看不到海岸。
     *
     * <p>Z 也不能离原点太远：温度场按 {@code |z|/6000} 衰减，{@code (6000,6000)} 已经是极地，
     * 整张图会变成灰绿的苔原。沿赤道方向偏到 X=6000 既避开造陆、又保持温带气候。
     */
    public static final int CENTER_X = 6000;
    public static final int CENTER_Z = 0;

    /** 归一化高度色带停靠点与颜色。 */
    private static final float[] REL_STOPS = {0f, 0.06f, 0.28f, 0.55f, 0.72f, 0.85f, 0.94f, 1f};
    private static final int[] REL_COLORS = {
            0xE8DCB0, // 沙滩
            0xA8C46A, // 浅草
            0x5E9448, // 林地
            0x6E8C46, // 高地草甸
            0x8C8452, // 干草坡
            0x8A7256, // 裸土岩
            0xA8A29A, // 高山石
            0xF4F7FA  // 雪线
    };

    /** 海洋配色：浅海到深渊。 */
    private static final int OCEAN_SHALLOW = 0x6FAECB;
    private static final int OCEAN_DEEP = 0x1B3A5C;
    /** 海洋配色达到最深所需的深度（格）。取大一些，海沟不至于把海面画成"蓝色蛛网"。 */
    private static final float OCEAN_DEPTH_SPAN = 260f;

    /** 山体阴影：坡度夸张系数与光源方向（归一化后的西北偏上光）。 */
    private static final float SLOPE_SCALE = 1f / 55f;
    private static final float LIGHT_X = -0.55f;
    private static final float LIGHT_Y = 0.70f;
    private static final float LIGHT_Z = -0.45f;

    private TerrainPreviewSampler() {}

    /**
     * 采样一帧。
     *
     * @param seed 世界种子（与创建世界界面的种子一致，保证预览所见即所得）
     * @return 行主序 ARGB 像素
     */
    public static int[] sample(long seed) {
        return colorize(sampleFrame(seed));
    }

    /**
     * 只采样高度场（不配色）。行主序，单位：格（相对海平面）。
     *
     * <p>单独暴露出来，供离线调色/调试工具复用，也方便以后做鼠标悬停读数。
     */
    public static float[] sampleHeights(long seed) {
        return sampleFrame(seed).heights();
    }

    /** 一帧的原始场数据：高度 / 湿度（湿度只用于淡淡的色相差异）。 */
    private record Frame(float[] heights, float[] moistures) {}

    private static Frame sampleFrame(long seed) {
        int size = WIDTH * HEIGHT;
        float[] heights = new float[size];
        float[] moistures = new float[size];
        RealCellLookup lookup = new RealCellLookup(seed);
        try {
            int halfW = WIDTH / 2;
            int halfH = HEIGHT / 2;
            for (int py = 0; py < HEIGHT; py++) {
                int blockZ = CENTER_Z + (py - halfH) * BLOCKS_PER_PIXEL;
                for (int px = 0; px < WIDTH; px++) {
                    int blockX = CENTER_X + (px - halfW) * BLOCKS_PER_PIXEL;
                    int i = py * WIDTH + px;
                    heights[i] = (float) lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, blockX, blockZ);
                    if (heights[i] >= 0f) {
                        moistures[i] = (float) lookup.sampleField(Cell.Field.MOISTURE, blockX, blockZ);
                    }
                }
            }
        } finally {
            lookup.close();
        }
        return new Frame(heights, moistures);
    }

    /** 高度场 → 像素：海陆分色 + 按本图分位数归一化的色带 + 坡度露岩 + 法线光照。 */
    private static int[] colorize(Frame frame) {
        float[] heights = frame.heights();
        int size = heights.length;
        int[] out = new int[size];

        // 先算坡度，并收集陆地的高度/坡度样本用于分位数标定
        float[] slopes = new float[size];
        int landCount = 0;
        for (int py = 0; py < HEIGHT; py++) {
            for (int px = 0; px < WIDTH; px++) {
                int i = py * WIDTH + px;
                if (heights[i] < 0f) continue;
                slopes[i] = (float) Math.hypot(slopeX(heights, px, py), slopeZ(heights, px, py));
                landCount++;
            }
        }
        float[] landHeights = new float[Math.max(1, landCount)];
        float[] landSlopes = new float[Math.max(1, landCount)];
        int k = 0;
        for (int i = 0; i < size; i++) {
            if (heights[i] < 0f) continue;
            landHeights[k] = heights[i];
            landSlopes[k] = slopes[i];
            k++;
        }
        java.util.Arrays.sort(landHeights);
        java.util.Arrays.sort(landSlopes);
        float hLow = percentile(landHeights, 0.05f);
        float hHigh = percentile(landHeights, 0.97f);
        float slopeLow = percentile(landSlopes, 0.70f);
        float slopeHigh = percentile(landSlopes, 0.98f);
        float hSpan = Math.max(1f, hHigh - hLow);
        float slopeSpan = Math.max(0.5f, slopeHigh - slopeLow);

        float[] localMean = boxBlur(heights, 5);
        for (int py = 0; py < HEIGHT; py++) {
            for (int px = 0; px < WIDTH; px++) {
                int i = py * WIDTH + px;
                float e = heights[i];
                if (e < 0f) {
                    out[i] = lerpColor(OCEAN_SHALLOW, OCEAN_DEEP,
                            clamp(e / -OCEAN_DEPTH_SPAN, 0f, 1f));
                    continue;
                }
                // 本图归一化：保证任何种子都能铺满"低地→山脊→雪峰"的完整层次
                float relE = clamp((e - hLow) / hSpan, 0f, 1f);
                float relSlope = clamp((slopes[i] - slopeLow) / slopeSpan, 0f, 1f);
                float ao = clamp(1f + (e - localMean[i]) * 0.004f, 0.82f, 1.10f);
                int base = landColor(relE, relSlope, frame.moistures()[i]);
                out[i] = shade(base, relief(slopeX(heights, px, py), slopeZ(heights, px, py)) * ao
                        * grain(px, py, relE));
            }
        }
        // 海岸浪花：贴着陆地的那一圈水面提亮，海岸线更清晰
        for (int py = 0; py < HEIGHT; py++) {
            for (int px = 0; px < WIDTH; px++) {
                int i = py * WIDTH + px;
                if (heights[i] >= 0f) continue;
                if (nearLand(heights, px, py)) {
                    out[i] = blend(out[i], 0xCFE8F2, 0.45f);
                }
            }
        }
        return out;
    }

    private static float percentile(float[] sorted, float q) {
        if (sorted.length == 0) return 0f;
        int index = Math.min(sorted.length - 1, Math.max(0, (int) (sorted.length * q)));
        return sorted[index];
    }

    /**
     * 陆地配色。关键点：<b>按坡度露岩，而不是按海拔涂棕</b>。
     *
     * <p>游戏内实际地表是"森林/草地铺满 + 陡坡露出岩壁"（见 run/screenshots 里的实机截图）。
     * 早先按海拔把 300 格以上一律刷成棕色，整张图就成了土黄疙瘩；改成坡度驱动后，
     * 平原保持各气候带的草色，山脊才显出岩壁，雪线再单独叠加。
     */
    /**
     * 陆地配色：输入都是本图归一化后的 0~1 值，所以任何种子都能铺满完整层次。
     *
     * <p>关键点：<b>陡坡露岩而不是按海拔涂棕</b>——游戏内实际地表是"植被铺满 + 陡坡露岩"
     * （见 run/screenshots 的实机截图），早先按绝对海拔上色会把整张图画成土黄疙瘩。
     */
    private static int landColor(float relE, float relSlope, float moist) {
        int base = ramp(relE);
        // 湿度微调：湿 → 偏青绿，干 → 偏黄
        int tint = lerpColor(0xC9B36A, 0x4E8C4C, clamp((moist + 0.9f) / 1.8f, 0f, 1f));
        base = blend(base, tint, 0.18f);
        // 陡坡露岩：坡度进入本图最高 30% 后渐显
        float rock = clamp((relSlope - 0.25f) / 0.65f, 0f, 1f) * 0.75f;
        return blend(base, 0x8C7A5E, rock);
    }

    /** 归一化高度 → 基础色带（沙滩→草原→林地→高地→干坡→裸岩→高山石→雪）。 */
    private static int ramp(float relE) {
        for (int i = 1; i < REL_STOPS.length; i++) {
            if (relE <= REL_STOPS[i]) {
                float t = (relE - REL_STOPS[i - 1]) / (REL_STOPS[i] - REL_STOPS[i - 1]);
                return lerpColor(REL_COLORS[i - 1], REL_COLORS[i], t);
            }
        }
        return REL_COLORS[REL_COLORS.length - 1];
    }

    /** 坡度（方块/方块）：中心差分。 */
    private static float slopeX(float[] heights, int px, int py) {
        int x0 = Math.max(0, px - 1);
        int x1 = Math.min(WIDTH - 1, px + 1);
        return (heights[py * WIDTH + x1] - heights[py * WIDTH + x0]) / (2f * BLOCKS_PER_PIXEL);
    }

    private static float slopeZ(float[] heights, int px, int py) {
        int y0 = Math.max(0, py - 1);
        int y1 = Math.min(HEIGHT - 1, py + 1);
        return (heights[y1 * WIDTH + px] - heights[y0 * WIDTH + px]) / (2f * BLOCKS_PER_PIXEL);
    }

    /** 4 邻域里是否有陆地。 */
    private static boolean nearLand(float[] heights, int px, int py) {
        if (px > 0 && heights[py * WIDTH + px - 1] >= 0f) return true;
        if (px < WIDTH - 1 && heights[py * WIDTH + px + 1] >= 0f) return true;
        if (py > 0 && heights[(py - 1) * WIDTH + px] >= 0f) return true;
        return py < HEIGHT - 1 && heights[(py + 1) * WIDTH + px] >= 0f;
    }

    /** 像素级确定性颗粒：给平滑场加一点表面质感，越靠雪线越明显。 */
    private static float grain(int px, int py, float relE) {
        int h = px * 374761393 + py * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        float n = ((h & 0xFFFF) / 65535f) - 0.5f;
        float amp = 0.030f + 0.045f * clamp(relE, 0f, 1f);
        return 1f + n * amp;
    }

    /** 线性混色。 */
    private static int blend(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) (((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) ((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 可分离盒式模糊：给 AO 一个"周边平均高度"参照。 */
    private static float[] boxBlur(float[] src, int radius) {
        float[] tmp = new float[src.length];
        float[] dst = new float[src.length];
        int window = radius * 2 + 1;
        for (int py = 0; py < HEIGHT; py++) {
            float sum = 0f;
            for (int x = -radius; x <= radius; x++) {
                sum += src[py * WIDTH + clampIndex(x, 0, WIDTH - 1)];
            }
            for (int px = 0; px < WIDTH; px++) {
                tmp[py * WIDTH + px] = sum / window;
                sum -= src[py * WIDTH + clampIndex(px - radius, 0, WIDTH - 1)];
                sum += src[py * WIDTH + clampIndex(px + radius + 1, 0, WIDTH - 1)];
            }
        }
        for (int px = 0; px < WIDTH; px++) {
            float sum = 0f;
            for (int y = -radius; y <= radius; y++) {
                sum += tmp[clampIndex(y, 0, HEIGHT - 1) * WIDTH + px];
            }
            for (int py = 0; py < HEIGHT; py++) {
                dst[py * WIDTH + px] = sum / window;
                sum -= tmp[clampIndex(py - radius, 0, HEIGHT - 1) * WIDTH + px];
                sum += tmp[clampIndex(py + radius + 1, 0, HEIGHT - 1) * WIDTH + px];
            }
        }
        return dst;
    }

    private static int clampIndex(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    /**
     * 法线光照：坡度换算成法线后与固定光源做 Lambert。
     *
     * <p>早先版本直接用"左/上邻格高差 × 系数"，在本模组 600+ 格的坡度上会瞬间饱和，
     * 渲染出大片死白的三角面；改为按 {@link #SLOPE_SCALE} 归一化后不再有硬边。
     */
    private static float relief(float dx, float dz) {
        float nx = -dx * SLOPE_SCALE;
        float nz = -dz * SLOPE_SCALE;
        float len = (float) Math.sqrt(nx * nx + 1f + nz * nz);
        float lambert = (nx * LIGHT_X + LIGHT_Y + nz * LIGHT_Z) / len;
        // 0.70~1.08：保持层次但不把颜色冲白
        return 0.70f + 0.38f * clamp(lambert, 0f, 1f);
    }

    /** 两色线性插值（RGB 通道各插，alpha 固定 FF）。 */
    private static int lerpColor(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 明暗调整（sRGB 通道各自乘系数后夹取）。 */
    private static int shade(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}
