package yifei.terrain.recast.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import yifei.terrain.recast.TerrainRecast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

/**
 * 预览贴图的持有者。整个屏幕只留一张预览贴图：新图上传后立即释放旧图，
 * 避免反复开关创建世界界面时在 TextureManager 里堆积。
 *
 * <p>线程契约：只允许在渲染线程调用（DynamicTexture 会直接建 GPU 纹理）。
 * {@code currentId()} 供控件在渲染时取当前贴图。
 */
final class PreviewTextures {

    private static @Nullable Identifier currentId;
    private static @Nullable DynamicTexture currentTexture;
    private static int counter;

    private PreviewTextures() {}

    /** 上传一张新预览图并释放上一张，返回新贴图 id（渲染线程调用）。 */
    static synchronized Identifier upload(NativeImage image) {
        Identifier id = Identifier.fromNamespaceAndPath(
                TerrainRecast.MOD_ID, "preview/map_" + (counter++));
        DynamicTexture texture = new DynamicTexture(() -> "terrain_recast:preview", image);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getTextureManager().register(id, texture);

        Identifier oldId = currentId;
        DynamicTexture oldTexture = currentTexture;
        currentId = id;
        currentTexture = texture;
        if (oldId != null && oldTexture != null) {
            minecraft.getTextureManager().release(oldId);
            oldTexture.close();
        }
        return id;
    }

    static @Nullable Identifier currentId() {
        return currentId;
    }
}
