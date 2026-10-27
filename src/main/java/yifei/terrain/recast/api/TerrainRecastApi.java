package yifei.terrain.recast.api;

import yifei.terrain.recast.api.injection.GroupedFeatureInjection;
import yifei.terrain.recast.api.modifier.ModifierPipeline;
import yifei.terrain.recast.api.ore.OreRegistry;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.CellLookup;

/**
 * Terrain Recast 附属模组统一 API 入口。静态门面，所有附属都从此拿服务实例，
 * 不直接接触内部实现——依赖倒置原则（DIP）。
 *
 * <p>静态字段由 {@code core.RegistryBootstrap} 在模组初始化时注入，
 * 生命周期绑定 MinecraftServer（世界卸载时 CellLookup.close() 释放资源）。
 */
public final class TerrainRecastApi {

    private static volatile CellLookup cellLookup;
    // 服务注册表（由 RegistryBootstrap 注入，世界重建时 clear 复用实例）：
    private static volatile ModifierPipeline pipeline = new ModifierPipeline();
    private static volatile OreRegistry oreRegistry = new OreRegistry();
    private static volatile GroupedFeatureInjection featureInjection = new GroupedFeatureInjection();

    private TerrainRecastApi() {}

    // ===== 内部注册（仅 TRC core 可调用，附属只读） =====

    /** {@code core.RegistryBootstrap} 注入 CellLookup。 */
    public static void setCellLookup(CellLookup lookup) {
        cellLookup = lookup;
    }

    // ===== 公开 API =====

    /** 获取 CellLookup 实例。可能返回 null（模组未启用或初始化失败）。 */
    public static CellLookup getCellLookup() {
        return cellLookup;
    }

    /** DataModifier 责任链管道——清单式注册本来由字段变换器，再由 feature 热路径消费。 */
    public static ModifierPipeline getPipeline() {
        return pipeline;
    }

    /** 矿床注册器——按岩性绑定矿种，附属可追加自己的矿床。 */
    public static OreRegistry getOreRegistry() {
        return oreRegistry;
    }

    /**
     * 分组地物注入表——core 与附属都在此登记 placed feature，
     * 再由 {@link GroupedFeatureInjection#apply()} 统一下发到 Fabric 群系注入系统。
     */
    public static GroupedFeatureInjection getFeatureInjection() {
        return featureInjection;
    }

    /** 便捷查询 (x,z) 的 Cell 视图。未找到返回 null。 */
    public static CellDataView queryCell(int chunkX, int chunkZ) {
        CellLookup lookup = cellLookup;
        if (lookup == null) return null;
        Cell cell = lookup.getCell(chunkX, chunkZ);
        if (cell == null) return null;
        return cell.asReadonly();
    }
}
