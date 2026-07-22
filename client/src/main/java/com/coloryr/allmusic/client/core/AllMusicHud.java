package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.client.core.render.PictureFrameBuffer;
import com.coloryr.allmusic.client.core.render.ModernHudRender;
import com.coloryr.allmusic.client.core.render.TextFrameBuffer;
import com.coloryr.allmusic.client.core.render.TextureRender;
import com.coloryr.allmusic.codec.HudPosObj;
import com.coloryr.allmusic.codec.HudPosType;
import com.coloryr.allmusic.codec.KtvLyricObj;
import org.apache.hc.client5.http.classic.methods.HttpGet;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * AllMusic信息显示
 */
public class AllMusicHud {
    private static final long AUTO_HIDE_DELAY_MILLIS = 1200L;
    private static final int MODERN_KTV_BASE_COLOR = 0xE8F0F6;
    private static final int MODERN_KTV_PROGRESS_COLOR = 0x61E8F0;
    private static final String PG1 = "textures/hud/pg1.png";
    private static final String PG2 = "textures/hud/pg2.png";
    private static final String PG3 = "textures/hud/pg3.png";
    private static final String BG1 = "textures/hud/bg1.png";
    private static final String BG2 = "textures/hud/bg2.png";
    private static final String BG3 = "textures/hud/bg3.png";
    private static final String PG_OFFSET = "textures/hud/offset.txt";
    private static final String PIC_SCALE = "textures/hud/pic.txt";
    /**
     * 更新图片的链接
     */
    private final Queue<String> urlList = new ConcurrentLinkedDeque<>();
    /**
     * 更新锁，更新在子线程进行
     */
    private final Semaphore semaphore = new Semaphore(0);
    /**
     * 图片大小
     */
    private final int size;
    /**
     * 图片渲染
     */
    private final PictureFrameBuffer picRender;
    private final ModernHudRender modernRender;
    /**
     * 文字渲染
     */
    private final TextFrameBuffer<?> stateRender;
    private final TextFrameBuffer<?> infoRender;
    private final TextFrameBuffer<?> lyricRender;
    private final TextFrameBuffer<?> lyricTranRender;
    private final TextFrameBuffer<?> lyricKtvRender;
    private final TextFrameBuffer<?> modernLyricRender;
    private final TextFrameBuffer<?> modernKtvBaseRender;
    private final TextFrameBuffer<?> modernKtvRender;
    private final TextureRender progress1;
    private final TextureRender progress2;
    private final TextureRender progress3;
    private final TextureRender bg1;
    private final TextureRender bg3;
    public KtvLyricObj ktv = null;
    /**
     * 图片buffer
     */
    private byte[] sourceImage;
    /**
     * 图片buffer
     */
    private byte[] rotateImage;
    //显示信息
    private String info = "";
    private String lyric = "";
    private String lyricTran = "";
    private String lyricKtv = "";
    private long allTime, nowTime;
    private HudPosObj save;
    private float lyricState = 0.0f;
    private long lyricTime = -1;
    private int lyricWidth;
    private int modernKtvWidth;
    private float modernVisibleWidth;
    private long modernWidthUpdatedAt;
    private final int pgOffset;
    private final float picScale;
    private BufferedImage bg2;
    private volatile boolean isRun;
    private final Thread picThread;
    private final ScheduledExecutorService service1;

    /**
     * 是否有图片
     */
    private boolean haveImg;

    /**
     * 旋转角度
     */
    private int ang = 0;
    private int count = 0;
    /**
     * 是否需要更新材质
     */
    private boolean imageNeedUpload;
    private boolean visible;
    private boolean playbackStarted;
    private long stoppedAt;
    /**
     * 是否需要文字
     */
    private boolean infoNeedUpdate;
    private boolean lyricNeedUpdate;
    private boolean stateNeedUpdate;

