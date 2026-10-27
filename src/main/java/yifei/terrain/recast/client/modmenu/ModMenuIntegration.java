package yifei.terrain.recast.client.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import yifei.terrain.recast.client.config.ConfigScreen;

/**
 * ModMenu 集成：让模组详情页右侧出现「配置」按钮，点击打开内置配置界面。
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> new ConfigScreen();
    }
}