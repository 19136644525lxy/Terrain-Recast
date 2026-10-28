package yifei.terrain.recast.worldgen.feature;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import yifei.terrain.recast.TerrainRecast;
import yifei.terrain.recast.api.TerrainRecastApi;

/**
 * 矿床地物的世界生成接线——把四组程序化矿脉 placed feature 注入主世界 UNDERGROUND_ORES 步骤。
 *
 * <p>与植被同思路：沿用原版群系 + Fabric biome 事件编程注入，不动群系 JSON。每类矿各占一个
 * placed feature，其深度带由 placed 数据的 height_range 决定（煤浅→钻石深），Feature 内部再按
 * Cell 侵蚀/湿度/岩性做地质省门控，形成"省内有脉、省外空"的分布。
 *
 * <p>注入统一走 {@link TerrainRecastApi#getFeatureInjection()} 登记表，
 * 由 {@code TerrainRecast.onInitialize()} 末尾一次性 apply。
 */
public final class OreDepositWorldgen {

    private OreDepositWorldgen() {}

    // 四组矿脉的 placed feature 注册键（数据在 data/terrain_recast/worldgen/placed_feature/）
    private static final ResourceKey<PlacedFeature> COAL = key("terrain_ore_coal");
    private static final ResourceKey<PlacedFeature> IRON = key("terrain_ore_iron");
    private static final ResourceKey<PlacedFeature> GOLD = key("terrain_ore_gold");
    private static final ResourceKey<PlacedFeature> DIAMOND = key("terrain_ore_diamond");

    private static ResourceKey<PlacedFeature> key(String path) {
        return ResourceKey.create(Registries.PLACED_FEATURE, TerrainRecast.id(path));
    }

    /** ModInitializer 调用一次：只登记，下发由 TerrainRecast 末尾统一 apply。 */
    public static void register() {
        var injection = TerrainRecastApi.getFeatureInjection();
        var step = GenerationStep.Decoration.UNDERGROUND_ORES;
        // 注入全部原版主世界群系：自定义群系已于 2026-10-27 下线，TRC_BIOMES 选择器不再命中任何群系。
        // 所有群系统一追加同一批 placed feature，顺序一致，不会产生 Feature order cycle。
        var ours = yifei.terrain.recast.api.injection.GroupedFeatureInjection.OVERWORLD;
        injection.add(step, COAL, ours);
        injection.add(step, IRON, ours);
        injection.add(step, GOLD, ours);
        injection.add(step, DIAMOND, ours);
    }
}
