package yifei.terrain.recast.loaders.fabric;

import java.nio.file.Path;

import yifei.terrain.recast.TerrainRecast;
import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.api.event.RecastEvents;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.config.ConfigState;
import yifei.terrain.recast.worldgen.cell.CellLookup;
import yifei.terrain.recast.worldgen.cell.RealCellLookup;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Fabric 端生命周期事件绑定。
 *
 * <p>关键时序：{@code SERVER_STARTED} 是在服务器<b>生成完出生点区块之后</b>才触发的，
 * 若只在那里注入，出生点区块会在 CellLookup 为空的情况下生成，地形被削平，
 * 与后续正常区块在区块前沿形成垂直断崖。因此优先在
 * {@code SERVER_STARTING}（loadLevel 入口，任何区块生成之前）注入，
 * {@code SERVER_STARTED} 仅作兜底，按 seed 幂等去重。
 *
 * <p>{@code SERVER_STOPPED}：close() 当前 CellLookup，释放缓存与对象池。
 */
public final class FabricLifecycleHandler {

    private static volatile CellLookup currentLookup;
    private static volatile long currentSeed;
    private static volatile boolean hasLookup;
    private static boolean configLoaded;

    private FabricLifecycleHandler() {}

    /** 配置文件路径：config/terrain_recast.json（Fabric 配置目录）。 */
    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("terrain_recast.json");
    }

    public static void init() {
        TerrainRecast.LOGGER.info("Binding Fabric lifecycle events...");

        ServerLifecycleEvents.SERVER_STARTING.register(FabricLifecycleHandler::onServerStarting);
        // 兜底：若 SERVER_STARTING 拿不到世界配置，至少在服务器启动完成后可用
        ServerLifecycleEvents.SERVER_STARTED.register(FabricLifecycleHandler::ensureLookup);
        ServerLifecycleEvents.SERVER_STOPPED.register(FabricLifecycleHandler::onServerStopped);

        // 区块生成完成事件：Fabric 4.x 的 CHUNK_LOAD 第三个参数标出"本次是否真正生成"
        // （false = 从盘加载/ProtoChunk 转正），正好是 CHUNK_GENERATED 需要的语义。
        // 不用 CHUNK_GENERATE：它在 4.1.9 已标记 @Deprecated，且同样只在此布尔为真时触发。
        ServerChunkEvents.CHUNK_LOAD.register(FabricLifecycleHandler::onChunkLoad);
    }

    /** 发布 CHUNK_GENERATED。无订阅者直接短路：不为没人听的事件构造载荷（设计文档 8.3）。 */
    private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (!generated) return;
        if (RecastEvents.CHUNK_GENERATED.subscriberCount() == 0) return;
        var pos = chunk.getPos();
        RecastEvents.CHUNK_GENERATED.post(new RecastEvents.ChunkGenerated(pos.x(), pos.z()));
    }

    /** 服务器启动加载配置（仅首次），保证首个区块生成前 RealCellLookup 拿到生效系数。 */
    private static synchronized void onServerStarting(MinecraftServer server) {
        ConfigState state = ConfigHandler.getState();
        if (state == null) {
            state = ConfigHandler.createDefaultState();
            ConfigHandler.setState(state);
        }
        if (configLoaded) return;
        if (ConfigHandler.load(state, configFile())) {
            TerrainRecast.LOGGER.info("Config loaded from {}", configFile());
        } else {
            TerrainRecast.LOGGER.info("No config file yet at {}, using defaults", configFile());
            ConfigHandler.save(state, configFile());
        }
        configLoaded = true;
        ensureLookup(server);
    }

    private static synchronized void ensureLookup(MinecraftServer server) {
        long seed = server.getWorldGenSettings().options().seed();
        if (hasLookup && currentSeed == seed) return;

        RealCellLookup lookup = new RealCellLookup(seed);
        CellLookup old = currentLookup;
        currentLookup = lookup;
        currentSeed = seed;
        hasLookup = true;
        TerrainRecastApi.setCellLookup(lookup);

        if (old != null) {
            try { old.close(); } catch (Exception e) { TerrainRecast.LOGGER.warn("CellLookup.close() threw", e); }
        }
        TerrainRecast.LOGGER.info("CellLookup ready before chunk generation, worldSeed={}", seed);
    }

    private static synchronized void onServerStopped(MinecraftServer server) {
        TerrainRecast.LOGGER.info("Server stopped, saving config and releasing CellLookup");

        ConfigState state = ConfigHandler.getState();
        if (state != null) {
            ConfigHandler.save(state, configFile());
        }
        configLoaded = false;

        CellLookup lookup = currentLookup;
        if (lookup != null) {
            try { lookup.close(); } catch (Exception e) { TerrainRecast.LOGGER.warn("CellLookup.close() threw", e); }
            currentLookup = null;
        }
        hasLookup = false;
        TerrainRecastApi.setCellLookup(null);
    }
}
