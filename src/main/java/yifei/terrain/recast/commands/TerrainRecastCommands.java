package yifei.terrain.recast.commands;

import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.config.ConfigOption;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.CellLookup;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
                        .then(Commands.literal("info").executes(TerrainRecastCommands::executeInfo))));
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