package yifei.terrain.recast.worldgen.feature;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import yifei.terrain.recast.TerrainRecast;
import yifei.terrain.recast.api.TerrainRecastApi;

/**
 * 植被地物的世界生成接线——把程序化植被 placed feature 注入原版主世界群系。
 *
 * <p>原理：本项目沿用原版 MultiNoiseBiomeSource + 原版群系；若用数据包覆写群系 JSON 加地物
 * 会连带丢失其原版 features（矿脉/洞穴/原版树），故改由 Fabric biome 事件、以编程方式把
 * placed feature 追加进每个满足条件的主世界群系的植被生成步骤。地物内部再按 Cell 管线的
 * 温度/湿度/海拔/陡峭度最终决定是否落树、落什么树，因此对"原生群系归属"不敏感。
 *
 * <p>注入统一走 {@link TerrainRecastApi#getFeatureInjection()} 登记表（不再直接调
 * {@code BiomeModifications}），由 {@code TerrainRecast.onInitialize()} 末尾一次性 apply；
 * 附属可用同一张表追加自己的地物。
 */
public final class VegetationWorldgen {

    private VegetationWorldgen() {}

    /** 置于植被生成步骤的 placed feature 注册键（数据在
     *  data/terrain_recast/worldgen/placed_feature/terrain_vegetation.json）。 */
    private static final ResourceKey<PlacedFeature> PLACED = ResourceKey.create(
            Registries.PLACED_FEATURE, TerrainRecast.id("terrain_vegetation"));

    /** 地表覆盖物 placed feature（草/花/蕨/仙人掌），数据同目录 ground_cover.json。 */
    private static final ResourceKey<PlacedFeature> GROUND = ResourceKey.create(
            Registries.PLACED_FEATURE, TerrainRecast.id("ground_cover"));

    /** ModInitializer 阶段调用一次：只登记，不下发（下发由 TerrainRecast 末尾统一 apply）。 */
    public static void register() {
        // 时序：RegistryBootstrap.init()（置入 FEATURE_TYPE 类型）先于本方法执行，类型已可用。
        var injection = TerrainRecastApi.getFeatureInjection();
        // 注入全主世界（overworld 选择器）：瘦身到少数自定义群系后，其参数空间极窄，
        // 若只注入 terrain_recast 命名空间，玩家绝大多数时间站在原版群系上，看不到本模组植被。
        // 安全前提：本 vegetation 地物自绘树（不引用任何原版 placed feature），注入原版群系
        // 不会触发 Feature order cycle；落不落树仍由 Feature.place() 内 Cell 管线
        // (温度/湿度/海拔/陡峭度) 做最终决策——沙漠/高山依旧不长树。
        var selector = yifei.terrain.recast.api.injection.GroupedFeatureInjection.OVERWORLD;
        injection.add(GenerationStep.Decoration.VEGETAL_DECORATION, PLACED, selector);
        injection.add(GenerationStep.Decoration.VEGETAL_DECORATION, GROUND, selector);
    }
}
