package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.AllMusicHud;
import com.coloryr.allmusic.client.core.Point2f;
import com.coloryr.allmusic.client.core.render.TextFrameBuffer;
import com.coloryr.allmusic.codec.HudPosType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

public class CoreRenderTarget extends TextFrameBuffer<Component> {
    private static final Pattern LEGACY_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private final boolean isState;
    private final boolean forcePlainColor;

    public CoreRenderTarget(String name) {
        isState = name.equals("state");
        forcePlainColor = name.startsWith("modern lyric");
    }

    @Override
    public void putText(String text, int y, int color, boolean shadow) {
        color = (color & 0x00FFFFFF) | 0xFF000000;
        Component parsed = MiniMessage.parse(text);
        Component component = forcePlainColor
                ? Component.literal(LEGACY_FORMATTING.matcher(parsed.getString()).replaceAll(""))
                : parsed;
        Font font = Minecraft.getInstance().font;
        int width = font.width(component);
        if (width == 0) {
            return;
        }

        int height = font.lineHeight + (shadow ? 1 : 0);
        if (isState) {
            y = 0;
        }
        texts.add(new TextItem<>(width, height, y, component, shadow, color));
    }

    @Override
    public void draw(float alpha, int x, int y, int maxWidth, HudPosType dir) {
        if (texts.isEmpty()) {
            return;
        }

        GuiGraphicsExtractor gui = AllMusicClient.context;
        if (gui == null) return;
        Font font = Minecraft.getInstance().font;

        for (TextItem<Component> entry : texts) {
            int displayWidth = maxWidth != -1 ? Math.min(entry.width, maxWidth) : entry.width;
            Point2f point = AllMusicHud.getPos(displayWidth, entry.height, x, y, dir);

            int drawX = (int) point.x;
            int drawY = (int) (point.y + entry.y);
            int finalColor = applyAlpha(entry.color, alpha);

            if (maxWidth != -1 && entry.width > maxWidth) {
                int scrollOffset = (int) getOffset(entry, maxWidth);
                gui.enableScissor(drawX, Math.max(0, drawY), drawX + maxWidth, drawY + entry.height);
                gui.text(font, entry.component, drawX - scrollOffset, drawY, finalColor, entry.shadow);
                if (scrollOffset > 0) {
                    gui.text(font, entry.component, drawX - scrollOffset + entry.width, drawY, finalColor, entry.shadow);
                }
                gui.disableScissor();
            } else {
                gui.text(font, entry.component, drawX, drawY, finalColor, entry.shadow);
            }
        }
    }

    @Override
    public void drawLine(float x, float y, float alpha, int line) {
        if (texts.isEmpty()) {
            return;
        }
        if (line >= texts.size()) {
            return;
        }
        GuiGraphicsExtractor gui = AllMusicClient.context;
        if (gui == null) return;
        TextItem<Component> entry = texts.get(line);
        gui.text(Minecraft.getInstance().font, entry.component,
                (int) x, (int) (y + entry.y),
                applyAlpha(entry.color, alpha), entry.shadow);
    }

    @Override
    public void drawWithState(float alpha, int x, int y, int maxWidth, float state, HudPosType dir) {
        if (texts.isEmpty()) {
            return;
        }
        GuiGraphicsExtractor gui = AllMusicClient.context;
        if (gui == null) return;
        Font font = Minecraft.getInstance().font;

        for (TextItem<Component> entry : texts) {
            int displayWidth = maxWidth != -1 ? Math.min(entry.width, maxWidth) : entry.width;
            Point2f point = AllMusicHud.getPos(displayWidth, entry.height, x, y, dir);

            int drawX = (int) point.x;
            int drawY = (int) (point.y + entry.y);
            int finalColor = applyAlpha(entry.color, alpha);

            if (maxWidth != -1 && entry.width > maxWidth) {
                int scrollOffset = (int) getOffset(entry, maxWidth);
                float revealPosition = entry.width * clamp01(state);
                int revealWidth = Math.max(0, Math.min(maxWidth,
                        Math.round(revealPosition - scrollOffset)));

                if (revealWidth > 0) {
                    gui.enableScissor(drawX, Math.max(0, drawY),
                            drawX + revealWidth, drawY + entry.height);
                    gui.text(font, entry.component, drawX - scrollOffset,
                            drawY, finalColor, entry.shadow);
                    gui.disableScissor();
                }
            } else {
                int revealWidth = Math.round(entry.width * clamp01(state));
                if (revealWidth > 0) {
                    gui.enableScissor(drawX, Math.max(0, drawY),
                            drawX + revealWidth, drawY + entry.height);
                    gui.text(font, entry.component, drawX, drawY, finalColor, entry.shadow);
                    gui.disableScissor();
                }
            }
        }
    }

    @Override
    public Point2f getLine(int line) {
        if (line >= texts.size()) {
            return new Point2f(0, 0);
        }
        TextItem<Component> entry = texts.get(line);
        return new Point2f(entry.width, entry.height);
    }

    private static int applyAlpha(int color, float alpha) {
        int a = (int) (alpha * 255);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
