package yifei.terrain.recast.worldgen.cell;

import yifei.terrain.recast.api.CellDataView;
import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.api.event.RecastEvents;
import yifei.terrain.recast.api.modifier.ModifierPipeline;
import yifei.terrain.recast.concurrent.LifecycleBoundCache;
import yifei.terrain.recast.concurrent.ObjectPool;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.util.ValueNoise2D;
import yifei.terrain.recast.util.WorleyDistance;
import yifei.terrain.recast.worldgen.biome.WhittakerBiome;

/**
 * CellLookup 的真实实现——软 Voronoi（distance-weighted blend）连续场。
 *
 * <p>核心结构：
 * <ul>
 *   <li>板块网格 {@code PLATE_CELL_SIZE} 方块一格，每格一个 Voronoi 板块中心</li>
 *   <li>板块属性（L0/L1/L2/L5）按<b>板块坐标</b>缓存，不是按区块缓存</li>
 *   <li>采样时取最近 3 个板块，按距离权重混合 → 连续场，无区块级阶跃</li>
 *   <li>板块边界附近叠加构造抬升/下降（陆-陆褶皱山、陆-洋海沟、洋-洋洋中脊）</li>
 * </ul>
 *
 * <p>线程安全：缓存用 ConcurrentHashMap；Cell 实例只读使用（写出后不再修改），
 * 可被多线程同时读取。
 */
public final class RealCellLookup implements CellLookup {

    /** 缓存上限。按板块粒度，4096 条足以覆盖上万方块的探索范围。 */
    private static final int CACHE_MAX = 4096;

    /** 板块网格尺度（方块）。384 方块 ≈ 24 区块。 */
    public static final int PLATE_CELL_SIZE = 384;

    /** 板块边界影响带宽度（方块）：碰撞造山/海沟在此范围内衰减到 0。 */
    private static final double EDGE_SPAN = PLATE_CELL_SIZE * 0.55;

    /** 板块内部次级山脉：低频噪声波长（方块）。 */
    private static final double INTERIOR_SCALE = 900.0;

    /** Cell.landformType 的陆/洋标记。 */
    private static final int LANDFORM_PLATEAU = 1;
    private static final int LANDFORM_DEEP_OCEAN = 5;

    // ===== L1 大地貌（高原 / 裂谷 / 火山弧）=====

    /** 边界类型噪声波长：>0 张裂、<0 汇聚，沿板块边界分段变化。 */
    private static final double BOUNDARY_TYPE_SCALE = 2400.0;
    /** 高原分布噪声波长。 */
    private static final double PLATEAU_SCALE = 1500.0;
    /** 群系族（原版/自定义）选择噪声波长。太小会碎，太大会整片只有一族，700 格约 1.5 个周期/千米。 */
    private static final double BIOME_FAMILY_SCALE = 700.0;
    /** 山体区域掩码波长（方块）：决定大片山区的位置。 */
    private static final double MASSIF_SCALE = 1600.0;
    /**
     * 地表高度上限（相对海平面）。世界最高 Y=2031，海平面约 63，
     * 再留 100 格余量避免顶到建筑上限后地形被截平。
     */
    private static final double MAX_TERRAIN_HEIGHT = 1860.0;
    /** 软上限起点：低于这个高度完全不受影响。 */
    private static final double SOFT_CAP_KNEE = 1200.0;
    /** 高原台面高度（相对海平面，方块）。 */
    private static final double PLATEAU_LEVEL = 210.0;
    /** 裂谷下切深度（方块）。 */
    private static final double RIFT_DEPTH = 75.0;
    /** 火山弧抬升高度（方块）。 */
    private static final double ARC_HEIGHT = 210.0;

    /** LANDFORM 编码：给群系表的 weirdness 分带用。 */
    public static final double LANDFORM_CODE_RIFT = -0.6;
    public static final double LANDFORM_CODE_PLAIN = 0.0;
    public static final double LANDFORM_CODE_PLATEAU = 0.35;
    public static final double LANDFORM_CODE_ARC = 0.7;

