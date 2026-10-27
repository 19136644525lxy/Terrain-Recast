package yifei.terrain.recast.api.event;

import yifei.terrain.recast.api.CellDataView;

/**
 * Terrain Recast 事件中心——聚合各场景 {@link RecastEventBus}，附属按需订阅。
 *
 * <p>载荷均为不可变 record，规避共享可变状态，保证跨线程发布安全。
 * 事件对象在使用后才被发来，寄主回调安全无泄漏。
 */
public final class RecastEvents {

    /** 区块生成完成后触发，载荷为区块 XY。可用于附属做区块级后处理/统计。 */
    public static final RecastEventBus<ChunkGenerated> CHUNK_GENERATED = new RecastEventBus<>();

    /** Cell 数据被管线应用后触发，载荷为当前板块只读视图。附属可据此观测驱动自己的逻辑。 */
    public static final RecastEventBus<CellTick> CELL_PIPELINE_APPLIED = new RecastEventBus<>();

    private RecastEvents() {}

    /** 区块生成完成载荷（16×16 区块左上角坐标）。 */
    public record ChunkGenerated(int chunkX, int chunkZ) {}

    /** 单个 Cell 板块被推演完成后的只读视图载荷。 */
    public record CellTick(CellDataView view) {}

    /** 清空全部事件总线订阅（世界卸载/模组重载时由 RegistryBootstrap 调）。 */
    public static void clearAll() {
        CHUNK_GENERATED.clear();
        CELL_PIPELINE_APPLIED.clear();
    }
}