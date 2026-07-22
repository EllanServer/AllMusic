package com.coloryr.allmusic.client.core.render;

/**
 * Optional bridge for the cohesive, album-themed HUD available on newer clients.
 * Older Minecraft targets keep using the legacy texture renderer.
 */
public abstract class ModernHudRender {
    /** Upload a new album image and derive the colors used by the HUD. */
    public abstract void update(byte[] source);

    /** Draw the animated card background and advance to a foreground render layer. */
    public abstract void drawBackground(float x, float y, float width, float height, float radius,
                                        float alpha, boolean dynamic, boolean animated, int speed);

    /** Draw the album artwork with rounded clipping and a short cross-fade. */
    public abstract void drawAlbum(float x, float y, float size, float radius, float alpha,
                                   boolean animated);

    /** Draw the progress track and album-colored progress fill. */
    public abstract void drawProgress(float x, float y, float width, float height, float progress,
                                      float alpha);

    /** Clip subsequent HUD elements to a content rectangle. */
    public abstract void beginClip(int left, int top, int right, int bottom);

    /** Restore the previous clip rectangle. */
    public abstract void endClip();

    /** Release version-specific GPU resources when the client shuts down. */
    public void close() {
    }
}
