package yifei.terrain.recast.client.config;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import yifei.terrain.recast.client.config.imports.ImportPresetScreen;
import yifei.terrain.recast.config.ConfigHandler;
import yifei.terrain.recast.config.ConfigOption;
import yifei.terrain.recast.config.ConfigState;
import yifei.terrain.recast.config.Preset;

/**
 * 地形配置界面（内置原版屏幕，零外部依赖）。
 *
 * <p>按 {@link ConfigOption.Category} 分页，每项一个数字输入框，输入即时夹取到合法范围。
 * 保存时写入 {@code config/terrain_recast.json} 并更新 ConfigHandler 状态；
 * 因地形是世界级快照，改动需重进世界/新建世界才生效（界面顶部有提示）。
 *
 * <p>不做 ThreadLocal/全局开关：直接作用于 ConfigHandler 持有的一份状态副本。
 */
public class ConfigScreen extends Screen {

    private static final int TAB_TOP = 42;
    private static final int TAB_HEIGHT = 20;
    private static final int ROW_TOP = 78;
    private static final int ROW_HEIGHT = 26;
    private static final int LEFT = 12;
    private static final int LABEL_W = 150;
    private static final int BOX_X = LEFT + LABEL_W + 8;
    private static final int BOX_W = 140;

    /** 正在编辑的配置副本（拷贝自 ConfigHandler，保存时才提交）。 */
    private final ConfigState work;

    private ConfigOption.Category current = ConfigOption.Category.TERRAIN;
    private final List<Button> tabs = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    /** 当前页显示的 选项 ⇄ 输入框 对。 */
    private record Entry(ConfigOption opt, EditBox box) {}

    @SuppressWarnings("DataFlowIssue")
    public ConfigScreen() {
        super(Component.translatable("screen.terrain_recast.title"));
        ConfigState cur = ConfigHandler.getState();
        ConfigState copy = ConfigHandler.createDefaultState();
        if (cur != null) {
            for (ConfigOption o : ConfigOption.values()) {
                copy.putValue(o.key(), cur.getValue(o.key()));
            }
        }
        this.work = copy;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        addNote();
        rebuildTabs();
        rebuildRows(current);
        addPresets();
        addPresetIo();
        addSaveAndReset();
    }

    /** 顶部提示：世界级快照生效说明。用 StringWidget 走 ActiveTextCollector 落屏路径。 */
    private void addNote() {
        Component note = Component.translatable("screen.terrain_recast.note")
                .withStyle(st -> st.withColor(0x8080A0));
        StringWidget label = new StringWidget(note, this.font);
        label.setX(LEFT);
        label.setY(TAB_TOP - 10);
        this.addRenderableWidget(label);
    }

    /** 右侧预设按钮：点击把预设覆盖值写入 work 并刷新当前页。 */
    private void addPresets() {
        int x = this.width - 140;
        int y = 80;
        for (Preset p : Preset.values()) {
            Button preset = Button.builder(Component.translatable(p.langKey()),
                    b -> { p.applyTo(work); init(); })
                    .bounds(x, y, 130, 20).build();
            this.addRenderableWidget(preset);
            y += 24;
        }
    }

    /** 顶部类别页签。 */
    private void rebuildTabs() {
        int total = ConfigOption.Category.values().length;
        int w = 90;
        int gap = 6;
        for (ConfigOption.Category cat : ConfigOption.Category.values()) {
            Button tab = Button.builder(
                    Component.literal(cat == current ? "» " + catLabel(cat) : catLabel(cat)),
                    b -> { flush(); current = cat; init(); })
                    .bounds(LEFT + (tabIndex(cat)) * (w + gap), TAB_TOP, w, TAB_HEIGHT)
                    .build();
            this.addRenderableWidget(tab);
            tabs.add(tab);
        }
    }