    /** 出生点附近强制为大陆的板块半径（板块数），保证出生区块是陆地。 */
    private static final int SPAWN_SAFE_PLATES = 1;

    /** 距离权重分母的偏移量，防止中心点处除零。 */
    private static final double EPS = 1.0;

    // 陆/洋基准高度（方块，相对海平面的偏移）
    private static final double CONTINENT_BASE = 32.0;
    private static final double OCEAN_BASE = -35.0;

    /** 大陆度噪声波长（方块）。必须明显小于视野尺度，否则整个可见区域的大陆度恒定、地形趋平。 */
    private static final double CONTINENT_SCALE = 1200.0;
    /** 山系脊线噪声波长（方块）。 */
    private static final double MOUNTAIN_SCALE = 620.0;
    /** 出生地大陆膨胀强度：半径内把陆地程度平滑抬高。 */
    private static final double SPAWN_LAND_STRENGTH = 0.6;
    /** 气候低频扰动波长（方块）：打破纯纬向带状，让同纬度也有冷暖差异。 */
    private static final double CLIMATE_JITTER_SCALE = 2400.0;
    /** 湿度噪声波长（方块）。 */
    private static final double MOISTURE_SCALE = 1700.0;
    /** 怪异度噪声波长（方块）。 */
    private static final double WEIRDNESS_SCALE = 1100.0;

    /** 侵蚀包络噪声波长（方块）：0=年轻地貌（尖锐山脊） 1=老年地貌（磨圆+填谷）。 */
    private static final double EROSION_ENVELOPE_SCALE = 1900.0;
    /** 老年地貌谷底的最大填高（方块）。 */
    private static final double EROSION_FILL = 7.0;

    /** 岩性细分噪声波长（方块），按板块中心采样决定岩性种类。 */
    private static final double LITHO_SCALE = 900.0;

    // ===== 可配置地形系数（构造时从 ConfigHandler 快照，热路径直接字段读取） =====

    private final double coastSpan;
    private final double latitudeScale;
    private final double mountainAmp;
    private final double interiorAmp;
    private final double abyssAmp;
    private final double trenchDepth;
    private final double erosionRound;
    private final double minContinentHeight;
    private final double spawnLandRadius;
    /** 全局垂直缩放：一次调高山/深海，参考 TerraForged 的 globalVerticalScale。 */
    private final double verticalScale;

    private final LifecycleBoundCache<Long, Cell> cache = new LifecycleBoundCache<>(CACHE_MAX);
    private final ObjectPool<Cell> pool = new ObjectPool<>(Cell::new, Cell::reset);
    private final WorleyDistance worley;
    private final ValueNoise2D terrainNoise;
    private final long worldSeed;

    public RealCellLookup(long worldSeed) {
        this.worldSeed = worldSeed;
        this.worley = new WorleyDistance(worldSeed);
        this.terrainNoise = new ValueNoise2D(worldSeed ^ 0x7A3C_91B5_2E4D_806FL);
        pool.prefill(64);

        // 从配置读取地形系数，无配置/未知 key 时回落默认字面量
        this.coastSpan = ConfigHandler.tuning("terrain.coast_span", 30.0);
        this.latitudeScale = ConfigHandler.tuning("climate.latitude_scale", 6000.0);
        this.mountainAmp = ConfigHandler.tuning("terrain.mountain_amp", 1100.0);
        this.interiorAmp = ConfigHandler.tuning("terrain.interior_amp", 55.0);
        this.abyssAmp = ConfigHandler.tuning("terrain.abyss_amp", 16.0);
        this.trenchDepth = ConfigHandler.tuning("terrain.trench_depth", 60.0);
        this.erosionRound = ConfigHandler.tuning("terrain.erosion_round", 0.55);
        this.minContinentHeight = ConfigHandler.tuning("terrain.min_continent_height", 8.0);
        this.spawnLandRadius = ConfigHandler.tuning("general.spawn_land_radius", 1400.0);
        this.verticalScale = ConfigHandler.tuning("terrain.vertical_scale", 1.0);
    }

    /** 合成板块坐标的缓存 key。 */
    private static long plateKey(int px, int pz) {
        return ((long) px << 32) | (pz & 0xFFFFFFFFL);
    }

