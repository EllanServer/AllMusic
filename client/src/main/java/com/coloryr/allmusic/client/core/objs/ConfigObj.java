package com.coloryr.allmusic.client.core.objs;

public class ConfigObj {
    /**
     * 图片大小
     */
    public int picSize;
    /**
     * 音频队列大小
     */
    public int queueSize;

    /** Enables the compact ActionBar lyric HUD on supported clients. */
    public boolean modernHud = true;
    /** Legacy album-card setting kept for config compatibility. */
    public boolean dynamicHudBackground = true;
    /** Legacy album-card setting kept for config compatibility. */
    public boolean hudAnimations = true;
    /** Maximum lyric width in GUI pixels. */
    public int modernHudWidth = 168;
    /** Legacy album-card setting kept for config compatibility. */
    public int modernHudHeight = 56;
    /** Legacy corner-radius field retained for config compatibility. */
    public int modernHudRadius = 1;
    /** Multiplier for CustomNameplates' original 112/255 background alpha. */
    public int modernHudOpacity = 100;
    /** Legacy album-card setting kept for config compatibility. */
    public int modernHudAnimationSpeed = 100;
    /** Draws the CustomNameplates bedrock_2 bitmap background behind the lyric. */
    public boolean lyricHudBackground = true;
    /** Distance from the bottom edge to the lyric's text baseline area. */
    public int lyricHudBottomOffset = 86;
    /** Version marker for one-time migration from the old album card. */
    public int compactLyricHudVersion;

    public void validate() {
        if (picSize <= 0) picSize = 120;
        if (queueSize <= 0) queueSize = 100;

        // Replace the old album card with the unobtrusive ActionBar lyric strip.
        if (compactLyricHudVersion < 4) {
            modernHudWidth = 168;
            modernHudRadius = 1;
            modernHudOpacity = 100;
            lyricHudBackground = true;
            lyricHudBottomOffset = 86;
            compactLyricHudVersion = 4;
        }

        modernHudWidth = clamp(modernHudWidth, 96, 320);
        modernHudHeight = clamp(modernHudHeight, 52, 80);
        modernHudRadius = 1;
        modernHudOpacity = clamp(modernHudOpacity, 0, 100);
        modernHudAnimationSpeed = clamp(modernHudAnimationSpeed, 0, 200);
        lyricHudBottomOffset = clamp(lyricHudBottomOffset, 36, 160);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
