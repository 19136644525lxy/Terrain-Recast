package yifei.terrain.recast.config;

/**
 * 配置选项注册表。一个选项 = key + 分类 + 默认值 + 范围 + 双语名。
 *
 * <p>设计：把「有哪些配置项」集中成枚举，单一事实来源，供
 * {@code ConfigHandler} 做加载/保存/校验/夹取，供配置界面与预设系统枚举条目。
 * 每个 key 对应一个可被 {@code ConfigConstant} / {@code ConfigNoise}
 * 消费的 DensityFunction 参数。
 */
public enum ConfigOption {

    // ===== 地形 ====
    MOUNTAIN_AMP("terrain.mountain_amp", Category.TERRAIN, 1100.0, 0, 3000,
            "山脉振幅", "Mountain Amplitude"),
    VERTICAL_SCALE("terrain.vertical_scale", Category.TERRAIN, 1.0, 0.3, 3.0,
            "垂直缩放", "Vertical Scale"),
    INTERIOR_AMP("terrain.interior_amp", Category.TERRAIN, 42.0, 0, 600,
            "内陆起伏", "Interior Relief"),
    ABYSS_AMP("terrain.abyss_amp", Category.TERRAIN, 16.0, 0, 120,
            "深海起伏", "Abyssal Relief"),
    TRENCH_DEPTH("terrain.trench_depth", Category.TERRAIN, 60.0, 0, 300,
            "海沟深度", "Trench Depth"),
    COAST_SPAN("terrain.coast_span", Category.TERRAIN, 30.0, 5, 150,
            "海岸跨度", "Coastal Span"),
    EROSION_ROUND("terrain.erosion_round", Category.TERRAIN, 0.55, 0, 1,
            "侵蚀圆润度", "Erosion Rounding"),
    MIN_CONTINENT_HEIGHT("terrain.min_continent_height", Category.TERRAIN, 8.0, 0, 80,
            "大陆最低高度", "Min Continent Height"),

    // ===== 气候 ====
    LATITUDE_SCALE("climate.latitude_scale", Category.CLIMATE, 6000.0, 1000, 30000,
            "纬度带尺度", "Latitude Scale"),

    // ===== 通用 ====
    SPAWN_LAND_RADIUS("general.spawn_land_radius", Category.GENERAL, 1400.0, 0, 20000,
            "出生地大陆半径", "Spawn Land Radius");

    private final String key;
    private final Category category;
    private final double defaultValue;
    private final double min;
    private final double max;
    private final String zhName;
    private final String enName;

    ConfigOption(String key, Category category, double defaultValue,
                 double min, double max, String zhName, String enName) {
        this.key = key;
        this.category = category;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.zhName = zhName;
        this.enName = enName;
    }

    public String key() { return key; }
    public Category category() { return category; }
    public double defaultValue() { return defaultValue; }
    public double min() { return min; }
    public double max() { return max; }
    public String zhName() { return zhName; }
    public String enName() { return enName; }

    /** 按 key 查选项，未知 key 返回 null。 */
    public static ConfigOption byKey(String key) {
        for (ConfigOption o : values()) {
            if (o.key.equals(key)) return o;
        }
        return null;
    }

    /** 配置分类。用于配置界面分区。 */
    public enum Category {
        TERRAIN, CLIMATE, GENERAL;
    }
}