    // ===== 离散读取 =====

    @Override
    public Cell getCell(int chunkX, int chunkZ) {
        int bx = (chunkX << 4) + 8;
        int bz = (chunkZ << 4) + 8;
        return nearestCell(bx, bz);
    }

    @Override
    public Cell computeIfAbsent(int chunkX, int chunkZ) {
        return getCell(chunkX, chunkZ);
    }

    @Override
    public Cell nearestCell(int blockX, int blockZ) {
        return plateCell(Math.floorDiv(blockX, PLATE_CELL_SIZE), Math.floorDiv(blockZ, PLATE_CELL_SIZE));
    }

    @Override
    public boolean isEmpty() { return cache.isEmpty(); }

    @Override
    public void close() {
        cache.close();
        pool.close();
    }

    // ===== 连续场采样（DensityFunction 热路径） =====

    /**
     * 采样入口：先算原始场值，再交给 DataModifier 责任链做覆盖。
     *
     * <p>责任链为空时直接返回原值——未安装附属的服务器在热路径上零额外开销、零分配。
     * 非空时把最近板块的只读 Cell 视图一并传下去，供附属做跨字段决策。
     */
    @Override
    public double sampleField(Cell.Field field, int blockX, int blockZ) {
        double raw = sampleRawField(field, blockX, blockZ);
        ModifierPipeline pipeline = TerrainRecastApi.getPipeline();
        if (pipeline.isEmpty()) {
            return raw;
        }
        CellDataView cell = TerrainRecastApi.queryCell(blockX >> 4, blockZ >> 4);
        return pipeline.apply(field, blockX, blockZ, raw, cell);
    }