    public AllMusicHud(int size) {
        this.size = size;

        isRun = true;

        picThread = new Thread(this::run, "allmusic_pic");
        picThread.start();

        service1 = Executors.newScheduledThreadPool(3);
        service1.scheduleAtFixedRate(this::picRotateTick, 0, 1, TimeUnit.MILLISECONDS);
        service1.scheduleAtFixedRate(this::lyricTick, 0, 10, TimeUnit.MILLISECONDS);
        service1.scheduleAtFixedRate(this::loopTick, 0, 100, TimeUnit.MILLISECONDS);

        picRender = AllMusicCore.bridge.makePictureRender(size);
        modernRender = AllMusicCore.bridge.makeModernHudRender(size);

        stateRender = AllMusicCore.bridge.makeTextRender("state");
        infoRender = AllMusicCore.bridge.makeTextRender("info");
        lyricRender = AllMusicCore.bridge.makeTextRender("lyric");
        lyricTranRender = AllMusicCore.bridge.makeTextRender("lyric tran");
        lyricKtvRender = AllMusicCore.bridge.makeTextRender("lyric ktv");
        modernLyricRender = AllMusicCore.bridge.makeTextRender("modern lyric");
        modernKtvBaseRender = AllMusicCore.bridge.makeTextRender("modern lyric ktv base");
        modernKtvRender = AllMusicCore.bridge.makeTextRender("modern lyric ktv progress");

        progress1 = AllMusicCore.bridge.makeTextureRender(PG1);
        progress2 = AllMusicCore.bridge.makeTextureRender(PG2);
        progress3 = AllMusicCore.bridge.makeTextureRender(PG3);
        bg1 = AllMusicCore.bridge.makeTextureRender(BG1);
        bg3 = AllMusicCore.bridge.makeTextureRender(BG3);

        String offset = AllMusicCore.bridge.readText(PG_OFFSET);
        pgOffset = parseInt(offset, -1);

        offset = AllMusicCore.bridge.readText(PIC_SCALE);
        picScale = parseFloat(offset, 0.83f);

        try {
            InputStream stream = AllMusicCore.bridge.readFile(BG2);
            bg2 = resizeImage(ImageIO.read(stream), size, size);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        isRun = false;
        service1.close();
        semaphore.release();
        picThread.interrupt();
        if (modernRender != null) {
            modernRender.close();
        }
    }

    public static BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        outputImage.getGraphics().drawImage(resultingImage, 0, 0, null);
        return outputImage;
    }

