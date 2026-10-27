package yifei.terrain.recast.commands;

import java.nio.file.Path;

import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.config.ConfigState;
import yifei.terrain.recast.config.Preset;
import yifei.terrain.recast.config.PresetCodec;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /terrainrecast preset 子命令：list / apply / export / import。
 * 配置是世界级，写入 config/terrain_recast.json（世界重置/重进后生效）。
 */
final class PresetCommand {

    private PresetCommand() {}

    /** 构建 preset 命令节点：/terrainrecast preset ... */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("preset")
                .then(Commands.literal("list").executes(PresetCommand::executeList))
                // apply/import 会写配置（世界级副作用），要求 OP 权限等级 2
                .then(Commands.literal("apply").requires(PresetCommand::isOp)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(PresetCommand::executeApply)))
                .then(Commands.literal("export").executes(PresetCommand::executeExport))
                .then(Commands.literal("import").requires(PresetCommand::isOp)
                        .then(Commands.argument("code", StringArgumentType.string())
                                .executes(PresetCommand::executeImport)));
    }

    /** 写操作的权限门槛：等级 2（OP / 命令方块）。 */
    private static boolean isOp(CommandSourceStack source) {
        return source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder();
        for (Preset p : Preset.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.id());
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.terrain_recast.preset.list.header", sb.toString()), false);
        return 1;
    }

    private static int executeApply(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        Preset preset = findPreset(id);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.translatable(
                    "command.terrain_recast.preset.apply.not_found", id));
            return 0;
        }
        ConfigState state = ConfigHandler.getState();
        if (state == null) {
            state = ConfigHandler.createDefaultState();
            ConfigHandler.setState(state);
        }
        preset.applyTo(state);
        ConfigHandler.save(state, configFile());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.terrain_recast.preset.apply.success",
                Component.translatable(preset.langKey())), false);
        return 1;
    }

    private static int executeExport(CommandContext<CommandSourceStack> ctx) {
        ConfigState state = ConfigHandler.getState();
        if (state == null) {
            state = ConfigHandler.createDefaultState();
        }
        String code = PresetCodec.encode(state);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.terrain_recast.preset.export.success", code), false);
        return 1;
    }

    private static int executeImport(CommandContext<CommandSourceStack> ctx) {
        String code = StringArgumentType.getString(ctx, "code");
        ConfigState state;
        try {
            state = PresetCodec.decode(code);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.translatable(
                    "command.terrain_recast.preset.import.invalid", e.getMessage()));
            return 0;
        }
        ConfigHandler.setState(state);
        ConfigHandler.save(state, configFile());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.terrain_recast.preset.import.success"), false);
        return 1;
    }

    /** 严格查找预设，未知返回 null（避免 byId 对未知 id 回退为默认）。 */
    private static Preset findPreset(String id) {
        for (Preset p : Preset.values()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /** 配置文件路径：config/terrain_recast.json。 */
    static Path configFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("terrain_recast.json");
    }
}