    /**
     * 三近板块按距离加权混合，得到连续字段值（不含 API 介入）。
     *
     * <p>PLATE_BOUNDARY_DISTANCE 直接用 F2−F1 连续距离场（不是板块属性），
     * 这是 BigGlobe 的核心做法：距离本身随位置连续变化，无需插值包装即可光滑。
     */
    private double sampleRawField(Cell.Field field, int blockX, int blockZ) {
        WorleyDistance.PlateNeighbourhood nb = worley.sampleNeighbourhood(blockX, blockZ, PLATE_CELL_SIZE);
        double gap = nb.d2() - nb.d1();

        // 边界系数：1 = 正压在板块边界上，0 = 板块深处
        double edge = smoothstep(Math.max(0.0, 1.0 - gap / EDGE_SPAN));

        if (field == Cell.Field.PLATE_BOUNDARY_DISTANCE) {
            return Math.min(1.0, gap / EDGE_SPAN);
        }
        // 岩性是离散量：跨板块用最近板块的整数值，绝不软混合成小数（矿种绑定依赖整数值）。
        if (field == Cell.Field.LITHOLOGY) {
            Cell nearest = plateCell(Math.floorDiv(blockX, PLATE_CELL_SIZE), Math.floorDiv(blockZ, PLATE_CELL_SIZE));
            return nearest != null ? nearest.lithology : 0;
        }

        // 气候与高度全部走连续场，不读板块身份（避免 Voronoi 顶点换人造成的阶跃）
        if (field == Cell.Field.CONTINENTALNESS) {
            // 直接绑定地形高度：h>0=陆地、h<0=海洋。
            // 海/陆群系闸门是 continentalness（OverworldBiomeBuilder 里
            // 深海[-1.05,-0.455]/海洋[-0.455,-0.19]/海岸[-0.19,-0.11]/内陆[-0.11,1]），
            // 只有让它与实际海拔一致，才不会出现「海洋群系盖在陆地上」。
            //
            // 用**解析基面高度**而不是区域高度场：这个字段会被群系查询/出生点选址/
            // /locate 大量采样，走水文网格会触发建区，把指令卡死。
            double h = baseTerrainHeight(blockX, blockZ, edge);
            return clamp(h / coastSpan, -1.0, 1.0);
        }
        if (field == Cell.Field.EROSION) {
            // 原版语义：-1 = 山高谷深，1 = 平坦。只用山脊项，不再掺河道强度。
            double ridgeE = 0.55 - 1.0 * ridgeAt(blockX, blockZ) * (0.45 + 0.55 * edge);
            return clamp(ridgeE, -1.0, 1.0);
        }
        if (field == Cell.Field.TEMPERATURE) {
            return temperature(blockX, blockZ, edge);
        }
        if (field == Cell.Field.MOISTURE) {
            return moisture(blockX, blockZ);
        }
        if (field == Cell.Field.LANDFORM) {
            // L1 大地貌编码：与地形塑形共用同一套掩码
            double landness = landnessAt(blockX, blockZ);
            if (landness < 0.5) return LANDFORM_CODE_PLAIN; // 海里不标地貌
            if (riftMaskAt(blockX, blockZ, edge) > 0.30) return LANDFORM_CODE_RIFT;
            if (arcMaskAt(blockX, blockZ, edge) > 0.30) return LANDFORM_CODE_ARC;
            if (plateauMaskAt(blockX, blockZ, edge, landness) > 0.45) return LANDFORM_CODE_PLATEAU;
            return LANDFORM_CODE_PLAIN;
        }
        if (field == Cell.Field.WEIRDNESS) {
            return clamp(terrainNoise.fbm(blockX, blockZ, WEIRDNESS_SCALE, 3, 2.0, 0.5) * 1.4, -1.0, 1.0);
        }
        if (field == Cell.Field.BIOME_FAMILY) {
            // 群系族选择器：低频噪声，正负各约一半 → 原版/自定义群系均匀交错
            return terrainNoise.fbm(blockX, blockZ, BIOME_FAMILY_SCALE, 2, 2.0, 0.5);
        }
        if (field == Cell.Field.RIVER_BAND) {
            // 水系改动已下线：河带恒为 0（ridges 覆写仍引用该字段，保持数据包可加载）。
            return 0;
        }
        if (field == Cell.Field.RIVER_FLOW) {
            return 0;
        }
        if (field == Cell.Field.BIOME) {
            // L6：按本点的温度/湿度/海拔做 Whittaker 查表（不是板块级粗分类）
            double h = terrainHeight(blockX, blockZ, edge);
            double t = temperature(blockX, blockZ, edge);
            double m = moisture(blockX, blockZ);
            return WhittakerBiome.classify(t, m, h);
        }
        if (field == Cell.Field.TERRAIN_HEIGHT) {
            return terrainHeight(blockX, blockZ, edge);
        }

        Cell c1 = plateCell(nb.px1(), nb.pz1());
        Cell c2 = plateCell(nb.px2(), nb.pz2());
        Cell c3 = plateCell(nb.px3(), nb.pz3());
        double w1 = invPow(nb.d1()), w2 = invPow(nb.d2()), w3 = invPow(nb.d3());
        double base = (w1 * field.get(c1) + w2 * field.get(c2) + w3 * field.get(c3)) / (w1 + w2 + w3);

        if (field == Cell.Field.STEEPNESS) {
            return Math.min(1.0, base + edge * 0.75);
        }
        return base;
    }

    /** 陆地程度 0=深海 1=内陆。连续量，含出生地大陆膨胀。 */
    private double landnessAt(int blockX, int blockZ) {
        double cont = terrainNoise.fbm(blockX, blockZ, CONTINENT_SCALE, 3, 2.2, 0.5);
        double landness = smoothstep(clamp((cont + 0.10) / 0.30, 0.0, 1.0));

        // 出生地大陆膨胀：半径内把陆地程度平滑抬高，半径外自然衰减到原始值
        double dist = Math.sqrt((double) blockX * blockX + (double) blockZ * blockZ);
        double spawn = smoothstep(clamp(1.0 - dist / spawnLandRadius, 0.0, 1.0)) * SPAWN_LAND_STRENGTH;
        return 1.0 - (1.0 - landness) * (1.0 - spawn);
    }

    /** 山脊强度 0~1（ridged 噪声）。 */
    private double ridgeAt(int blockX, int blockZ) {
        return 1.0 - Math.abs(terrainNoise.fbm(blockX, blockZ, MOUNTAIN_SCALE, 3, 2.0, 0.5));
    }

