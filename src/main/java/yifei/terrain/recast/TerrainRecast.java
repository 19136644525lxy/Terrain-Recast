package yifei.terrain.recast;

import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.commands.TerrainRecastCommands;
import yifei.terrain.recast.core.RegistryBootstrap;
import yifei.terrain.recast.loaders.fabric.FabricLifecycleHandler;
import yifei.terrain.recast.worldgen.feature.OreDepositWorldgen;
import yifei.terrain.recast.worldgen.feature.VegetationWorldgen;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerrainRecast implements ModInitializer {
    public static final String MOD_ID = "terrain_recast";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        RegistryBootstrap.init();
        FabricLifecycleHandler.init();
        TerrainRecastCommands.register();
        // 挂载程序化植被：FEATURE_TYPE 登记在 RegistryBootstrap.init() 内，先于本调用。
        VegetationWorldgen.register();
        // 挂载地质矿床：同在地物类型注册之后，注入 UNDERGROUND_ORES 步骤。
        OreDepositWorldgen.register();
        // 统一下发地物注入登记表（植被 + 矿床 + 附属登记项）。
        // 统一下发地物注入登记表（植被 + 矿床 + 附属登记项）。
        // 附属若在 TRC 之后才登记，可自行再调一次 apply()——已下发的条目不会重复注入。
        TerrainRecastApi.getFeatureInjection().apply();
    }

    /** 便捷创建命名空间标识符。 */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
