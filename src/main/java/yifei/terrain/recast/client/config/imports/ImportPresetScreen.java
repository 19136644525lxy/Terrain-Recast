package yifei.terrain.recast.client.config.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import yifei.terrain.recast.client.config.ConfigScreen;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.config.ConfigState;

/**
 * 导入预设界面：列出预设目录下的 .json 文件，点击即加载并覆盖当前配置。
 *
 * <p>文件名即按钮（去扩展名），无文件时给出放置路径提示。
 * 导入后 {@link ConfigHandler} 状态已更新，返回配置界面会自动读到新值。
 */
public class ImportPresetScreen extends Screen {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("terrain_recast/import");
    private static final int LEFT = 12;
    private final List<Path> presets = new ArrayList<>();

    public ImportPresetScreen() {
        super(Component.translatable("screen.terrain_recast.import.title"));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        listPresets();

        // 顶部返回（cancel）按钮，连同已有的返回交互，退回上一个屏
        Button back = Button.builder(Component.translatable("screen.terrain_recast.back"),
                b -> this.onClose())
                .bounds(LEFT, 8, 70, 20).build();
        this.addRenderableWidget(back);

        // 标题文本： StringWidget 走 ActiveTextCollector 落屏路径
        Component title = Component.translatable("screen.terrain_recast.import.title");
        StringWidget titleWidget = new StringWidget(title, this.font);
        titleWidget.setX((this.width - titleWidget.getWidth()) / 2);
        titleWidget.setY(12);
        this.addRenderableWidget(titleWidget);

        int y = 48;
        int w = 220;
        int x = (this.width - w) / 2;
        if (presets.isEmpty()) {
            Component none = Component.translatable("screen.terrain_recast.import.none");
            StringWidget label = new StringWidget(none, this.font);
            label.setX((this.width - label.getWidth()) / 2);
            label.setY(y);
            this.addRenderableWidget(label);
            return;
        }
        for (Path f : presets) {
            String fileName = f.getFileName().toString();
            String name = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
            Button btn = Button.builder(Component.literal(name), b -> importPreset(f))
                    .bounds(x, y, w, 20).build();
            this.addRenderableWidget(btn);
            y += 24;
        }
    }

    /** 收集预设目录下所有 .json 文件。 */
    private void listPresets() {
        presets.clear();
        Path dir = ConfigScreen.presetsDir();
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(presets::add);
        } catch (IOException e) {
            LOGGER.error("Failed to list preset directory {}", dir, e);
        }
    }

    /** 加载预设 → 覆盖状态 → 写回主配置 → 刷新回配置界面。 */
    private void importPreset(Path file) {
        ConfigState s = ConfigHandler.createDefaultState();
        ConfigHandler.load(s, file);
        ConfigHandler.setState(s);
        ConfigHandler.save(s, ConfigScreen.configFile());
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.translatable("screen.terrain_recast.imported"));
        }
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new ConfigScreen());
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}