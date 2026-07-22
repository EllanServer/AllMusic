package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.AllMusicCore;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Adds AllMusic's visual settings to Sodium's official 26.2 config UI. */
public final class AllMusicSodiumConfig implements ConfigEntryPoint {
    private final StorageEventHandler storage = AllMusicCore::saveConfig;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        Identifier modernHud = id("modern_hud");
        Identifier nameplatesBackground = id("lyric_background");

        var appearance = builder.createOptionGroup()
                .setName(Component.literal("外观"))
                .addOption(builder.createBooleanOption(modernHud)
                        .setName(Component.literal("ActionBar 歌词"))
                        .setTooltip(Component.literal("只在原生 ActionBar 上方显示一行当前歌词；关闭后回到 AllMusic 经典布局。"))
                        .setStorageHandler(storage)
                        .setBinding(value -> AllMusicCore.config.modernHud = value,
                                () -> AllMusicCore.config.modernHud)
                        .setDefaultValue(true))
                .addOption(builder.createBooleanOption(nameplatesBackground)
                        .setName(Component.literal("CustomNameplates 背景"))
                        .setTooltip(Component.literal("使用 CustomNameplates 的 bedrock_2 ActionBar 位图样式；不需要自定义着色器。"))
                        .setStorageHandler(storage)
                        .setBinding(value -> AllMusicCore.config.lyricHudBackground = value,
                                () -> AllMusicCore.config.lyricHudBackground)
                        .setDefaultValue(true));

        var layout = builder.createOptionGroup()
                .setName(Component.literal("布局"))
                .addOption(builder.createIntegerOption(id("hud_width"))
                        .setName(Component.literal("歌词最大宽度"))
                        .setTooltip(Component.literal("背景会按当前歌词长度自动伸缩；歌词过长时才会在这个宽度内跟随演唱进度平滑移动。"))
                        .setStorageHandler(storage)
                        .setBinding(value -> AllMusicCore.config.modernHudWidth = value,
                                () -> AllMusicCore.config.modernHudWidth)
                        .setDefaultValue(168)
                        .setRange(96, 320, 8)
                        .setValueFormatter(value -> Component.literal(value + " px")))
                .addOption(builder.createIntegerOption(id("bottom_offset"))
                        .setName(Component.literal("底部距离"))
                        .setTooltip(Component.literal("86 px 位于 Minecraft 原生 ActionBar 上方；增大数值会继续向上移动。"))
                        .setStorageHandler(storage)
                        .setBinding(value -> AllMusicCore.config.lyricHudBottomOffset = value,
                                () -> AllMusicCore.config.lyricHudBottomOffset)
                        .setDefaultValue(86)
                        .setRange(36, 160, 2)
                        .setValueFormatter(value -> Component.literal(value + " px")));

        var material = builder.createOptionGroup()
                .setName(Component.literal("背景样式"))
                .addOption(builder.createIntegerOption(id("background_opacity"))
                        .setName(Component.literal("背景透明度"))
                        .setTooltip(Component.literal("100% 对应 CustomNameplates 原版的 112/255 黑色透明度。"))
                        .setStorageHandler(storage)
                        .setBinding(value -> AllMusicCore.config.modernHudOpacity = value,
                                () -> AllMusicCore.config.modernHudOpacity)
                        .setDefaultValue(100)
                        .setRange(0, 100, 5)
                        .setValueFormatter(value -> Component.literal(value + "%")));

        builder.registerOwnModOptions()
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xDCEBFF))
                .setNonTintedIcon(id("icon.png"))
                .addPage(builder.createOptionPage()
                        .setName(Component.literal("歌词 HUD"))
                        .addOptionGroup(appearance)
                        .addOptionGroup(layout)
                        .addOptionGroup(material));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(AllMusicClient.MODID, path);
    }
}
