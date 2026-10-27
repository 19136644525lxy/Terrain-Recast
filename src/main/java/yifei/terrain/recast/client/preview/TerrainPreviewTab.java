package yifei.terrain.recast.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import yifei.terrain.recast.TerrainRecast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * 创建世界界面的「地形预览」页签。
 *
 * <p>布局：种子输入框 → 地形高度图 → 重新生成/随机种子 → 状态行。
 *
 * <p>采样走 {@link Util#backgroundExecutor()}（复用 MC 自己的后台线程池，不新建全局线程），
 * 结果回到渲染线程再上传贴图。输入有 350ms 防抖；页签每次被选中（{@code doLayout}）
 * 都会与创建界面的种子同步一次——所以在"世界"页签改完种子切过来即可看到新图。
 *
 * <p>页签拿不到 tick 事件，防抖轮询挂在画布的渲染回调上（{@link TerrainPreviewMap#setOnRender}）。
 */
public final class TerrainPreviewTab extends GridLayoutTab {

    private static final int MAP_WIDTH = TerrainPreviewSampler.WIDTH;
    private static final int MAP_HEIGHT = TerrainPreviewSampler.HEIGHT;
    /** 顶部行两个按钮的固定宽度（剩下的都给种子框）。 */
    private static final int BUTTON_WIDTH = 96;
    /** 种子输入停顿多久后才重新采样（毫秒）。 */
    private static final long DEBOUNCE_MS = 350L;

    private final WorldCreationUiState uiState;
    private final EditBox seedEdit;
    private final TerrainPreviewMap map;
    private final StringWidget status;

    /** 请求序号：后台结果回来时序号已变则丢弃（防止旧种子覆盖新图）。 */
    private volatile int requestId;
    private boolean syncing;
    private boolean dirty = true;
    private long dirtyAt = System.currentTimeMillis();
    private boolean initialRequestDone;
    /** 上一次真正采样过的种子，避免同一张图被重复采样。 */
    private long renderedSeed = Long.MIN_VALUE;

    public TerrainPreviewTab(WorldCreationUiState uiState) {
        super(Component.translatable("createWorld.tab.terrain_recast.preview.title"));
        this.uiState = uiState;
        Font font = Minecraft.getInstance().font;

        this.seedEdit = new EditBox(font, 0, 0, MAP_WIDTH - 2 * BUTTON_WIDTH - 16, 20,
                Component.translatable("screen.terrain_recast.preview.seed"));
        this.seedEdit.setMaxLength(64);
        this.seedEdit.setHint(Component.translatable("screen.terrain_recast.preview.seed.hint"));
        this.seedEdit.setValue(uiState.getSeed());
        this.seedEdit.setResponder(value -> {
            // 预览页签里改种子 = 直接改这个世界将要用的种子
            this.uiState.setSeed(value);
            this.markDirty();
        });

        this.map = new TerrainPreviewMap(MAP_WIDTH, MAP_HEIGHT);
        this.map.setOnRender(this::poll);

        Button refreshButton = Button.builder(
                Component.translatable("screen.terrain_recast.preview.refresh"),
                button -> this.request(true)).width(BUTTON_WIDTH).build();
        Button randomButton = Button.builder(
                Component.translatable("screen.terrain_recast.preview.random"),
                button -> this.rollRandom()).width(BUTTON_WIDTH).build();
        // 种子框与两个按钮同一行，把纵向空间全留给地图
        LinearLayout topRow = LinearLayout.horizontal().spacing(8);
        topRow.addChild(this.seedEdit);
        topRow.addChild(refreshButton);
        topRow.addChild(randomButton);

        this.status = new StringWidget(
                Component.translatable("screen.terrain_recast.preview.idle"), font);

        GridLayout.RowHelper helper = this.layout.columnSpacing(8).rowSpacing(8).createRowHelper(1);
        helper.addChild(topRow);
        helper.addChild(this.map);
        helper.addChild(this.status);
    }

    @Override
    public void doLayout(ScreenRectangle area) {
        // 自适应：窗口/界面缩放变小时按页签区域收缩画布（贴图保持 640×300，绘制时缩放）。
        // 顶部行高度 20 + 两处间距 16 + 状态行 9 ≈ 45，留一点余量。
        int maxMapHeight = Math.max(120, area.height() - 60);
        int maxMapWidth = Math.max(240, area.width() - 24);
        this.map.setHeight(Math.min(MAP_HEIGHT, maxMapHeight));
        this.map.setWidth(Math.min(MAP_WIDTH, maxMapWidth));
        this.seedEdit.setWidth(Math.max(80, this.map.getWidth() - 2 * BUTTON_WIDTH - 16));

        super.doLayout(area);
        syncSeedFromState();
        if (!this.initialRequestDone) {
            this.initialRequestDone = true;
            this.request(false);
        } else {
            this.markDirty();
        }
    }

    /** 与创建世界界面的种子对齐（在世界页签改过种子时）。 */
    private void syncSeedFromState() {
        String stateSeed = this.uiState.getSeed();
        if (!stateSeed.equals(this.seedEdit.getValue())) {
            this.syncing = true;
            this.seedEdit.setValue(stateSeed);
            this.syncing = false;
        }
    }

    private void markDirty() {
        if (this.syncing) return;
        this.dirty = true;
        this.dirtyAt = System.currentTimeMillis();
    }

    /** 渲染帧轮询：输入停顿够久且有待刷新，就重新采样。 */
    private void poll() {
        if (!this.dirty) return;
        if (System.currentTimeMillis() - this.dirtyAt < DEBOUNCE_MS) return;
        this.dirty = false;
        // 渲染期不直接改 screen 状态（种子回填会触发 uiState 监听），挪到主线程任务队列
        Minecraft.getInstance().execute(() -> this.request(false));
    }

    /** 只有默认/放大生物群系世界类型会走本模组的 noise_settings。 */
    private boolean usesTrcTerrain() {
        Holder<WorldPreset> preset = this.uiState.getWorldType().preset();
        return preset == null || preset.is(WorldPresets.NORMAL) || preset.is(WorldPresets.LARGE_BIOMES);
    }

    private void rollRandom() {
        // 直接落一个具体种子进输入框：预览所见 = 世界实际所用（留空就是真随机，预览无法预知）
        this.setSeedText(Long.toString(WorldOptions.randomSeed()));
        this.request(true);
    }

    /** 写种子框并同步回创建界面（syncing 期间不触发重采样，避免撤销/回填造成多次请求）。 */
    private void setSeedText(String value) {
        this.syncing = true;
        this.seedEdit.setValue(value);
        this.syncing = false;
        this.uiState.setSeed(value);
    }

    private void request(boolean force) {
        if (!this.usesTrcTerrain()) {
            // 超平坦/放大化/调试世界用的不是 TRC 的 noise_settings，预览会误导
            this.status.setMessage(Component.translatable("screen.terrain_recast.preview.other_world_type"));
            this.dirty = false;
            return;
        }

        String raw = this.seedEdit.getValue().trim();
        long seed;
        if (raw.isEmpty()) {
            // 空种子 = 随机：生成一个并落回输入框，保证预览与最终世界用的是同一个种子
            seed = WorldOptions.randomSeed();
            this.setSeedText(Long.toString(seed));
        } else {
            seed = WorldOptions.parseSeed(raw).orElseGet(WorldOptions::randomSeed);
        }

        if (!force && seed == this.renderedSeed) {
            this.dirty = false;
            return;
        }
        this.renderedSeed = seed;
        this.dirty = false;

        int id = ++this.requestId;
        long startedAt = System.currentTimeMillis();
        this.status.setMessage(Component.translatable("screen.terrain_recast.preview.loading"));

        Util.backgroundExecutor().execute(() -> {
            NativeImage image;
            try {
                image = toImage(TerrainPreviewSampler.sample(seed));
            } catch (Throwable t) {
                TerrainRecast.LOGGER.error("Terrain preview sampling failed (seed={})", seed, t);
                Minecraft.getInstance().execute(() -> {
                    if (id == this.requestId) {
                        this.status.setMessage(Component.translatable(
                                "screen.terrain_recast.preview.failed", String.valueOf(t)));
                    }
                });
                return;
            }
            Minecraft.getInstance().execute(() -> {
                if (id != this.requestId) {
                    image.close(); // 过期结果：连图带贴图一起丢掉
                    return;
                }
                PreviewTextures.upload(image);
                long elapsed = System.currentTimeMillis() - startedAt;
                this.status.setMessage(Component.translatable(
                        "screen.terrain_recast.preview.info",
                        elapsed, TerrainPreviewSampler.BLOCKS_PER_PIXEL));
            });
        });
    }

    /** 像素 → NativeImage（后台线程调用；贴图上传必须回渲染线程）。 */
    private static NativeImage toImage(int[] pixels) {
        NativeImage image = new NativeImage(
                TerrainPreviewSampler.WIDTH, TerrainPreviewSampler.HEIGHT, false);
        for (int y = 0; y < TerrainPreviewSampler.HEIGHT; y++) {
            int row = y * TerrainPreviewSampler.WIDTH;
            for (int x = 0; x < TerrainPreviewSampler.WIDTH; x++) {
                image.setPixel(x, y, pixels[row + x]);
            }
        }
        return image;
    }
}
