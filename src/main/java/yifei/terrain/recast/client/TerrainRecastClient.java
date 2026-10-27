package yifei.terrain.recast.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

import yifei.terrain.recast.client.config.ConfigScreen;

/**
 * 客户端入口：注册一个快捷键打开地形配置界面。
 *
 * <p>配置是世界级快照，仅改文件与 ConfigHandler 状态；玩家重进/新建世界后生效。
 * 纯原版 Screen 实现，零外部 GUI 依赖。
 */
public class TerrainRecastClient implements ClientModInitializer {

    private static KeyMapping configKey;

    @Override
    public void onInitializeClient() {
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.terrain_recast.config",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (configKey.consumeClick() && client.gui.screen() == null) {
                client.setScreenAndShow(new ConfigScreen());
            }
        });
    }
}