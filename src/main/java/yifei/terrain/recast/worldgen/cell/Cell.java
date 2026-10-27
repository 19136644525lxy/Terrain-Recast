package yifei.terrain.recast.worldgen.cell;

import yifei.terrain.recast.api.CellDataView;

/**
 * Cell 单元格——地形引擎的最小数据载体，代表 1×1 区块尺度的区域地理单元。
 *
 * <p>所有字段对应 L0–L6 因果推演管线的一层输出：
 * <pre>
 * L0 板块：continentId / plateBoundaryDistance
 * L1 大地貌：landformType / terrainHeight / steepness
 * L2 气候：temperature / moisture / continentalness / erosion / weirdness
 * L3 水系：riverFlow
 * L4 侵蚀：erosionAmount
 * L5 地层：lithology
 * L6 群系：biomeCandidate
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯 POJO，方便 {@link yifei.terrain.recast.concurrent.ObjectPool} 池化复用。
 *   <li>一期骨架只声明字段，不填值。真实推演在 L0–L6 管线完成后注入。
 *   <li>线程安全：Cell 本身不是线程安全容器——它由 {@link CellLookup} 在区块级采样时
 *       构造并填入 {@link yifei.terrain.recast.concurrent.LifecycleBoundCache}，区块数据
 *       消费路径是单线程（主线程或 chunk 构建线程），不跨线程共享同一 Cell 实例。
 * </ul>
 */
public final class Cell implements CellDataView {

    // ===== L0 板块 =====
    /** 大陆/海洋 ID（-1 表示深海，0+ 表示板块编号） */
    public int continentId = -1;
    /** 到最近板块边界的距离（Worley F2-F1 值，[0,1]，越大越远离边界） */
    public double plateBoundaryDistance;

    // ===== L1 大地貌 =====
    /** 地貌类型枚举 ordinal（褶皱山/断块山/火山/高原/盆地/平原/海沟/岛弧/裂谷……） */
    public int landformType;
    /** 基准地形高度（Y 轴，单位：方块，以 -64 海平面为 0 的相对高度） */
    public double terrainHeight;
    /** 地表陡峭度（[0,1]，0=平地，1=悬崖） */
    public double steepness;

    // ===== L2 气候（五维） =====
    /** 温度（≈ -0.5 极地 ~ +0.5 赤道） */
    public double temperature;
    /** 湿度（≈ 0 荒漠 ~ 1 雨林） */
    public double moisture;
    /** 大陆性（≈ -1 海洋 ~ +1 内陆） */
    public double continentalness;
    /** 侵蚀（≈ 0 年轻地貌 ~ 1 老年地貌） */
    public double erosion;
    /** 怪异度（≈ 0 规整 ~ 1 狂野） */
    public double weirdness;

    // ===== L3 水系 =====
    /** 河流流量累积值（D8 算法输出，已 log 压缩） */
    public double riverFlow;

    // ===== L4 侵蚀 =====
    /** 额外侵蚀量（水力+热力+岩性差异叠加，用于二次塑形） */
    public double erosionAmount;

    // ===== L5 地层 =====
    /** 岩性枚举 ordinal（花岗岩/玄武岩/石灰岩/砂岩/页岩/片麻岩/火成岩……） */
    public int lithology;

    // ===== L6 群系 =====
    /** Whittaker 图选出的候选群系分类编码（见 {@link Cell.Biome}，0=NONE）。 */
    public int biomeCandidate;

    /** L6 群系候选分类（与 Biomes 原版名义对应，用于调试/附属查询）。 */
    public static final class Biome {
        public static final int NONE = 0;
        public static final int OCEAN = 1;
        public static final int COAST = 2;
        public static final int DESERT = 3;
        public static final int GRASSLAND = 4;
        public static final int FOREST = 5;
        public static final int SNOWY = 6;
        public static final int MOUNTAIN = 7;
        public static final int PLAINS = 8;
    }

    /** XZ 区块坐标。Cell 本身不存坐标——由 CellLookup 的缓存 key 负责定位。 */

