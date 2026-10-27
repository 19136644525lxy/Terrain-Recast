package yifei.terrain.recast.commands;

import java.util.List;

import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.config.ConfigOption;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.CellLookup;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /terrainrecast 命令根注册。子命令 preset 见 {@link PresetCommand}。
 * 配置是世界级（config/terrain_recast.json），仅在进入世界（服务器）后生效。
 */
public final class TerrainRecastCommands {

    private TerrainRecastCommands() {}

    /** 挂到 CommandRegistrationCallback，随服务器注册所有命令。 */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("terrainrecast")
                        .then(PresetCommand.build())
                        .then(Commands.literal("info").executes(TerrainRecastCommands::executeInfo))
                        .then(Commands.literal("peak")
                                .executes(ctx -> executePeak(ctx, DEFAULT_RADIUS, DEFAULT_MIN_HEIGHT))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(64, 20000))
                                        .executes(ctx -> executePeak(ctx,
                                                IntegerArgumentType.getInteger(ctx, "radius"), DEFAULT_MIN_HEIGHT))
                                        .then(Commands.argument("minHeight",
                                                        DoubleArgumentType.doubleArg(-100, 3000))
                                                .executes(ctx -> executePeak(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        DoubleArgumentType.getDouble(ctx, "minHeight"))))))));
    }

    /** peak 的默认参数：半径 2000 格、高度 ≥ 800 格（相对海平面）。 */
    private static final int DEFAULT_RADIUS = 2000;
    private static final double DEFAULT_MIN_HEIGHT = 800.0;

    /**
     * peak：在解析地形场里找附近最高的几座山。
     *
     * <p>不生成区块、不读世界，纯采样 {@code TERRAIN_HEIGHT}，所以半径几千格也很便宜。
     * 参数：{@code /terrainrecast peak [radius] [minHeight]}。
     */
    private static int executePeak(CommandContext<CommandSourceStack> ctx, int radius, double minHeight) {
        CellLookup lookup = TerrainRecastApi.getCellLookup();
        if (lookup == null) {
            ctx.getSource().sendFailure(Component.translatable("command.terrain_recast.peak.not_ready"));
            return 0;
        }
        BlockPos center = BlockPos.containing(ctx.getSource().getPosition());
        List<PeakSearch.Peak> peaks = PeakSearch.search(lookup, center.getX(), center.getZ(), radius, minHeight, 5);
        if (peaks.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "command.terrain_recast.peak.none", radius, (int) minHeight), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.terrain_recast.peak.header", radius, peaks.size()), false);
        for (int i = 0; i < peaks.size(); i++) {
            PeakSearch.Peak p = peaks.get(i);
            int dist = (int) Math.round(Math.hypot(p.x() - center.getX(), p.z() - center.getZ()));
            int index = i + 1;
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "command.terrain_recast.peak.entry",
                    index, p.x(), p.z(), (int) Math.round(p.height()),
                    (int) Math.round(p.height()) + 63, dist), false);
        }
        return peaks.size();
    }

    /** info：输出配置关键值；玩家在场且 CellLookup 就绪时附加脚下地形字段。 */
    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double mountain = ConfigHandler.tuning(ConfigOption.MOUNTAIN_AMP.key(),
                ConfigOption.MOUNTAIN_AMP.defaultValue());
        double interior = ConfigHandler.tuning(ConfigOption.INTERIOR_AMP.key(),
                ConfigOption.INTERIOR_AMP.defaultValue());
        double minHeight = ConfigHandler.tuning(ConfigOption.MIN_CONTINENT_HEIGHT.key(),
                ConfigOption.MIN_CONTINENT_HEIGHT.defaultValue());
        source.sendSuccess(() -> Component.translatable(
                "command.terrain_recast.info.config", mountain, interior, minHeight), false);

        if (source.getEntity() instanceof ServerPlayer && TerrainRecastApi.getCellLookup() != null) {
            double bx = Math.floor(source.getPosition().x);
            double bz = Math.floor(source.getPosition().z);
            CellLookup lookup = TerrainRecastApi.getCellLookup();
            double height = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, (int) bx, (int) bz);
            double temp = lookup.sampleField(Cell.Field.TEMPERATURE, (int) bx, (int) bz);
            source.sendSuccess(() -> Component.translatable(
                    "command.terrain_recast.info.cell", height, temp), false);
        }
        return 1;
    }
}