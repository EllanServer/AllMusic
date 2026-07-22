package com.coloryr.allmusic.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/**
 * A textured GUI rectangle clipped to a rounded shape using narrow edge quads.
 * It stays on Minecraft's normal GUI pipeline and therefore remains compatible
 * with Sodium without using any of Sodium's private renderer internals.
 */
public record RoundedTextureRenderState(RenderPipeline pipeline,
                                        TextureSetup textureSetup,
                                        Matrix3x2f pose,
                                        float width,
                                        float height,
                                        float radius,
                                        float u0,
                                        float v0,
                                        float u1,
                                        float v1,
                                        int color,
                                        ScreenRectangle scissorArea,
                                        ScreenRectangle bounds) implements GuiElementRenderState {
    public RoundedTextureRenderState(RenderPipeline pipeline,
                                     TextureSetup textureSetup,
                                     Matrix3x2f pose,
                                     float width,
                                     float height,
                                     float radius,
                                     float u0,
                                     float v0,
                                     float u1,
                                     float v1,
                                     int color,
                                     ScreenRectangle scissorArea) {
        this(pipeline, textureSetup, pose, width, height, radius, u0, v0, u1, v1,
                color, scissorArea, getBounds(width, height, pose, scissorArea));
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        float safeWidth = Math.max(0.0f, width);
        float safeHeight = Math.max(0.0f, height);
        float safeRadius = Math.max(0.0f, Math.min(radius, Math.min(safeWidth, safeHeight) / 2.0f));

        if (safeWidth <= 0.0f || safeHeight <= 0.0f) {
            return;
        }
        if (safeRadius < 0.5f) {
            emitQuad(consumer, 0.0f, 0.0f, safeWidth, safeHeight);
            return;
        }

        if (safeHeight - safeRadius * 2.0f > 0.0f) {
            emitQuad(consumer, 0.0f, safeRadius, safeWidth, safeHeight - safeRadius);
        }

        int slices = Math.max(1, (int) Math.ceil(safeRadius));
        for (int i = 0; i < slices; i++) {
            float top = safeRadius * i / slices;
            float bottom = safeRadius * (i + 1) / slices;
            float middle = (top + bottom) * 0.5f;
            float dy = safeRadius - middle;
            float inset = safeRadius - (float) Math.sqrt(Math.max(0.0f,
                    safeRadius * safeRadius - dy * dy));

            emitQuad(consumer, inset, top, safeWidth - inset, bottom);
            emitQuad(consumer, inset, safeHeight - bottom, safeWidth - inset, safeHeight - top);
        }
    }

    private void emitQuad(VertexConsumer consumer, float left, float top, float right, float bottom) {
        if (right <= left || bottom <= top) {
            return;
        }

        float spanU = u1 - u0;
        float spanV = v1 - v0;
        float leftU = u0 + left / width * spanU;
        float rightU = u0 + right / width * spanU;
        float topV = v0 + top / height * spanV;
        float bottomV = v0 + bottom / height * spanV;

        consumer.addVertexWith2DPose(pose, left, top).setUv(leftU, topV).setColor(color);
        consumer.addVertexWith2DPose(pose, left, bottom).setUv(leftU, bottomV).setColor(color);
        consumer.addVertexWith2DPose(pose, right, bottom).setUv(rightU, bottomV).setColor(color);
        consumer.addVertexWith2DPose(pose, right, top).setUv(rightU, topV).setColor(color);
    }

    private static ScreenRectangle getBounds(float width, float height, Matrix3x2f pose,
                                             ScreenRectangle scissorArea) {
        ScreenRectangle area = new ScreenRectangle(0, 0,
                Math.max(1, (int) Math.ceil(width)),
                Math.max(1, (int) Math.ceil(height))).transformMaxBounds(pose);
        return scissorArea != null ? scissorArea.intersection(area) : area;
    }
}