    // ===== L1 大地貌掩码（地形塑形与群系分带共用同一套，避免两处漂移）=====

    /** 边界类型噪声：>0 张裂、<0 汇聚。热路径上只算一次，供两个掩码共用。 */
    private double boundaryTypeAt(int blockX, int blockZ) {
        return terrainNoise.fbm(blockX, blockZ, BOUNDARY_TYPE_SCALE, 2, 2.0, 0.5);
    }

    /** 张裂边界掩码 0~1（裂谷）。 */
    private double riftMaskAt(int blockX, int blockZ, double edge) {
        return riftMaskAt(boundaryTypeAt(blockX, blockZ), edge);
    }

    private static double riftMaskAt(double type, double edge) {
        double divergent = smoothstep(clamp((type - 0.02) * 4.0, 0.0, 1.0));
        return clamp(edge * divergent, 0.0, 1.0);
    }

    /** 汇聚边界掩码 0~1（火山弧）。 */
    private double arcMaskAt(int blockX, int blockZ, double edge) {
        return arcMaskAt(boundaryTypeAt(blockX, blockZ), edge);
    }

    private static double arcMaskAt(double type, double edge) {
        double convergent = smoothstep(clamp((-type - 0.02) * 4.0, 0.0, 1.0));
        return clamp(edge * convergent, 0.0, 1.0);
    }

    /** 高原掩码 0~1：板块内部 + 低频分布。 */
    private double plateauMaskAt(int blockX, int blockZ, double edge, double landness) {
        double plateauNoise = smoothstep(clamp(
                terrainNoise.fbm(blockX, blockZ, PLATEAU_SCALE, 2, 2.0, 0.5) * 0.5 + 0.5, 0.0, 1.0));
        return clamp(landness * (1.0 - edge) * plateauNoise, 0.0, 1.0);
    }

    /** 温度：纬向带 + 低频扰动 + 海拔递减。原版语义区间 [-1, 1]。 */
    private double temperature(int blockX, int blockZ, double edge) {
        double lat = Math.min(1.0, Math.abs(blockZ) / latitudeScale);
        double jitter = terrainNoise.fbm(blockX, blockZ, CLIMATE_JITTER_SCALE, 2, 2.0, 0.5) * 0.30;
        // 用解析基面高度：temperature 是群系查询的热点，不能触发水文建区
        double elev = Math.max(0.0, baseTerrainHeight(blockX, blockZ, edge) - 100.0) * 0.0015;
        return clamp(0.55 - 1.05 * lat + jitter - elev, -1.0, 1.0);
    }

    /** 湿度：噪声主导，沿海更湿、内陆更干。原版语义区间 [-1, 1]。 */
    private double moisture(int blockX, int blockZ) {
        double n = terrainNoise.fbm(blockX, blockZ, MOISTURE_SCALE, 3, 2.0, 0.5);
        return clamp(n * 0.7 + 0.12 + (0.5 - landnessAt(blockX, blockZ)) * 0.35, -0.9, 0.9);
    }

