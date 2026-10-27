package yifei.terrain.recast.worldgen.cell;

/**
 * Cell 查找入口接口。对外提供两种读取方式：
 * <ul>
 *   <li>{@link #sampleField}：连续场采样（软 Voronoi 距离加权混合），供 DensityFunction 热路径使用</li>
 *   <li>{@link #getCell} / {@link #nearestCell}：离散 Cell 读取，供调试、附属模组查询</li>
 * </ul>
 *
 * <p>实现类内部持有线程安全缓存 + Cell 对象池，close() 时显式释放。
 */
public interface CellLookup extends AutoCloseable {

    /**
     * 获取指定区块坐标的 Cell。若该区块尚未计算则返回 null。
     */
    Cell getCell(int chunkX, int chunkZ);

    /** 获取或计算指定区块坐标的 Cell。 */
    default Cell computeIfAbsent(int chunkX, int chunkZ) {
        return getCell(chunkX, chunkZ);
    }

    /**
     * 采样 (blockX, blockZ) 处某字段的<b>连续</b>值。
     *
     * <p>实现必须保证跨板块边界时结果连续（相邻板块属性按距离加权混合），
     * 否则 DensityFunction 输出会出现区块级阶跃，地形呈台阶状。
     */
    default double sampleField(Cell.Field field, int blockX, int blockZ) {
        return 0.0;
    }

    /** 最近板块的 Cell（离散值，用于岩性等本质离散的属性）。 */
    default Cell nearestCell(int blockX, int blockZ) {
        return null;
    }

    /** 底层缓存是否为空（全是新区块）。调试/预热场景下可用。 */
    boolean isEmpty();
}
