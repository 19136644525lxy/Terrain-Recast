package yifei.terrain.recast.config;

import java.util.Map;

/**
 * 地形预设。预设 = 一组配置项覆盖值，一键应用后整体改变地形观感。
 *
 * <p>设计（最小可用版）：预设以代码内静态枚举存储，经 {@code ConfigHandler.applyPreset}
 * 写入 ConfigState；后续可演进为数据包 JSON 资源（技巧变换不影响 API）。
 * lang key 形如 {@code preset.terrain_recast.<id>}，用于配置界面展示。
 */
public enum Preset {

    DEFAULT("default", Map.of()),
    MAJESTIC("majestic", Map.of(
            "terrain.mountain_amp", 1900.0,
            "terrain.interior_amp", 100.0,
            "terrain.trench_depth", 80.0,
            "terrain.coast_span", 40.0,
            "terrain.erosion_round", 0.35,
            "terrain.min_continent_height", 12.0)),
    ARCHIPELAGO("archipelago", Map.of(
            "terrain.abyss_amp", 28.0,
            "terrain.interior_amp", 45.0,
            "terrain.coast_span", 120.0,
            "terrain.min_continent_height", 4.0,
            "general.spawn_land_radius", 2000.0)),
    // 高原：大陆整体抬升但起伏平缓，低山脉振幅 + 高侵蚀圆润
    PLATEAU("plateau", Map.of(
            "terrain.mountain_amp", 70.0,
            "terrain.interior_amp", 25.0,
            "terrain.min_continent_height", 45.0,
            "terrain.erosion_round", 0.75,
            "terrain.coast_span", 25.0)),
    // 平缓丘陵：次级起伏占主导、山脉少，圆润度高
    HILLS("hills", Map.of(
            "terrain.mountain_amp", 45.0,
            "terrain.interior_amp", 70.0,
            "terrain.erosion_round", 0.65,
            "terrain.min_continent_height", 8.0,
            "terrain.coast_span", 40.0));

    private final String id;
    private final Map<String, Double> overrides;

    Preset(String id, Map<String, Double> overrides) {
        this.id = id;
        this.overrides = overrides;
    }

    public String id() { return id; }

    /** 语言 key，如 preset.terrain_recast.majestic。 */
    public String langKey() { return "preset.terrain_recast." + id; }

    /** 把此预设覆盖值写入状态（未覆盖的项保持现状）。 */
    public void applyTo(ConfigState state) {
        for (Map.Entry<String, Double> e : overrides.entrySet()) {
            state.putValue(e.getKey(), e.getValue());
        }
    }

    public static Preset byId(String id) {
        for (Preset p : values()) {
            if (p.id.equals(id)) return p;
        }
        return DEFAULT;
    }
}