    /**
     * 基面地形高度（<b>未切河</b>）——完全由连续场构成，不读板块身份。
     *
     * <p>板块身份是离散量：在 Voronoi 顶点附近「第三近板块」会换人，
     * 若高度依赖它，换人处会直接产生上百方块的垂直断崖（已用量化脚本验证：
     * 相邻 16 方块跳变最大 138 格）。
     *
     * <p>因此高度只由两件事决定：
     * <ul>
     *   <li>{@code cont}：低频连续大陆度噪声 → 平滑的海陆基底</li>
     *   <li>{@code edge}：F2−F1 连续边界接近度 → 边界成山、洋侧成沟</li>
     * </ul>
     */
    private double baseTerrainHeight(int blockX, int blockZ, double edge) {
        // 1) 大陆度 → 海陆基底（连续插值，无硬边）
        double landness = landnessAt(blockX, blockZ);
        double base = OCEAN_BASE + (CONTINENT_BASE - OCEAN_BASE) * landness;

        // 2) 山系：ridged 噪声（1−|n|）→ 连续山脊；板块边界带加强成褶皱山脉
        double ridge = ridgeAt(blockX, blockZ);
        // 3) 侵蚀包络：老年地貌削尖山脊、填高谷底，形成「锐利新山 vs 圆润老山」的地貌分异
        double erodeNoise = terrainNoise.fbm(blockX, blockZ, EROSION_ENVELOPE_SCALE, 2, 2.0, 0.5);
        double erode = smoothstep(clamp(erodeNoise * 0.5 + 0.5, 0.0, 1.0));
        double sharpness = 1.0 - erode * erosionRound;
        // 山体区域掩码：低频（1600 格）决定"这一带是不是山区"。
        // 参考 TerraForged（山脉类型 weight=2.0）与 Terralith（大尺度 offset 样条）：
        // 高山应该成片出现，而不是每条脊线一样高。增益 0.8~1.7，均值约 1.25。
        double massif = smoothstep(clamp(
                terrainNoise.fbm(blockX, blockZ, MASSIF_SCALE, 2, 2.0, 0.5) * 1.1 + 0.15, 0.0, 1.0));
        double massifGain = 0.8 + 0.9 * massif;
        double uplift = landness * (0.45 + 0.55 * edge) * massifGain
                * ridge * mountainAmp * sharpness * verticalScale;

        // 3) 海沟：只在陆洋边界附近下切，山脊处减弱
        double trench = (1.0 - landness) * edge * (1.0 - 0.5 * ridge) * trenchDepth;

        // 4) 次级起伏：陆侧随陆地程度增强，洋侧用较小振幅保留洋底起伏
        double localAmp = landness * interiorAmp - (1.0 - landness) * abyssAmp;
        double interior = terrainNoise.fbm(blockX, blockZ, INTERIOR_SCALE, 3, 2.0, 0.5)
                * localAmp * (0.4 + 0.6 * edge);

        double h = base + uplift - trench + interior;
        // 侵蚀填谷：老年地貌把低洼谷底垫高，只在陆地、低海拔处生效
        double infill = erode * landness * (1.0 - ridge) * EROSION_FILL;
        h += infill;

        // ===== L1 大地貌 =====
        // 边界类型噪声只算一次，裂谷/火山弧两个掩码共用（热路径优化）
        double boundaryType = boundaryTypeAt(blockX, blockZ);
        double riftMask = riftMaskAt(boundaryType, edge);
        double arcMask = arcMaskAt(boundaryType, edge);
        double plateauMask = plateauMaskAt(blockX, blockZ, edge, landness);

        // 高原：向台面高度收敛并压平（保留少量起伏，避免镜子一样的平面）
        if (plateauMask > 0.01) {
            double target = PLATEAU_LEVEL + interior * 0.35;
            h += plateauMask * (target - h) * 0.85;
        }
        // 裂谷：沿张裂边界下切，山脊处切得浅一点
        h -= landness * riftMask * RIFT_DEPTH * (0.55 + 0.45 * ridge);
        // 火山弧：沿汇聚边界抬升成串珠状山链
        h += landness * arcMask * ARC_HEIGHT * (0.45 + 0.55 * ridge);

        // 陆地软下限：权重随陆地程度渐入，海陆交界不留硬边
        h = h + landness * (softMax(h, minContinentHeight) - h);
        // 软上限：depth 的 y 梯度只到 Y=2031，再高整列都会被推成实心。
        // 用"软膝"压缩而不是硬截断，避免出现一片 1860 的平顶山。
        if (h > SOFT_CAP_KNEE) {
            h = SOFT_CAP_KNEE + (MAX_TERRAIN_HEIGHT - SOFT_CAP_KNEE)
                    * (1.0 - Math.exp(-(h - SOFT_CAP_KNEE) / (MAX_TERRAIN_HEIGHT - SOFT_CAP_KNEE)));
        }
        return h;
    }

    /** 地表高度 = 解析基面高度（水系/侵蚀已下线，回到逐点解析）。 */
    private double terrainHeight(int blockX, int blockZ, double edge) {
        return baseTerrainHeight(blockX, blockZ, edge);
    }

