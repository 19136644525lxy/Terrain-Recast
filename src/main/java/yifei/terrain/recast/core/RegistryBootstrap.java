package yifei.terrain.recast.core;

import yifei.terrain.recast.TerrainRecast;
import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.api.event.RecastEvents;
import yifei.terrain.recast.api.ore.OreDeposit;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.worldgen.cell.NoopCellLookup;
import yifei.terrain.recast.worldgen.densityfunction.CellFieldSampler;
import yifei.terrain.recast.worldgen.densityfunction.ConfigConstant;
import yifei.terrain.recast.worldgen.densityfunction.ConfigNoise;
import yifei.terrain.recast.worldgen.densityfunction.EndShatteredContinentFunction;
import yifei.terrain.recast.worldgen.densityfunction.LandformHeightFunction;
import yifei.terrain.recast.worldgen.densityfunction.NetherVolcanoDensityFunction;
import yifei.terrain.recast.worldgen.densityfunction.PlateBoundaryDistanceFunction;
import yifei.terrain.recast.worldgen.densityfunction.StrataDensityFunction;
import yifei.terrain.recast.worldgen.materialrule.LithologyRule;
import yifei.terrain.recast.worldgen.feature.TerrainGroundCoverFeature;
import yifei.terrain.recast.worldgen.feature.TerrainOreDepositFeature;
import yifei.terrain.recast.worldgen.feature.TerrainVegetationFeature;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 模组引导与注册中心。集中注册自定义 DensityFunction 类型、初始化 ConfigHandler、
 * 注入 TerrainRecastApi 的 CellLookup。
 *
 * <p>全部注册在 {@link TerrainRecast#onInitialize()} 调用——Fabric 初始化时序下，
 * BuiltInRegistries.DENSITY_FUNCTION_TYPE 此时还未冻结，可以安全注册。
 *
 * <p>注册策略（参考 Tectonic Fabric 端 {@code TectonicFabric.onInitialize()}）：
 * {@code Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("type_name"), codec)}
 */
public final class RegistryBootstrap {

    private RegistryBootstrap() {}

    /** Fabric ModInitializer 阶段调。 */
    public static void init() {
        TerrainRecast.LOGGER.info("Bootstrapping Terrain Recast registry...");

        // ==== ConfigHandler ====
        ConfigHandler.setState(ConfigHandler.createDefaultState());

        // ==== DensityFunction 类型 ====
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("config_constant"), ConfigConstant.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("config_noise"), ConfigNoise.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("cell_field"), CellFieldSampler.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("plate_boundary_distance"), PlateBoundaryDistanceFunction.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("landform_height"), LandformHeightFunction.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("strata"), StrataDensityFunction.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("nether_volcano"), NetherVolcanoDensityFunction.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                TerrainRecast.id("end_shattered_continent"), EndShatteredContinentFunction.CODEC);

        // ==== MaterialRule 类型 ====
        // L5 岩性落块：LithologyRule 供 overworld 顶层 material_rule 序列末尾引用。
        Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE,
                TerrainRecast.id("lithology"), LithologyRule.CODEC);

        // ==== Feature 类型（地物）====
        // 程序化植被：TreeFeature（及 TrunkPlacer/FoliagePlacer）的 codec 把树高限制在
        // 80 以内，配不上本模组超高山地；故注册一个自绘 Feature，由其读取 Cell 管线
        // 决定树种/高度/密度（详见 TerrainVegetationFeature）。
        Registry.register(BuiltInRegistries.FEATURE_TYPE,
                TerrainRecast.id("vegetation"), TerrainVegetationFeature.CODEC);
        // 地表覆盖物：草/花/蕨/仙人掌等灌木层，与树互补，见 TerrainGroundCoverFeature。
        Registry.register(BuiltInRegistries.FEATURE_TYPE,
                TerrainRecast.id("ground_cover"), TerrainGroundCoverFeature.CODEC);
        // 地质矿床：按 Cell 地层省分带分布矿脉，见 TerrainOreDepositFeature。
        Registry.register(BuiltInRegistries.FEATURE_TYPE,
                TerrainRecast.id("ore_deposit"), TerrainOreDepositFeature.CODEC);

        // ==== CellLookup 初始 stub ====
        // 二期 L0 板块推演完成后，替换为真正实现；当前用 NoopCellLookup 让 API 不为 null。
        TerrainRecastApi.setCellLookup(new NoopCellLookup());

        // ==== 八章 API 平台：初始化服务注册 + 内置默认矿床（岩性-矿种绑定） ====
        registerDefaultOreDeposits();

        TerrainRecast.LOGGER.info("Terrain Recast registry bootstrap done.");
    }

    /**
     * 内置默认矿床。按 Cell 岩性绑定矿种，供 TerrainOreDepositFeature 等消费。
     *
     * <p>岩性编码对应 StrataDensityFunction.Lithology：1=花岗岩 3=石灰岩 4=砂岩。
     */
    private static void registerDefaultOreDeposits() {
        var reg = TerrainRecastApi.getOreRegistry();
        reg.register(new OreDeposit(1, net.minecraft.world.level.block.Blocks.GOLD_ORE.defaultBlockState()));
        reg.register(new OreDeposit(3, net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState()));
        reg.register(new OreDeposit(4, net.minecraft.world.level.block.Blocks.COAL_ORE.defaultBlockState()));
        reg.register(new OreDeposit(4, net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState(), 7, 0.55));
    }

    /**
     * 显式卸载（模组重载/测试夹具）时调：释放 ConfigState 并清空服务注册与事件订阅。
     *
     * <p><b>不要在 SERVER_STOPPED 调用</b>——矿床/注入表/事件订阅属于模组级注册，
     * {@link #init()} 只在模组初始化时跑一次，清掉之后下一次开世界不会自动恢复。
     * 世界级状态（CellLookup、ConfigState）由 {@code FabricLifecycleHandler} 管理。
     */
    public static void close() {
        ConfigHandler.clear();
        TerrainRecastApi.getPipeline().clear();
        TerrainRecastApi.getOreRegistry().clear();
        TerrainRecastApi.getFeatureInjection().clear();
        RecastEvents.clearAll();
    }
}
