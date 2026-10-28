package yifei.terrain.recast.api;

/**
 * Cell 数据只读视图。附属模组通过此接口查询地理数据，不接触内部可变 Cell 实例——
 * 避免附属误改字段导致生成链路数据不一致。
 *
 * <p>接口隔离原则：附属模组通常只用到 1–2 个字段，不暴露 Cell 本体。
 */
public interface CellDataView {

    int getContinentId();

    double getPlateBoundaryDistance();

    double getTerrainHeight();

    double getSteepness();

    double getTemperature();

    double getMoisture();

    double getContinentalness();

    double getErosion();

    double getWeirdness();

    int getLithology();

    /** L6 群系候选分类（Cell.Biome）。未填充前为 0。 */
    int getBiomeCandidate();
}
