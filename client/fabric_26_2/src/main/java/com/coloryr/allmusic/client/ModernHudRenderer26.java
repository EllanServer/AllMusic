package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.render.ModernHudRender;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModernHudRenderer26 extends ModernHudRender {
    private static final int BACKDROP_TEXTURE_SIZE = 128;
    private static final int PROGRESS_TEXTURE_WIDTH = 256;
    private static final int CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT = 14;
    private static final int CUSTOM_NAMEPLATES_BACKGROUND_ALPHA = 112;
    private static final long TRANSITION_MILLIS = 460L;

    private final DynamicTexture[] albumTextures = new DynamicTexture[2];
    private final DynamicTexture[] backdropTextures = new DynamicTexture[2];
    private final DynamicTexture progressTexture;
    private final DynamicTexture whiteTexture;
    private final DynamicTexture customNameplatesBackgroundTexture;

    private int currentTexture;
    private int previousTexture;
    private long transitionStarted;
    private boolean hasAlbum;
    private int solidBackground = 0xFF10131C;

    public ModernHudRenderer26(int albumSize) {
        for (int i = 0; i < 2; i++) {
            albumTextures[i] = new DynamicTexture("allmusic modern album " + i,
                    albumSize, albumSize, false);
            backdropTextures[i] = new DynamicTexture("allmusic blurred backdrop " + i,
                    BACKDROP_TEXTURE_SIZE, BACKDROP_TEXTURE_SIZE, false);
        }
        progressTexture = new DynamicTexture("allmusic progress gradient",
                PROGRESS_TEXTURE_WIDTH, 4, false);
        whiteTexture = new DynamicTexture("allmusic white pixel", 1, 1, false);
        customNameplatesBackgroundTexture = new DynamicTexture(
                "allmusic CustomNameplates actionbar background", 3,
                CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT, false);

        NativeImage white = new NativeImage(1, 1, false);
        white.setPixel(0, 0, 0xFFFFFFFF);
        upload(whiteTexture, white);
        upload(customNameplatesBackgroundTexture, createCustomNameplatesBackground());

        Palette fallback = Palette.fallback();
        for (DynamicTexture texture : backdropTextures) {
            upload(texture, createFallbackBackdrop(fallback));
        }
        upload(progressTexture, createProgressTexture(fallback));
    }

    @Override
    public void update(byte[] source) {
        if (source == null || source.length == 0) {
            return;
        }

        try {
            NativeImage album = NativeImage.read(source);
            Palette palette = Palette.from(album);
            int target = hasAlbum ? 1 - currentTexture : currentTexture;
            NativeImage backdrop = createBackdropTexture(album, palette);

            upload(albumTextures[target], album);
            upload(backdropTextures[target], backdrop);
            upload(progressTexture, createProgressTexture(palette));

            previousTexture = currentTexture;
            currentTexture = target;
            transitionStarted = System.currentTimeMillis();
            hasAlbum = true;
            solidBackground = mix(0xFF090A0D, palette.dark, 0.14f);
        } catch (Exception e) {
            AllMusicClient.LOGGER.warn("Could not prepare modern HUD textures", e);
        }
    }

    @Override
    public void drawBackground(float x, float y, float width, float height, float radius,
                               float alpha, boolean dynamic, boolean animated, int speed) {
        if (AllMusicClient.context == null || width <= 0.0f || height <= 0.0f) {
            return;
        }

        float safeAlpha = clamp01(alpha);
        int color = argb(255, 255, 255, Math.round(255 * safeAlpha));
        float drawHeight = CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT;

        if (width <= 2.0f) {
            drawTextureSlice(customNameplatesBackgroundTexture, x, y, width, drawHeight,
                    1.0f / 3.0f, 0.0f, 2.0f / 3.0f, 1.0f, color);
        } else {
            drawTextureSlice(customNameplatesBackgroundTexture, x, y, 1.0f, drawHeight,
                    0.0f, 0.0f, 1.0f / 3.0f, 1.0f, color);
            drawTextureSlice(customNameplatesBackgroundTexture, x + 1.0f, y,
                    width - 2.0f, drawHeight,
                    1.0f / 3.0f, 0.0f, 2.0f / 3.0f, 1.0f, color);
            drawTextureSlice(customNameplatesBackgroundTexture, x + width - 1.0f, y,
                    1.0f, drawHeight,
                    2.0f / 3.0f, 0.0f, 1.0f, 1.0f, color);
        }
        AllMusicClient.context.nextStratum();
    }

    @Override
    public void drawAlbum(float x, float y, float size, float radius, float alpha,
                          boolean animated) {
        if (AllMusicClient.context == null || !hasAlbum || size <= 0.0f) {
            return;
        }

        float safeAlpha = clamp01(alpha);
        drawRounded(whiteTexture, false, x + 1.0f, y + 1.5f, size, size, radius,
                0.0f, 0.0f, 1.0f, 1.0f,
                argb(0, 0, 0, Math.round(96 * safeAlpha)));
        AllMusicClient.context.nextStratum();

        float transition = transitionProgress(animated);
        if (transition < 1.0f) {
            drawRounded(albumTextures[previousTexture], false, x, y, size, size, radius,
                    0.0f, 0.0f, 1.0f, 1.0f,
                    argb(255, 255, 255, Math.round(255 * safeAlpha * (1.0f - transition))));
        }
        drawRounded(albumTextures[currentTexture], false, x, y, size, size, radius,
                0.0f, 0.0f, 1.0f, 1.0f,
                argb(255, 255, 255, Math.round(255 * safeAlpha * transition)));
        AllMusicClient.context.nextStratum();
    }

    @Override
    public void drawProgress(float x, float y, float width, float height, float progress,
                             float alpha) {
        if (AllMusicClient.context == null || width <= 0.0f || height <= 0.0f) {
            return;
        }

        float safeAlpha = clamp01(alpha);
        float radius = height / 2.0f;
        drawRounded(whiteTexture, false, x, y, width, height, radius,
                0.0f, 0.0f, 1.0f, 1.0f,
                argb(255, 255, 255, Math.round(30 * safeAlpha)));

        float fillWidth = width * clamp01(progress);
        if (fillWidth > 0.25f) {
            drawRounded(progressTexture, false, x, y, fillWidth, height,
                    Math.min(radius, fillWidth / 2.0f),
                    0.0f, 0.0f, clamp01(progress), 1.0f,
                    argb(255, 255, 255, Math.round(224 * safeAlpha)));
        }
    }

    @Override
    public void beginClip(int left, int top, int right, int bottom) {
        if (AllMusicClient.context != null && right > left && bottom > top) {
            AllMusicClient.context.enableScissor(left, top, right, bottom);
        }
    }

    @Override
    public void endClip() {
        if (AllMusicClient.context != null) {
            AllMusicClient.context.disableScissor();
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < albumTextures.length; i++) {
            albumTextures[i].close();
            backdropTextures[i].close();
        }
        progressTexture.close();
        whiteTexture.close();
        customNameplatesBackgroundTexture.close();
    }

    private void drawTextureSlice(DynamicTexture texture,
                                  float x, float y, float width, float height,
                                  float u0, float v0, float u1, float v1, int color) {
        Matrix3x2f pose = new Matrix3x2f().translation(x, y);
        TextureSetup setup = TextureSetup.singleTexture(texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        AllMusicClient.context.guiRenderState.addGuiElement(new RoundedTextureRenderState(
                RenderPipelines.GUI_TEXTURED, setup, pose, width, height, 0.0f,
                u0, v0, u1, v1, color, AllMusicClient.context.scissorStack.peek()));
    }

    private void drawRounded(DynamicTexture texture, boolean repeat,
                             float x, float y, float width, float height, float radius,
                             float u0, float v0, float u1, float v1, int color) {
        Matrix3x2f pose = new Matrix3x2f().translation(x, y);
        TextureSetup setup = TextureSetup.singleTexture(texture.getTextureView(), repeat
                ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
                : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        AllMusicClient.context.guiRenderState.addGuiElement(new RoundedTextureRenderState(
                RenderPipelines.GUI_TEXTURED, setup, pose, width, height, radius,
                u0, v0, u1, v1, color, AllMusicClient.context.scissorStack.peek()));
    }

    private float transitionProgress(boolean animated) {
        if (!animated || !hasAlbum) {
            return 1.0f;
        }
        return clamp01((System.currentTimeMillis() - transitionStarted) / (float) TRANSITION_MILLIS);
    }

    private static void upload(DynamicTexture texture, NativeImage image) {
        texture.setPixels(image);
        texture.upload();
    }

    /**
     * Exact three-slice form of CustomNameplates' GPL-3.0 bedrock_2 background:
     * 14 px tall, black at alpha 112, with one transparent corner pixel.
     */
    private static NativeImage createCustomNameplatesBackground() {
        NativeImage image = new NativeImage(3, CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT, false);
        int black = CUSTOM_NAMEPLATES_BACKGROUND_ALPHA << 24;
        for (int y = 0; y < CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT; y++) {
            for (int x = 0; x < 3; x++) {
                image.setPixel(x, y, black);
            }
        }
        image.setPixel(0, 0, 0x00000000);
        image.setPixel(0, CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT - 1, 0x00000000);
        image.setPixel(2, 0, 0x00000000);
        image.setPixel(2, CUSTOM_NAMEPLATES_BACKGROUND_HEIGHT - 1, 0x00000000);
        return image;
    }

    private static NativeImage createFallbackBackdrop(Palette palette) {
        NativeImage image = new NativeImage(BACKDROP_TEXTURE_SIZE, BACKDROP_TEXTURE_SIZE, false);
        int color = mix(0xFF090A0D, palette.dark, 0.08f);
        for (int y = 0; y < BACKDROP_TEXTURE_SIZE; y++) {
            for (int x = 0; x < BACKDROP_TEXTURE_SIZE; x++) {
                image.setPixel(x, y, color);
            }
        }
        return image;
    }

    private static NativeImage createBackdropTexture(NativeImage source, Palette palette) {
        int size = BACKDROP_TEXTURE_SIZE;
        int[] sampled = new int[size * size];
        int[] horizontal = new int[size * size];
        int[] blurred = new int[size * size];

        for (int y = 0; y < size; y++) {
            int sourceY = Math.min(source.getHeight() - 1, y * source.getHeight() / size);
            for (int x = 0; x < size; x++) {
                int sourceX = Math.min(source.getWidth() - 1, x * source.getWidth() / size);
                sampled[y * size + x] = source.getPixel(sourceX, sourceY);
            }
        }

        blurHorizontal(sampled, horizontal, size, 11);
        blurVertical(horizontal, blurred, size, 11);

        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color = desaturate(blurred[y * size + x], 0.22f);
                color = mix(color, 0xFF090A0D, 0.70f);
                color = mix(color, palette.dark, 0.08f);
                float dx = (x - size * 0.5f) / (size * 0.5f);
                float dy = (y - size * 0.5f) / (size * 0.5f);
                float vignette = clamp01((dx * dx + dy * dy - 0.30f) * 0.20f);
                image.setPixel(x, y, mix(color, 0xFF040508, vignette));
            }
        }
        return image;
    }

    private static void blurHorizontal(int[] source, int[] target, int size, int radius) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                long alpha = 0;
                long red = 0;
                long green = 0;
                long blue = 0;
                int count = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int color = source[y * size + Math.max(0, Math.min(size - 1, x + offset))];
                    alpha += (color >>> 24) & 0xFF;
                    red += (color >>> 16) & 0xFF;
                    green += (color >>> 8) & 0xFF;
                    blue += color & 0xFF;
                    count++;
                }
                target[y * size + x] = ((int) (alpha / count) << 24)
                        | ((int) (red / count) << 16) | ((int) (green / count) << 8)
                        | (int) (blue / count);
            }
        }
    }

    private static void blurVertical(int[] source, int[] target, int size, int radius) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                long alpha = 0;
                long red = 0;
                long green = 0;
                long blue = 0;
                int count = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int color = source[Math.max(0, Math.min(size - 1, y + offset)) * size + x];
                    alpha += (color >>> 24) & 0xFF;
                    red += (color >>> 16) & 0xFF;
                    green += (color >>> 8) & 0xFF;
                    blue += color & 0xFF;
                    count++;
                }
                target[y * size + x] = ((int) (alpha / count) << 24)
                        | ((int) (red / count) << 16) | ((int) (green / count) << 8)
                        | (int) (blue / count);
            }
        }
    }

    private static NativeImage createProgressTexture(Palette palette) {
        NativeImage image = new NativeImage(PROGRESS_TEXTURE_WIDTH, 4, false);
        for (int x = 0; x < PROGRESS_TEXTURE_WIDTH; x++) {
            float t = x / (float) (PROGRESS_TEXTURE_WIDTH - 1);
            int accent = mix(0xFFD8D5D2, desaturate(palette.primary, 0.58f), 0.16f);
            int color = mix(accent, 0xFFF3F1EF, smooth(t));
            for (int y = 0; y < 4; y++) {
                image.setPixel(x, y, withAlpha(color, 255));
            }
        }
        return image;
    }

    private static float smooth(float value) {
        float t = clamp01(value);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static int argb(int red, int green, int blue, int alpha) {
        return (clampByte(alpha) << 24) | (clampByte(red) << 16)
                | (clampByte(green) << 8) | clampByte(blue);
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int mix(int first, int second, float amount) {
        float t = clamp01(amount);
        int red = Math.round(((first >> 16) & 0xFF) * (1.0f - t) + ((second >> 16) & 0xFF) * t);
        int green = Math.round(((first >> 8) & 0xFF) * (1.0f - t) + ((second >> 8) & 0xFF) * t);
        int blue = Math.round((first & 0xFF) * (1.0f - t) + (second & 0xFF) * t);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int darken(int color, float factor) {
        int red = Math.round(((color >> 16) & 0xFF) * factor);
        int green = Math.round(((color >> 8) & 0xFF) * factor);
        int blue = Math.round((color & 0xFF) * factor);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int lighten(int color, float amount) {
        return mix(color, 0xFFFFFFFF, amount);
    }

    private static int desaturate(int color, float amount) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        int gray = Math.round(red * 0.2126f + green * 0.7152f + blue * 0.0722f);
        return mix(color, 0xFF000000 | (gray << 16) | (gray << 8) | gray, amount);
    }

    private static final class Palette {
        private final int primary;
        private final int secondary;
        private final int bright;
        private final int dark;

        private Palette(int primary, int secondary, int bright, int dark) {
            this.primary = primary;
            this.secondary = secondary;
            this.bright = bright;
            this.dark = dark;
        }

        private static Palette fallback() {
            return new Palette(0xFF725DFF, 0xFF28C5D9, 0xFFC9BDFF, 0xFF111522);
        }

        private static Palette from(NativeImage image) {
            List<ColorBin> bins = new ArrayList<>();
            ColorBin[] histogram = new ColorBin[4096];
            int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 72);

            for (int y = 0; y < image.getHeight(); y += step) {
                for (int x = 0; x < image.getWidth(); x += step) {
                    int color = image.getPixel(x, y);
                    int alpha = (color >>> 24) & 0xFF;
                    int red = (color >>> 16) & 0xFF;
                    int green = (color >>> 8) & 0xFF;
                    int blue = color & 0xFF;
                    int max = Math.max(red, Math.max(green, blue));
                    int min = Math.min(red, Math.min(green, blue));
                    if (alpha < 160 || max < 24 || min > 238) {
                        continue;
                    }

                    int index = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4);
                    ColorBin bin = histogram[index];
                    if (bin == null) {
                        bin = new ColorBin();
                        histogram[index] = bin;
                        bins.add(bin);
                    }
                    bin.add(red, green, blue);
                }
            }

            bins.sort(Comparator.comparingDouble(ColorBin::score).reversed());
            int primary = 0;
            int secondary = 0;
            for (ColorBin bin : bins) {
                int color = bin.color();
                if (primary == 0) {
                    primary = color;
                } else if (distance(primary, color) > 72) {
                    secondary = color;
                    break;
                }
            }

            if (primary == 0) {
                return fallback();
            }
            if (secondary == 0) {
                secondary = mix(primary, 0xFF45D7E8, 0.48f);
            }

            int bright = lighten(mix(primary, secondary, 0.42f), 0.34f);
            int dark = darken(mix(primary, secondary, 0.28f), 0.24f);
            return new Palette(primary, secondary, bright, dark);
        }

        private static int distance(int first, int second) {
            int red = ((first >> 16) & 0xFF) - ((second >> 16) & 0xFF);
            int green = ((first >> 8) & 0xFF) - ((second >> 8) & 0xFF);
            int blue = (first & 0xFF) - (second & 0xFF);
            return (int) Math.sqrt(red * red + green * green + blue * blue);
        }
    }

    private static final class ColorBin {
        private int count;
        private long red;
        private long green;
        private long blue;

        private void add(int red, int green, int blue) {
            count++;
            this.red += red;
            this.green += green;
            this.blue += blue;
        }

        private int color() {
            return 0xFF000000 | ((int) (red / count) << 16)
                    | ((int) (green / count) << 8) | (int) (blue / count);
        }

        private double score() {
            int color = color();
            int max = Math.max((color >> 16) & 0xFF,
                    Math.max((color >> 8) & 0xFF, color & 0xFF));
            int min = Math.min((color >> 16) & 0xFF,
                    Math.min((color >> 8) & 0xFF, color & 0xFF));
            double saturation = max == 0 ? 0.0 : (max - min) / (double) max;
            return count * (0.55 + saturation * 1.35);
        }
    }
}