    // ===== 生命周期 =====

    /**
     * 全部字段归零。由 {@link yifei.terrain.recast.concurrent.ObjectPool} 的 resetter 调用，
     * 避免池化复用时脏数据泄漏到下一个消费者。
     */
    public void reset() {
        continentId = -1;
        plateBoundaryDistance = 0;
        landformType = 0;
        terrainHeight = 0;
        steepness = 0;
        temperature = 0;
        moisture = 0;
        continentalness = 0;
        erosion = 0;
        weirdness = 0;
        riverFlow = 0;
        erosionAmount = 0;
        lithology = 0;
        biomeCandidate = 0;
    }

    // ===== CellDataView 只读接口实现 =====

    /** 返回自身作为只读视图。设计：Cell 同时是内部可变实现和对外只读视图，
     * 附属模组拿到的是 CellDataView 接口，无法访问字段。 */
    public CellDataView asReadonly() { return this; }

    @Override public int getContinentId() { return continentId; }
    @Override public double getPlateBoundaryDistance() { return plateBoundaryDistance; }
    @Override public double getTerrainHeight() { return terrainHeight; }
    @Override public double getSteepness() { return steepness; }
    @Override public double getTemperature() { return temperature; }
    @Override public double getMoisture() { return moisture; }
    @Override public double getContinentalness() { return continentalness; }
    @Override public double getErosion() { return erosion; }
    @Override public double getWeirdness() { return weirdness; }
    @Override public double getRiverFlow() { return riverFlow; }
    @Override public double getErosionAmount() { return erosionAmount; }
    @Override public int getLithology() { return lithology; }
    @Override public int getBiomeCandidate() { return biomeCandidate; }

    // ===== Field 访问（供 CellFieldSampler 反射/枚举索引） =====

    /** Cell 全部可采样字段的索引枚举。 */
    public enum Field {
        PLATE_BOUNDARY_DISTANCE { @Override public double get(Cell c) { return c.plateBoundaryDistance; } },
        TERRAIN_HEIGHT         { @Override public double get(Cell c) { return c.terrainHeight; } },
        STEEPNESS              { @Override public double get(Cell c) { return c.steepness; } },
        TEMPERATURE            { @Override public double get(Cell c) { return c.temperature; } },
        MOISTURE               { @Override public double get(Cell c) { return c.moisture; } },
        CONTINENTALNESS        { @Override public double get(Cell c) { return c.continentalness; } },
        EROSION                { @Override public double get(Cell c) { return c.erosion; } },
        WEIRDNESS              { @Override public double get(Cell c) { return c.weirdness; } },
        RIVER_FLOW             { @Override public double get(Cell c) { return c.riverFlow; } },
        RIVER_BAND             { @Override public double get(Cell c) { return c.riverFlow; } },
        EROSION_AMOUNT         { @Override public double get(Cell c) { return c.erosionAmount; } },
        /** 岩性。本质离散量：取最近板块整数值，不做软混合。 */
        LITHOLOGY              { @Override public double get(Cell c) { return c.lithology; } },
        /** L6 群系分类（Whittaker 查表结果，取值见 {@code WhittakerBiome}）。 */
        BIOME                  { @Override public double get(Cell c) { return c.biomeCandidate; } },
        /**
         * L1 大地貌编码（裂谷 -0.6 / 平原 0 / 高原 0.35 / 火山弧 0.7）。
         * 供覆写的 {@code overworld/ridges} 使用：把地貌编进 weirdness，
         * 群系表就能按地貌分带（Tectonic 的 biome_parameter 手法）。
         */
        LANDFORM               { @Override public double get(Cell c) { return 0; } },
        /**
         * 群系族选择器：>0 用自定义群系，<0 用原版群系（低频噪声，约五五开）。
         * 供覆写的 {@code overworld/erosion} 使用，让原版群系和自定义群系在世界里均匀混合。
         */
        BIOME_FAMILY           { @Override public double get(Cell c) { return 0; } };

        public abstract double get(Cell cell);
    }
}