    /** 距离反比的 6 次方权重——近处主导、边界处平滑过渡。 */
    private static double invPow(double d) {
        double v = 1.0 / (d + EPS);
        double v2 = v * v;
        double v3 = v2 * v;
        return v3 * v3;
    }

    /** 平滑阶梯曲线 3t²−2t³。 */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 平滑取大——避免硬 clamp 造出平顶台地。
     *
     * <p>{@code 0.5(a+b+√((a−b)²+k))} 在 a≈b 处光滑过渡，a>>b 时趋近 a。
     */
    private static double softMax(double a, double b) {
        double d = a - b;
        return 0.5 * (a + b + Math.sqrt(d * d + 16.0));
    }

    // ===== 板块属性计算 =====

    private Cell plateCell(int px, int pz) {
        long key = plateKey(px, pz);
        Cell hit = cache.get(key);
        if (hit != null) return hit;

        Cell cell = pool.borrow();
        try {
            populatePlate(cell, px, pz);
            cache.put(key, cell);
        } catch (RuntimeException e) {
            pool.giveBack(cell);
            throw e;
        }
        // 入缓存之后再发布：订阅者若回调 queryCell/plateCell，拿到的就是已就绪的这条，
        // 不会重入 populatePlate（否则会无限递归）。无订阅者时不构造载荷对象。
        if (RecastEvents.CELL_PIPELINE_APPLIED.subscriberCount() > 0) {
            RecastEvents.CELL_PIPELINE_APPLIED.post(new RecastEvents.CellTick(cell.asReadonly()));
        }
        return cell;
    }

    private void populatePlate(Cell cell, int px, int pz) {
        double bx = worley.plateCenterX(px, pz, PLATE_CELL_SIZE);
        double bz = worley.plateCenterZ(px, pz, PLATE_CELL_SIZE);

        WorleyDistance.PlateNeighbourhood nb = worley.sampleNeighbourhood(bx, bz, PLATE_CELL_SIZE);
        boolean isContinent = isContinentPlate(px, pz);
        boolean neighbourContinent = isContinentPlate(nb.px2(), nb.pz2());

        cell.continentId = plateHash(px, pz);
        cell.plateBoundaryDistance = Math.min(1.0, (nb.d2() - nb.d1()) / EDGE_SPAN);

        populateL1(cell, isContinent);
        populateL2(cell, isContinent, neighbourContinent, bz);
        populateL5(cell, isContinent, bx, bz);
        populateL6(cell);
    }

    /** 板块类型判定。出生点附近强制为大陆，避免出生区块落在海盆里。 */
    private boolean isContinentPlate(int px, int pz) {
        if (Math.abs(px) <= SPAWN_SAFE_PLATES && Math.abs(pz) <= SPAWN_SAFE_PLATES) return true;
        return worley.isContinent(plateHash(px, pz));
    }

    /** 板块坐标 → 稳定哈希 ID（与 WorleyDistance 保持同一算法）。 */
    private static int plateHash(int px, int pz) {
        return (px * 374761393 + pz * 668265263) & 0x7FFFFFFF;
    }

    /** L1 板块固有大地貌——基准高度与陡峭度。边界造山在采样阶段叠加。 */
    private void populateL1(Cell cell, boolean isContinent) {
        if (isContinent) {
            cell.landformType = LANDFORM_PLATEAU;
            cell.terrainHeight = CONTINENT_BASE;
            cell.steepness = 0.15;
        } else {
            cell.landformType = LANDFORM_DEEP_OCEAN;
            cell.terrainHeight = OCEAN_BASE;
            cell.steepness = 0.05;
        }
    }

