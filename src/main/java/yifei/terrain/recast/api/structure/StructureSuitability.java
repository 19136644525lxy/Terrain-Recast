package yifei.terrain.recast.api.structure;

/**
 * 结构适宜性评估接口——判定某个结构能否在指定位置地理条件下放置。
 *
 * <p>为 7.2 结构地理适宜性放置预留的开放点（开闭原则）：附属/核心可通过实现
 * 此接口，把「村庄只在低 erosion 平原」「神殿只在沙漠」等地学约束下放到 cell 数据。
 *
 * <p>函数式接口，可用 lambda。单一方法（接口隔离），不强迫实现无关能力。
 */
@FunctionalInterface
public interface StructureSuitability {

    /**
     * @param cell    目标位置最近板块的只读地理视图（岩性/陡峭度/海拔等）
     * @param config  结构名（命名空间 id）与可扩展的形态参数容器，占位，未来扩展不加方法
     * @return true=适宜放置
     */
    boolean isSuitable(CellView cell, PlacementContext config);

    /** 结构放置形态参数的轻量容器（不可变快照）。 */
    record PlacementContext(String structureId, int blockX, int blockZ, int surfaceY) {}

    /**
     * 最小化的 Cell 地理只读切片——只暴露判定所需的几个字段，
     * 避免把整个 CellDataView 依赖塞进结构层（依赖倒置 + 接口隔离）。
     */
    interface CellView {
        int lithology();
        double steepness();
        double terrainHeight();
        double moisture();
        double temperature();
    }
}