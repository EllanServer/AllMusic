package com.coloryr.allmusic.client.core.player;

import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.client.core.objs.PlayTaskObj;
import com.coloryr.allmusic.client.core.player.decoder.BuffPack;
import com.coloryr.allmusic.client.core.player.decoder.IDecoder;
import com.coloryr.allmusic.client.core.player.decoder.flac.FlacDecoder;
import com.coloryr.allmusic.client.core.player.decoder.m4a.M4ADecoder;
import com.coloryr.allmusic.client.core.player.decoder.mp3.Mp3Decoder;
import com.coloryr.allmusic.client.core.player.decoder.ogg.OggDecoder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AllMusicPlayer extends InputStream {

    private final Stack<PlayTaskObj> tasks = new Stack<>();
    private final Semaphore semaphore = new Semaphore(0);
    private final Semaphore semaphoreReload = new Semaphore(0);

    private volatile PlayTaskObj nowTask;
    private volatile String currentUrl;
    private CloseableHttpResponse response;
    private BufferedInputStream content;
    private boolean isClose = false;
    private boolean reload = false;
    private IDecoder decoder;
    private volatile boolean isPlay = false;
    private boolean wait = false;
    private int index = -1;
    private IntBuffer source;
    private long local;
    private boolean isRun;
    private boolean isChat;
    private ScheduledExecutorService scheduler;

    public AllMusicPlayer(IntBuffer source) {
        try {
            this.source = source;
            new Thread(this::run, "allmusic_run").start();
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::timerTick, 0, 10, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        isRun = false;
        isClose = true;
        semaphore.release();
        semaphoreReload.release();
        scheduler.close();
    }

    public void setChat() {
        isChat = true;
    }

    public void timerTick() {
        if (isPlay && nowTask != null) {
            nowTask.time += 10;
        }
    }

    public boolean isPlay() {
        return isPlay;
    }

    public void setTime(int time) {
        PlayTaskObj current = nowTask;
        if (current == null || current.url == null) {
            return;
        }
        String url = current.url;
        closePlayer();
        PlayTaskObj task = new PlayTaskObj();
        task.url = url;
        task.time = time;
        tasks.push(task);
        semaphore.release();
    }

    public void connect() throws IOException {
        String url = currentUrl;
        if (url == null || url.isEmpty()) {
            throw new IOException("The current music URL is empty");
        }

        streamClose();
        HttpGet request = new HttpGet(url);
        request.setHeader("Range", "bytes=" + local + "-");
        response = AllMusicCore.client.execute(request);
        int statusCode = response.getCode();
        if (statusCode < 200 || statusCode >= 400) {
            throw new IOException("Unexpected code " + statusCode);
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            throw new IOException("Response entity is null");
        }
        content = new BufferedInputStream(entity.getContent());
    }

    private void resetSource() {
        if (index != -1) {
            AL10.alSourceStop(index);
            AL10.alSourcei(index, AL10.AL_BUFFER, AL10.AL_NONE);

            int queued;
            do {
                queued = AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED);
                if (queued > 0) {
                    int buffer = AL10.alSourceUnqueueBuffers(index);
                    if (buffer != 0) {
                        AL10.alDeleteBuffers(buffer);
                    }
                }
            } while (queued > 0);

            AL10.alSourcef(index, AL10.AL_GAIN, AllMusicCore.bridge.getVolume());
            AL10.alSourcef(index, AL10.AL_PITCH, 1.0f);
        }
    }

    private void run() {
        if (isRun) {
            return;
        }
        isRun = true;
        while (true) {
            try {
                if (!isRun) {
                    return;
                }
                semaphore.acquire();
                if (!isRun) {
                    return;
                }

                if (index == -1) {
                    index = AL10.alGenSources();
                    if (index == 0 && source != null) {
                        index = source.get(0);
                        if (index == 0) {
                            AllMusicCore.bridge.sendMessage("音频源创建失败");
                            return;
                        }
                    }
                }

                resetSource();

                PlayTaskObj task = tasks.pop();
                if (task == null || task.url == null || task.url.isEmpty()) continue;
                tasks.clear();
                nowTask = task;
                currentUrl = task.url;
                isClose = false;
                try {
                    local = 0;
                    connect();
                } catch (Exception e) {
                    try {
                        streamClose();
                    } catch (Exception closeException) {
                        e.addSuppressed(closeException);
                    }
                    e.printStackTrace();
                    AllMusicCore.bridge.sendMessage("获取音乐失败");
                    clearCurrentTask(task);
                    continue;
                }

                decoder = createDecoder();
                if (decoder == null || !decoder.set()) {
                    AllMusicCore.bridge.sendMessage("不支持这样的文件播放");
                    streamClose();
                    decodeClose();
                    clearCurrentTask(task);
                    continue;
                }

                isPlay = true;
                int frequency = decoder.getOutputFrequency();
                int channels = decoder.getOutputChannels();
                if (channels != 1 && channels != 2) {
                    streamClose();
                    decodeClose();
                    clearCurrentTask(task);
                    continue;
                }
                if (task.time != 0) {
                    decoder.set(task.time);
                }
                reload = false;
                int chatCount = 0;

                while (true) {
                    if (!isRun) {
                        return;
                    }
                    try {
                        if (isClose) break;

                        while (AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED) < AllMusicCore.config.queueSize) {
                            if (!isRun) {
                                return;
                            }
                            if (isClose) break;
                            BuffPack output = decoder.decodeFrame();
                            if (output == null) break;
                            ByteBuffer byteBuffer = BufferUtils.createByteBuffer(output.len)
                                    .put(output.buff, 0, output.len);
                            ((Buffer) byteBuffer).flip();

                            IntBuffer intBuffer = BufferUtils.createIntBuffer(1);
                            AL10.alGenBuffers(intBuffer);
                            int buffer = intBuffer.get(0);

                            if (buffer == 0) continue;

                            AL10.alBufferData(
                                    buffer,
                                    channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                                    byteBuffer,
                                    frequency);

                            AL10.alSourceQueueBuffers(index, buffer);

                            if (AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                                AL10.alSourcePlay(index);
                            }
                        }

                        float temp = AllMusicCore.bridge.getVolume();
                        float now = AL10.alGetSourcef(index, AL10.AL_GAIN);
                        if (isChat) {
                            temp *= 0.2F;
                        }
                        if (now != temp) {
                            AL10.alSourcef(index, AL10.AL_GAIN, temp);
                        }

                        int processed = AL10.alGetSourcei(index, AL10.AL_BUFFERS_PROCESSED);
                        for (int i = 0; i < processed; i++) {
                            int buffer = AL10.alSourceUnqueueBuffers(index);
                            if (buffer != 0) {
                                AL10.alDeleteBuffers(buffer);
                            }
                        }

                        Thread.sleep(5);

                        if (isChat) {
                            chatCount++;
                            if (chatCount >= 200) {
                                isChat = false;
                                chatCount = 0;
                            }
                        }
                    } catch (Exception e) {
                        if (!isClose) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }

                streamClose();
                decodeClose();
                currentUrl = null;

                while (!isClose && AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                    Thread.sleep(50);
                }

                if (!reload) {
                    wait = true;
                    if (semaphoreReload.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        if (!isRun) {
                            break;
                        }
                        if (reload) {
                            nowTask = null;
                            tasks.push(task);
                            semaphore.release();
                            continue;
                        }
                    }
                    isPlay = false;

                    AL10.alSourceStop(index);
                    AL10.alSourcei(index, AL10.AL_BUFFER, AL10.AL_NONE);
                    int queued = AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED);
                    while (queued > 0) {
                        int buffer = AL10.alSourceUnqueueBuffers(index);
                        if (buffer != 0) {
                            AL10.alDeleteBuffers(buffer);
                        }
                        queued--;
                    }
                } else {
                    nowTask = null;
                    tasks.push(task);
                    index = -1;
                    semaphore.release();
                }
                nowTask = null;
            } catch (Exception e) {
                e.printStackTrace();
                isPlay = false;
                nowTask = null;
                currentUrl = null;
                try {
                    streamClose();
                } catch (Exception closeException) {
                    e.addSuppressed(closeException);
                }
                try {
                    decodeClose();
                } catch (Exception closeException) {
                    e.addSuppressed(closeException);
                }
            }
        }
    }

    private void clearCurrentTask(PlayTaskObj task) {
        if (nowTask == task) {
            nowTask = null;
        }
        if (currentUrl != null && currentUrl.equals(task.url)) {
            currentUrl = null;
        }
        isPlay = false;
    }

    private IDecoder createDecoder() throws IOException {
        byte[] head = new byte[12];
        content.mark(head.length);
        int read = 0;
        while (read < head.length) {
            int count = content.read(head, read, head.length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        content.reset();
        if (read < 4) {
            throw new IOException("The audio response is too short");
        }

        AudioFormatDetector.Format format = AudioFormatDetector.detect(head, read);
        switch (format) {
            case MP3:
                return new Mp3Decoder(this);
            case M4A:
                return new M4ADecoder(this);
            case OGG:
                return new OggDecoder(this);
            case FLAC:
                return new FlacDecoder(this);
            case UNKNOWN:
            default:
                System.err.println("[AllMusic Client] Unsupported audio header: "
                        + AudioFormatDetector.hexPrefix(head, read));
                return null;
        }
    }

    public void tick() {
        if (wait) {
            wait = false;
            semaphoreReload.release();
        }
    }

    public void closePlayer() {
        isClose = true;
        nowTask = null;
    }

    public void setMusic(String url) {
        closePlayer();
        PlayTaskObj taskObj = new PlayTaskObj();
        taskObj.time = 0;
        taskObj.url = url;
        tasks.push(taskObj);
        semaphore.release();
    }

    private void streamClose() throws IOException {
        CloseableHttpResponse oldResponse = response;
        BufferedInputStream oldContent = content;
        response = null;
        content = null;

        if (oldResponse != null) {
            // The response owns the entity stream. Closing content again after
            // an immediate response close makes HttpClient try to drain an
            // already-aborted Content-Length stream and report a false
            // "premature end" error.
            oldResponse.close(CloseMode.IMMEDIATE);
        } else if (oldContent != null) {
            oldContent.close();
        }
    }

    private void decodeClose() throws Exception {
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
    }

    @Override
    public int read() throws IOException {
        int value = content.read();
        if (value >= 0) {
            local++;
        }
        return value;
    }

    @Override
    public int read(byte[] buf) throws IOException {
        int length = content.read(buf);
        if (length > 0) {
            local += length;
        }
        return length;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }

        // InputStream.skip() is a forward read, not a random seek. Reopening
        // the HTTP response with Range here can request bytes=<length>- when
        // the MP4 parser skips exactly to EOF, which correctly receives 416.
        // Consume the current response instead; setLocal() remains the random
        // access path used for actual playback seeking.
        long skipped = content.skip(n);
        if (skipped > 0) {
            local += skipped;
        }
        return skipped;
    }

    @Override
    public synchronized int read(byte[] buf, int off, int len) throws IOException {
        try {
            int length = content.read(buf, off, len);
            if (length > 0) {
                local += length;
            }
            return length;
        } catch (IOException e) {
            connect();
            return this.read(buf, off, len);
        }
    }

    @Override
    public synchronized int available() throws IOException {
        return content.available();
    }

    @Override
    public void close() throws IOException {
        streamClose();
    }

    public void setLocal(long local) throws IOException {
        streamClose();
        this.local = local;
        connect();
    }

    public void setReload() {
        if (isPlay) {
            reload = true;
            isClose = true;
        }
    }
}