    /**
     * L2 气候五维——由板块类型 + 纬度 + 板块高度推演。
     *
     * <p>纬度用板块中心 Z 坐标判定（真实纬向带状），海拔做温度衰减。
     */
    private void populateL2(Cell cell, boolean isContinent, boolean neighbourContinent, double bz) {
        double lat = Math.min(1.0, Math.abs(bz) / 4000.0);
        cell.temperature = clamp(1.0 - lat * 1.6 - cell.terrainHeight * 0.003, -1.0, 1.0);

        // 大陆内陆干燥、海洋湿润；边界带（造山/海沟）湿度偏高
        cell.moisture = isContinent ? 0.35 + (1.0 - cell.plateBoundaryDistance) * 0.4 : 0.85;

        // 板块固有大陆性，边界过渡由采样阶段的距离加权混合自动处理
        cell.continentalness = isContinent ? 0.6 : -0.6;
        if (isContinent != neighbourContinent) {
            // 陆洋交界：往中间值靠一点，避免混合后停留在极端
            cell.continentalness = isContinent ? 0.3 : -0.3;
        }

        // 陡峭=年轻地貌（低侵蚀），平缓=老年地貌（高侵蚀）
        cell.erosion = (1.0 - cell.steepness) * 2.0 - 1.0;

        // 现阶段用板块位置的确定性随机代替 noise（后续接 OpenSimplex）
        long h = worldSeed ^ ((long) cell.continentId * 0x4F9939F508L);
        h ^= h >>> 13; h ^= h << 17; h ^= h >>> 5;
        cell.weirdness = ((h & 0xFFFFFFFFL) / 2147483648.0) - 1.0;
    }

    /**
     * L5 岩性——大陆基底花岗岩/砂岩，海洋基底玄武岩/石灰岩。
     *
     * <p>按板块中心噪声细分，让相邻板块岩性不同，挖矿/挖山时地层有区域差异。
     * 岩性编码与 {@link yifei.terrain.recast.worldgen.densityfunction.StrataDensityFunction.Lithology} 一致：
     * 1=GRANITE 2=BASALT 3=LIMESTONE 4=SANDSTONE。
     */
    private void populateL5(Cell cell, boolean isContinent, double bx, double bz) {
        double n = terrainNoise.fbm(bx, bz, LITHO_SCALE, 3, 2.0, 0.5);
        if (isContinent) {
            // 大陆：噪声高 → 花岗岩（坚硬结晶岩），低 → 砂岩（沉积岩）
            cell.lithology = n > 0.2 ? 1 : 4;
        } else {
            // 海洋：噪声高 → 玄武岩（洋壳火成岩），低 → 石灰岩（海相沉积）
            cell.lithology = n > 0.1 ? 2 : 3;
        }
    }

    /**
     * L6 群系候选——把 TRC 气候域映射到原版群系名义分类（调试/附属查询用）。
     *
     * <p>注意：真正的群系由 noise_router 中的 temperature/vegetation/continents/erosion
     * 五路 cell_field 参数驱动原版 MultiNoiseBiomeSource 完成，这里只是把同一个
     * 气候域折叠成单一分类编码，供 {@link yifei.terrain.recast.api.CellDataView#getBiomeCandidate()}
     * 等调试/附属 API 快速核对「本板块大概率是什么群系」。
     */
    private void populateL6(Cell cell) {
        double h = cell.terrainHeight;
        double t = cell.temperature;
        double m = cell.moisture;

        // 先按海拔分海陆 → 再按温度/湿度抬群系
        if (h < -12) {
            cell.biomeCandidate = Cell.Biome.OCEAN;                    // 深海/海洋
        } else if (h < 2) {
            cell.biomeCandidate = Cell.Biome.COAST;                    // 海岸带
        } else if (h > 110) {
            cell.biomeCandidate = Cell.Biome.MOUNTAIN;                 // 高海拔山地/雪山
        } else if (t < -0.25) {
            cell.biomeCandidate = Cell.Biome.SNOWY;                    // 寒冷 → 雪原
        } else if (t > 0.35 && m < 0.3) {
            cell.biomeCandidate = Cell.Biome.DESERT;                   // 干热 → 荒漠
        } else if (m > 0.55) {
            cell.biomeCandidate = Cell.Biome.FOREST;                   // 湿润 → 森林
        } else if (t > 0.25) {
            cell.biomeCandidate = Cell.Biome.GRASSLAND;                // 温热稍干 → 草原
        } else {
            cell.biomeCandidate = Cell.Biome.PLAINS;                   // 温湿适中 → 平原
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
