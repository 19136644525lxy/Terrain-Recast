package yifei.terrain.recast.client.preview;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

/**
 * 预览画布：按 {@link PreviewTextures#currentId()} 绘制当前贴图，没有图时显示深色底。
 *
 * <p>不参与交互（{@link #isActive()} 恒 false），只在渲染时把当前纹理贴到自己的矩形里。
 * 每帧末尾回调 {@code onRender}，供页签做"输入停顿后再重采样"的轮询（页签拿不到 tick 事件）。
 */
final class TerrainPreviewMap extends AbstractWidget {

    private @Nullable Runnable onRender;

    TerrainPreviewMap(int width, int height) {
        super(0, 0, width, height, CommonComponents.EMPTY);
    }

    void setOnRender(@Nullable Runnable onRender) {
        this.onRender = onRender;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        graphics.fill(x, y, x + w, y + h, 0xFF101016);
        Identifier texture = PreviewTextures.currentId();
        if (texture != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, w, h,
                    TerrainPreviewSampler.WIDTH, TerrainPreviewSampler.HEIGHT);
        }
        // 1px 边框，让画布在页签背景上有明确边界
        graphics.fill(x - 1, y - 1, x + w + 1, y, 0xFF3C3C4A);
        graphics.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFF3C3C4A);
        graphics.fill(x - 1, y, x, y + h, 0xFF3C3C4A);
        graphics.fill(x + w, y, x + w + 1, y + h, 0xFF3C3C4A);

        if (this.onRender != null) {
            this.onRender.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // 纯视觉控件，无需朗读
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent navigationEvent) {
        return null;
    }
}
