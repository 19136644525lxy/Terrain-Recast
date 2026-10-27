package yifei.terrain.recast.mixin.client;

import java.util.Arrays;

import yifei.terrain.recast.client.preview.TerrainPreviewTab;

import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 向创建世界界面的页签栏追加「地形预览」页签。
 *
 * <p>{@code CreateWorldScreen.init()} 里一次性构造 3 个页签：
 * {@code MenuTabBar.builder(...).addTabs(new GameTab(), new WorldTab(), new MoreTab())}。
 * 这里用 {@code @ModifyArg} 把那个变长参数数组换成多带一个页签的数组——
 * 页签栏的 {@code arrangeElements} 会按页签数量均分宽度，所以无需改布局代码。
 *
 * <p>本 Mixin 是客户端专用（在 mixins.json 的 client 段登记），不会进入服务端。
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Shadow
    @Final
    private WorldCreationUiState uiState;

    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;"),
            index = 0)
    private Tab[] terrainRecast$appendPreviewTab(Tab[] tabs) {
        Tab[] extended = Arrays.copyOf(tabs, tabs.length + 1);
        extended[tabs.length] = new TerrainPreviewTab(this.uiState);
        return extended;
    }
}