    /** 当前类别下的选项行。 */
    private void rebuildRows(ConfigOption.Category cat) {
        entries.clear();
        int y = ROW_TOP;
        for (ConfigOption opt : ConfigOption.values()) {
            if (opt.category() != cat) continue;
            // 选项名标签：走 StringWidget 的 ActiveTextCollector 落屏路径（同按钮文字）
            Component name = Component.translatable("config.terrain_recast." + opt.key())
                    .withStyle(st -> st.withColor(0xE0E0E0));
            StringWidget label = new StringWidget(name, this.font);
            label.setX(LEFT);
            label.setY(y + 1);
            this.addRenderableWidget(label);

            EditBox box = new EditBox(this.font, BOX_X, y, BOX_W, 18, Component.literal(""));
            box.setMaxLength(16);
            box.setValue(String.valueOf(work.getValue(opt.key())));
            this.addRenderableWidget(box);
            entries.add(new Entry(opt, box));
            y += ROW_HEIGHT;
        }
    }

    /** 预设目录：config/terrain_recast/presets/，可放入/分享 .json 文件。 */
    public static java.nio.file.Path presetsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("terrain_recast/presets");
    }

    /** 底部预设导入/导出按钮。 */
    private void addPresetIo() {
        int y = this.height - 52;
        Button imp = Button.builder(
                Component.translatable("screen.terrain_recast.import"),
                b -> { if (this.minecraft != null) this.minecraft.setScreenAndShow(new ImportPresetScreen()); })
                .bounds(12, y, 80, 20).build();
        Button exp = Button.builder(
                Component.translatable("screen.terrain_recast.export"),
                b -> exportPreset())
                .bounds(98, y, 80, 20).build();
        this.addRenderableWidget(imp);
        this.addRenderableWidget(exp);
    }

    /** 导出当前 work 到预设目录，文件名带时间戳。 */
    private void exportPreset() {
        flush();
        String stamp = String.format("%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS", System.currentTimeMillis());
        ConfigHandler.save(work, presetsDir().resolve("preset_" + stamp + ".json"));
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.translatable("screen.terrain_recast.exported"));
        }
    }

    private void addSaveAndReset() {
        int y = this.height - 28;
        Button save = Button.builder(
                Component.translatable("screen.terrain_recast.save"),
                b -> commitAndClose())
                .bounds(this.width - 92 - 96, y, 92, 20).build();
        Button reset = Button.builder(
                Component.translatable("screen.terrain_recast.reset"),
                b -> { for (ConfigOption o : ConfigOption.values()) work.putValue(o.key(), o.defaultValue()); init(); })
                .bounds(this.width - 92 - 96 - 100 - 6, y, 100, 20).build();
        Button cancel = Button.builder(
                Component.translatable("screen.terrain_recast.cancel"),
                b -> this.onClose())
                .bounds(this.width - 92, y, 92, 20).build();
        this.addRenderableWidget(cancel);
        this.addRenderableWidget(reset);
        this.addRenderableWidget(save);
    }

    /** 把当前页输入框写回 work。 */
    private void flush() {
        for (Entry e : entries) {
            work.putValue(e.opt().key(), parse(e.box().getValue(), e.opt().defaultValue()));
        }
    }

    /** 保存：flush → 写文件 → 更新 ConfigHandler 状态 → 关闭。 */
    private void commitAndClose() {
        flush();
        ConfigHandler.setState(work);
        ConfigHandler.save(work, configFile());
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.translatable("screen.terrain_recast.saved"));
        }
        this.onClose();
    }

    private static String catLabel(ConfigOption.Category cat) {
        return Component.translatable("config.terrain_recast.category." + cat.name()).getString();
    }

    private static int tabIndex(ConfigOption.Category cat) {
        return cat.ordinal();
    }

    /** 主配置文件路径：config/terrain_recast.json。 */
    public static java.nio.file.Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("terrain_recast.json");
    }

    private static double parse(String s, double fallback) {
        if (s == null) return fallback;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}