    /**
     * 时间转换
     *
     * @param time 时间
     * @return 结果
     */
    private static String tranTime(long time) {
        long m = time / 60;
        long s = time - m * 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    /**
     * 绘制图片
     *
     * @param x   X坐标
     * @param y   Y坐标
     * @param dir 对齐方式
     */
    public static Point2f getPos(float width, float height, float x, float y, HudPosType dir) {
        if (dir == null) {
            return new Point2f(x, y);
        }

        float screenWidth = AllMusicCore.bridge.getScreenWidth();
        float screenHeight = AllMusicCore.bridge.getScreenHeight();

        float x1 = x;
        float y1 = y;

        switch (dir) {
            case TOP_CENTER:
                x1 = screenWidth / 2 - width / 2 + x;
                break;
            case TOP_RIGHT:
                x1 = screenWidth - width - x;
                break;
            case LEFT:
                y1 = screenHeight / 2 - height / 2 + y;
                break;
            case CENTER:
                x1 = screenWidth / 2 - width / 2 + x;
                y1 = screenHeight / 2 - height / 2 + y;
                break;
            case RIGHT:
                x1 = screenWidth - width - x;
                y1 = screenHeight / 2 - height / 2 + y;
                break;
            case BOTTOM_LEFT:
                y1 = screenHeight - height - y;
                break;
            case BOTTOM_CENTER:
                x1 = screenWidth / 2 - width / 2 + x;
                y1 = screenHeight - height - y;
                break;
            case BOTTOM_RIGHT:
                x1 = screenWidth - width - x;
                y1 = screenHeight - height - y;
                break;
        }

        return new Point2f(x1, y1);
    }

    /**
     * 图片旋转计数器
     */
    private void picRotateTick() {
        if (save == null) return;
        if (!save.pic.rotate) return;
        if (count < save.pic.speed) {
            count++;
            return;
        }
        count = 0;
        ang++;
        ang = ang % 360;
    }

    private void lyricTick() {
        if (save == null || ktv == null || lyricTime == -1) return;
        lyricTime += 10;
        kUpdate();
        updateKtvOffset();
    }

    private void updateKtvOffset() {
        if (ktv == null || save == null || lyricWidth <= 0) {
            lyricRender.clearKtvOffset();
            lyricKtvRender.clearKtvOffset();
            lyricTranRender.setState(0);
            return;
        }

        lyricTranRender.setState(lyricState);

        float offset = 0;
        int maxWidth = save.lyric.maxWidth;
        if (maxWidth > 0 && lyricWidth > maxWidth) {
            offset = (lyricWidth - maxWidth) * lyricState;
        }

        lyricRender.setKtvOffset(offset);
        lyricKtvRender.setKtvOffset(offset);
    }

    private void loopTick() {
        if (save == null) return;
        infoRender.tick();
        if (AllMusicCore.config != null && AllMusicCore.config.modernHud) {
            modernLyricRender.tick();
            modernKtvBaseRender.tick();
            modernKtvRender.tick();
        }
    }

    /**
     * 清理显示
     */
    public void close() {
        clear();
        save = null;
    }

    public void clear() {
        visible = false;
        playbackStarted = false;
        stoppedAt = 0L;
        haveImg = false;
        info = lyric = lyricTran = lyricKtv = "";
        allTime = nowTime = 0;
        ktv = null;
        lyricTime = -1;
        lyricState = 0.0f;
        lyricWidth = 0;
        modernKtvWidth = 0;
        modernVisibleWidth = 0.0f;
        modernWidthUpdatedAt = 0L;
        lyricRender.clearKtvOffset();
        lyricKtvRender.clearKtvOffset();
        modernKtvBaseRender.clearKtvOffset();
        modernKtvRender.clearKtvOffset();

        infoNeedUpdate = true;
        lyricNeedUpdate = true;
        stateNeedUpdate = true;
    }

    public void syncPlayback(boolean playing) {
        if (!visible) {
            return;
        }
        if (playing) {
            playbackStarted = true;
            stoppedAt = 0L;
            return;
        }
        if (!playbackStarted) {
            return;
        }
        long now = System.currentTimeMillis();
        if (stoppedAt == 0L) {
            stoppedAt = now;
        } else if (now - stoppedAt >= AUTO_HIDE_DELAY_MILLIS) {
            clear();
        }
    }

    public void kUpdate() {
        if (ktv == null) {
            lyricState = 0.0f;
            return;
        }

        if (lyricTime <= ktv.start) {
            lyricState = 0.0f;
            return;
        }

        if (lyricTime >= ktv.start + ktv.time) {
            lyricState = 1.0f;
            return;
        }

        float now = 0;
        for (int i = 0; i < ktv.items.size(); i++) {
            KtvLyricObj.KtvItem item = ktv.items.get(i);
            float itemp = (float) item.key.length() / ktv.charCount;
            if (lyricTime >= item.start && lyricTime < item.start + item.time) {
                float progressInChar = (float) (lyricTime - item.start) / item.time * itemp;
                lyricState = now + progressInChar;
                return;
            }

            now += itemp;
        }

        lyricState = 0.0f;
    }

    /**
     * 加载图片
     *
     * @param picUrl 加载地址
     */
    private void loadPic(String picUrl) {
        haveImg = false;
        try {
            while (isRun && (save == null || imageNeedUpload)) {
                Thread.sleep(200);
            }
            if (!isRun || !save.pic.enable) {
                return;
            }

            HttpGet request = new HttpGet(picUrl);
            BufferedImage image = AllMusicCore.client.execute(request, (response -> {
                InputStream inputStream = response.getEntity().getContent();
                return resizeImage(ImageIO.read(inputStream), size, size);
            }));

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(image, "png", outputStream);
            sourceImage = outputStream.toByteArray();

            BufferedImage formatAvatarImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = formatAvatarImage.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int border = (int) (size * (1 - picScale));
            Ellipse2D.Double shape = new Ellipse2D.Double(border, border, size - border * 2, size - border * 2);
            graphics.setClip(shape);
            graphics.drawImage(image, border, border, size - border * 2, size - border * 2, null);
            graphics.dispose();
            if (bg2 != null) {
                graphics = formatAvatarImage.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.drawImage(bg2, 0, 0, size, size, null);
                graphics.dispose();
            }

            outputStream = new ByteArrayOutputStream();
            ImageIO.write(formatAvatarImage, "png", outputStream);
            rotateImage = outputStream.toByteArray();

            request.clear();
            imageNeedUpload = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            haveImg = false;
        } catch (Exception e) {
            e.printStackTrace();
            AllMusicCore.bridge.sendMessage("图片解析错误");
            haveImg = false;
        }
    }

    /**
     * 更新材质
     */
    private void updateTexture() {
        picRender.update(sourceImage, rotateImage);
        if (modernRender != null) {
            modernRender.update(sourceImage);
        }
        haveImg = true;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int updateModernLyricText(TextFrameBuffer<?> render, String value, int color) {
        render.clear();
        String line = firstNonEmptyLine(value);
        if (line.isEmpty()) {
            return 0;
        }

        int width = AllMusicCore.bridge.getTextWidth(line);
        if (width <= 0) {
            return 0;
        }

        render.resize(width, AllMusicCore.bridge.getFontHeight());
        render.use();
        // CustomNameplates' bedrock_2 ActionBar text is rendered without a shadow.
        render.putText(line, 0, color, false);
        render.unUse();
        return width;
    }

    private static String firstNonEmptyLine(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        for (String line : value.split("\n")) {
            if (line != null && !line.trim().isEmpty()) {
                return line;
            }
        }
        return "";
    }

    /**
     * 网络线程
     */
    private void run() {
        while (true) {
            try {
                if (!isRun) {
                    return;
                }
                semaphore.acquire();
                if (!isRun) {
                    return;
                }
                while (!urlList.isEmpty()) {
                    if (!isRun) {
                        return;
                    }
                    String picUrl = urlList.poll();
                    if (picUrl != null) {
                        loadPic(picUrl);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 设置下一张图片
     *
     * @param picUrl 图片链接
     */
    public void setImg(String picUrl) {
        visible = true;
        urlList.add(picUrl);
        semaphore.release();
    }

    /**
     * 设置位置信息
     *
     * @param save 位置信息
     */
    public boolean setPos(HudPosObj save) {
        if (save == null || save.lyric == null || save.state == null || save.info == null || save.pic == null) {
            return false;
        }
        this.save = save;

        infoNeedUpdate = true;
        lyricNeedUpdate = true;

        return true;
    }

    /**
     * 显示更新
     */
    public void update() {
        if (save == null || !visible) return;

        // 需要更新文字渲染
        if (infoNeedUpdate) {
            if (!info.isEmpty()) {
                int offset = 0;
                String[] temp = info.split("\n");

                int height = AllMusicCore.bridge.getFontHeight() + (save.info.shadow ? 1 : 0);
                int allHeight = (height + save.info.gap) * temp.length;
                int allWidth = 0;

                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    allWidth = Math.max(allWidth, AllMusicCore.bridge.getTextWidth(item));
                }

                infoRender.resize(allWidth, allHeight);
                infoRender.use();
                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    infoRender.putText(item, offset, save.info.color, save.info.shadow);
                    offset += save.info.gap;
                }
                infoRender.unUse();
            } else {
                infoRender.clear();
            }

            infoNeedUpdate = false;
        }

        if (lyricNeedUpdate) {
            int offset = 0;
            if (!lyric.isEmpty()) {
                String[] temp = lyric.split("\n");

                int height = AllMusicCore.bridge.getFontHeight() + (save.lyric.shadow ? 1 : 0);
                int allHeight = (height + save.lyric.gap) * temp.length;
                int allWidth = 0;

                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    allWidth = Math.max(allWidth, AllMusicCore.bridge.getTextWidth(item));
                }

                lyricRender.resize(allWidth, allHeight);
                lyricRender.use();
                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    lyricRender.putText(item, offset, save.lyric.color, save.lyric.shadow);
                    offset += save.lyric.gap;
                }
                lyricRender.unUse();
            } else {
                lyricRender.clear();
            }

            if (!lyricTran.isEmpty()) {
                offset = 0;
                String[] temp = lyricTran.split("\n");

                int height = AllMusicCore.bridge.getFontHeight() + (save.lyric.shadow ? 1 : 0);
                int allHeight = (height + save.lyric.gap) * temp.length;
                int allWidth = 0;

                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    allWidth = Math.max(allWidth, AllMusicCore.bridge.getTextWidth(item));
                }

                lyricTranRender.resize(allWidth, allHeight);
                lyricTranRender.use();
                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    lyricTranRender.putText(item, offset, save.lyric.color, save.lyric.shadow);
                    offset += save.lyric.gap;
                }
                lyricTranRender.unUse();
            } else {
                lyricTranRender.clear();
            }

            if (!lyricKtv.isEmpty()) {
                offset = 0;
                String[] temp = lyricKtv.split("\n");

                int height = AllMusicCore.bridge.getFontHeight() + (save.lyric.shadow ? 1 : 0);
                int allHeight = (height + save.lyric.gap) * temp.length;
                int allWidth = 0;

                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    allWidth = Math.max(allWidth, AllMusicCore.bridge.getTextWidth(item));
                }

                lyricWidth = allWidth;

                lyricKtvRender.resize(allWidth, allHeight);
                lyricKtvRender.use();
                for (String item : temp) {
                    if (item.isEmpty()) {
                        continue;
                    }
                    lyricKtvRender.putText(item, offset, save.lyric.color, save.lyric.shadow);
                    offset += save.lyric.gap;
                }
                lyricKtvRender.unUse();
            } else {
                lyricWidth = 0;
                lyricKtvRender.clear();
            }

            updateModernLyricText(modernLyricRender, lyric, save.lyric.color);
            updateModernLyricText(modernKtvBaseRender, lyricKtv, MODERN_KTV_BASE_COLOR);
            modernKtvWidth = updateModernLyricText(modernKtvRender, lyricKtv,
                    MODERN_KTV_PROGRESS_COLOR);

            lyricNeedUpdate = false;
        }

        if (stateNeedUpdate) {
            if (allTime == 0) {
                stateRender.clear();
            } else {
                String all = tranTime(allTime / 1000);
                String time = tranTime(nowTime / 1000);

                int allWidth = Math.max(AllMusicCore.bridge.getTextWidth(time), AllMusicCore.bridge.getTextWidth(all));
                int allHeight = (AllMusicCore.bridge.getFontHeight() + (save.state.shadow ? 1 : 0)) * 2;

                stateRender.resize(allWidth, allHeight);
                stateRender.use();
                stateRender.putText(all, 0, save.state.color, save.state.shadow);
                stateRender.putText(time, 10, save.state.color, save.state.shadow);
                stateRender.unUse();
            }
            stateNeedUpdate = false;
        }

        // Texture uploads must happen on the client render thread.
        if (imageNeedUpload) {
            imageNeedUpload = false;
            updateTexture();
        }

        if (modernRender != null && AllMusicCore.config.modernHud) {
            drawModernHud();
            return;
        }

        if (save.info.enable) {
            infoRender.draw(save.info.alpha, save.info.x, save.info.y, save.info.loop ? save.info.maxWidth : -1, save.info.pos);
        }
        if (save.lyric.enable) {
            TextFrameBuffer<?> baseLyricRender = ktv == null ? lyricRender : lyricKtvRender;
            float baseLyricAlpha = ktv == null ? save.lyric.alpha : save.lyric.alpha * 0.38f;
            baseLyricRender.draw(baseLyricAlpha, save.lyric.x, save.lyric.y,
                    save.lyric.maxWidth, save.lyric.pos);
            lyricTranRender.draw(save.lyric.alpha, save.lyric.x, save.lyric.y + save.lyric.gap, save.lyric.maxWidth, save.lyric.pos);

            if (ktv != null) {
                lyricKtvRender.drawWithState(save.lyric.alpha, save.lyric.x, save.lyric.y, save.lyric.maxWidth, lyricState, save.lyric.pos);
            }
        }
        if (save.state.enable && allTime != 0) {
            int gap = save.state.gap;
            Point2f item = stateRender.getLine(1);
            Point2f item1 = stateRender.getLine(0);
            // 渲染原点
            Point2f point = getPos(item.x + item1.x + progress1.width + gap + gap,
                    item.y, save.state.x, save.state.y, save.state.pos);

            stateRender.drawLine(point.x, point.y, save.state.alpha, 1);
            stateRender.drawLine(point.x + progress1.width + gap + gap + item.x, point.y, save.state.alpha, 0);

            float x = point.x + gap + item.x;
            progress1.drawPic(x, point.y + pgOffset, save.state.alpha);
            progress2.drawPic(x, point.y + pgOffset, (float) nowTime / allTime, save.state.alpha);

            float x1 = x + (((float) nowTime / allTime) * (progress1.width)) - ((float) progress3.width / 2);

            progress3.drawPic(x1, save.state.y + pgOffset, save.state.alpha);
        }

        //绘制图片
        if (save.pic.enable && haveImg) {
            if (save.pic.rotate) {
                bg1.drawPic(save.pic.x, save.pic.y, save.pic.size, save.pic.size, save.pic.pos, save.pic.alpha);
            }

            picRender.draw(save.pic.rotate, save.pic.size, save.pic.x, save.pic.y, ang, save.pic.pos, save.pic.alpha);

            if (save.pic.rotate) {
                bg3.drawPic(save.pic.x, save.pic.y, save.pic.size, save.pic.size, save.pic.pos, save.pic.alpha);
            }
        }
    }

    private void drawModernHud() {
        if (!save.lyric.enable) {
            return;
        }

        int screenWidth = AllMusicCore.bridge.getScreenWidth();
        int screenHeight = AllMusicCore.bridge.getScreenHeight();
        boolean hasKtv = ktv != null && modernKtvWidth > 0;
        TextFrameBuffer<?> activeRender = hasKtv ? modernKtvRender : modernLyricRender;
        Point2f line = activeRender.getLine(0);
        int lineWidth = Math.round(line.x);
        int lineHeight = Math.round(line.y);
        if (lineWidth <= 0 || lineHeight <= 0) {
            return;
        }

        // CustomNameplates' bedrock_2 actionbar adds one-pixel content margins
        // plus its left/right edge glyphs, producing three visible pixels per side.
        int horizontalPadding = 3;
        int maxContentWidth = Math.min(AllMusicCore.config.modernHudWidth,
                Math.max(24, screenWidth - horizontalPadding * 4));
        int targetVisibleWidth = Math.min(lineWidth, maxContentWidth);
        int visibleWidth = animateModernWidth(targetVisibleWidth);
        int drawX = Math.round((screenWidth - visibleWidth) / 2.0f);
        int textY = Math.max(4, Math.min(screenHeight - lineHeight - 4,
                screenHeight - AllMusicCore.config.lyricHudBottomOffset));

        int barX = drawX - horizontalPadding;
        int barY = textY - 3;
        int barWidth = visibleWidth + horizontalPadding * 2;
        int barHeight = 14;

        if (AllMusicCore.config.lyricHudBackground && AllMusicCore.config.modernHudOpacity > 0) {
            modernRender.drawBackground(barX, barY, barWidth, barHeight, 1,
                    AllMusicCore.config.modernHudOpacity / 100.0f,
                    true, false, 0);
        }

        if (hasKtv) {
            float sharedOffset = Math.max(0, lineWidth - visibleWidth) * lyricState;
            modernKtvBaseRender.setKtvOffset(sharedOffset);
            modernKtvRender.setKtvOffset(sharedOffset);
            modernKtvBaseRender.draw(save.lyric.alpha * 0.92f,
                    drawX, textY, visibleWidth, null);
            modernKtvRender.drawWithState(save.lyric.alpha,
                    drawX, textY, visibleWidth, lyricState, null);
        } else {
            modernLyricRender.clearKtvOffset();
            modernLyricRender.draw(save.lyric.alpha,
                    drawX, textY, visibleWidth, null);
        }
    }

    private int animateModernWidth(int targetWidth) {
        long now = System.nanoTime();
        if (modernVisibleWidth <= 0.0f || modernWidthUpdatedAt == 0L) {
            modernVisibleWidth = targetWidth;
            modernWidthUpdatedAt = now;
            return targetWidth;
        }

        float elapsedSeconds = Math.min(0.05f,
                (now - modernWidthUpdatedAt) / 1_000_000_000.0f);
        modernWidthUpdatedAt = now;

        // Frame-rate-independent easing: lyrics settle to their measured width in
        // roughly 180 ms while still following rapid line changes without snapping.
        float blend = 1.0f - (float) Math.exp(-18.0f * elapsedSeconds);
        modernVisibleWidth += (targetWidth - modernVisibleWidth) * blend;
        if (Math.abs(targetWidth - modernVisibleWidth) < 0.35f) {
            modernVisibleWidth = targetWidth;
        }
        return Math.max(1, Math.round(modernVisibleWidth));
    }

    public void setInfo(String info) {
        visible = true;
        this.info = info;

        infoNeedUpdate = true;
    }

    public void setLyric(String lyric, String tlyric, String ktv) {
        visible = true;
        this.ktv = null;
        this.lyric = lyric;
        this.lyricTran = tlyric;
        this.lyricKtv = ktv;

        lyricState = 0.0f;

        lyricNeedUpdate = true;
    }

    public void setKtv(long time, KtvLyricObj pack2) {
        ktv = pack2;
        lyricTime = time;
        if (pack2 == null) {
            lyricRender.clearKtvOffset();
            lyricKtvRender.clearKtvOffset();
            modernKtvBaseRender.clearKtvOffset();
            modernKtvRender.clearKtvOffset();
        }
    }

    public void setTime(long time, long now) {
        visible = true;
        this.nowTime = now;
        this.allTime = time;

        stateNeedUpdate = true;
    }
}
