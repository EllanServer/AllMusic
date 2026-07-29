package com.coloryr.allmusic.client.core.player;

import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.client.core.objs.PlayTaskObj;
import com.coloryr.allmusic.client.core.player.decoder.BuffPack;
import com.coloryr.allmusic.client.core.player.decoder.IDecoder;
import com.coloryr.allmusic.client.core.player.decoder.flac.FlacDecoder;
import com.coloryr.allmusic.client.core.player.decoder.m4a.M4ADecoder;
import com.coloryr.allmusic.client.core.player.decoder.m4a.mp4.SeekableInput;
import com.coloryr.allmusic.client.core.player.decoder.mp3.Mp3Decoder;
import com.coloryr.allmusic.client.core.player.decoder.ogg.OggDecoder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AllMusicPlayer extends InputStream implements SeekableInput {

    private static final int MAX_READ_RETRIES = 3;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "^bytes\\s+(?:(\\d+)-(\\d+)|\\*)/(\\d+|\\*)$",
            Pattern.CASE_INSENSITIVE);

    private final Stack<PlayTaskObj> tasks = new Stack<>();
    private final Semaphore semaphore = new Semaphore(0);
    private final Semaphore semaphoreReload = new Semaphore(0);

    private volatile PlayTaskObj nowTask;
    private volatile String currentUrl;
    private CloseableHttpResponse response;
    private BufferedInputStream content;
    private volatile boolean isClose = false;
    private volatile boolean reload = false;
    private IDecoder decoder;
    private volatile boolean isPlay = false;
    private boolean wait = false;
    private int index = -1;
    private long sourceGeneration = Long.MIN_VALUE;
    private IntBuffer source;
    private long local;
    private volatile long contentLength = -1;
    private volatile boolean isRun;
    private volatile boolean isChat;
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

    public synchronized void connect() throws IOException {
        String url = currentUrl;
        if (url == null || url.isEmpty()) {
            throw new IOException("The current music URL is empty");
        }

        final long position = local;
        streamClose();
        HttpGet request = new HttpGet(url);
        request.setHeader("Accept-Encoding", "identity");
        if (position > 0) {
            request.setHeader("Range", "bytes=" + position + "-");
        }

        CloseableHttpResponse nextResponse = AllMusicCore.client.execute(request);
        boolean keepResponse = false;
        try {
            int statusCode = nextResponse.getCode();
            RangeInfo range = parseContentRange(nextResponse.getFirstHeader("Content-Range"));

            if (statusCode == 416) {
                if (range != null && range.length >= 0) {
                    contentLength = range.length;
                }
                if (contentLength >= 0 && position == contentLength) {
                    content = emptyContent();
                    return;
                }
                throw new EOFException("HTTP range starts outside the audio file: " + position
                        + " (length=" + contentLength + ")");
            }
            if (statusCode != 200 && statusCode != 206) {
                throw new IOException("Unexpected code " + statusCode);
            }

            HttpEntity entity = nextResponse.getEntity();
            if (entity == null) {
                throw new IOException("Response entity is null");
            }

            if (statusCode == 206) {
                if (range == null || range.start != position) {
                    throw new IOException("Invalid Content-Range for requested offset " + position);
                }
                if (range.length >= 0) {
                    contentLength = range.length;
                }
            } else {
                long responseLength = entity.getContentLength();
                if (responseLength >= 0) {
                    contentLength = responseLength;
                }
                if (contentLength >= 0 && position > contentLength) {
                    throw new EOFException("Audio offset " + position + " exceeds length " + contentLength);
                }
            }

            BufferedInputStream nextContent = new BufferedInputStream(entity.getContent());
            if (statusCode == 200 && position > 0) {
                // Some CDNs ignore Range and return the complete object. Keep
                // the logical and physical positions aligned by consuming the
                // exact prefix instead of silently restarting at byte zero.
                discardFully(nextContent, position);
            }

            response = nextResponse;
            content = nextContent;
            keepResponse = true;
        } finally {
            if (!keepResponse) {
                nextResponse.close(CloseMode.IMMEDIATE);
            }
        }
    }

    private static BufferedInputStream emptyContent() {
        return new BufferedInputStream(new ByteArrayInputStream(new byte[0]));
    }

    private static void discardFully(InputStream input, long count) throws IOException {
        long left = count;
        byte[] buffer = new byte[8192];
        while (left > 0) {
            long skipped = input.skip(left);
            if (skipped > 0) {
                left -= skipped;
                continue;
            }

            int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new EOFException("Audio response ended while discarding " + count + " bytes");
            }
            left -= read;
        }
    }

    private static RangeInfo parseContentRange(Header header) {
        if (header == null || header.getValue() == null) {
            return null;
        }
        Matcher matcher = CONTENT_RANGE.matcher(header.getValue().trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long start = matcher.group(1) == null ? -1 : Long.parseLong(matcher.group(1));
            long end = matcher.group(2) == null ? -1 : Long.parseLong(matcher.group(2));
            long length = "*".equals(matcher.group(3)) ? -1 : Long.parseLong(matcher.group(3));
            if ((start >= 0 && end < start) || (length >= 0 && end >= length)) {
                return null;
            }
            return new RangeInfo(start, length);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class RangeInfo {
        private final long start;
        private final long length;

        private RangeInfo(long start, long length) {
            this.start = start;
            this.length = length;
        }
    }

    private boolean isCurrentSource() {
        return index > 0 && sourceGeneration == AllMusicCore.bridge.getSoundGeneration();
    }

    private void prepareSource() {
        while (true) {
            if (!isCurrentSource()) {
                SourceHandle created = onSoundThread(() -> {
                    long generation = AllMusicCore.bridge.getSoundGeneration();
                    clearAlError();
                    int sourceId = AL10.alGenSources();
                    int error = AL10.alGetError();
                    return new SourceHandle(sourceId, generation, error);
                });

                if (created.generation != AllMusicCore.bridge.getSoundGeneration()) {
                    continue;
                }

                int sourceId = created.sourceId;
                boolean usedFallback = false;
                if (sourceId == 0 && source != null) {
                    sourceId = source.get(0);
                    usedFallback = sourceId != 0;
                }
                if (sourceId == 0 || (!usedFallback && created.error != AL10.AL_NO_ERROR)) {
                    throw new IllegalStateException("OpenAL failed to create source"
                            + " (source=" + sourceId + ", error=0x"
                            + Integer.toHexString(created.error) + ")");
                }
                index = sourceId;
                sourceGeneration = created.generation;
            }

            if (resetSource() && isCurrentSource()) {
                return;
            }
            index = -1;
        }
    }

    private boolean resetSource() {
        if (index == -1) {
            return false;
        }
        return onSoundThread(() -> {
            if (!isCurrentSource()) {
                return false;
            }

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
            return true;
        });
    }

    private <T> T onSoundThread(Supplier<T> action) {
        return AllMusicCore.bridge.callOnSoundThread(action);
    }

    private void runOnSoundThread(Runnable action) {
        onSoundThread(() -> {
            action.run();
            return null;
        });
    }

    private int queuedBuffers() {
        return onSoundThread(() -> {
            if (isClose || !isCurrentSource()) {
                return Integer.MAX_VALUE;
            }
            return AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED);
        });
    }

    private void queueBuffer(BuffPack output, int channels, int frequency) {
        ByteBuffer byteBuffer = BufferUtils.createByteBuffer(output.len)
                .put(output.buff, 0, output.len);
        ((Buffer) byteBuffer).flip();

        runOnSoundThread(() -> {
            if (isClose || !isCurrentSource()) {
                return;
            }
            clearAlError();
            IntBuffer intBuffer = BufferUtils.createIntBuffer(1);
            AL10.alGenBuffers(intBuffer);
            int buffer = intBuffer.get(0);
            requireAlBuffer(buffer, "create");

            AL10.alBufferData(
                    buffer,
                    channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                    byteBuffer,
                    frequency);
            requireAlSuccess("fill buffer");

            AL10.alSourceQueueBuffers(index, buffer);
            requireAlSuccess("queue buffer");

            if (AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                AL10.alSourcePlay(index);
                requireAlSuccess("start source");
            }
        });
    }

    private PlaybackState updatePlaybackState(float volume) {
        return onSoundThread(() -> {
            if (isClose || !isCurrentSource()) {
                return new PlaybackState(0, AL10.AL_STOPPED);
            }
            float currentVolume = AL10.alGetSourcef(index, AL10.AL_GAIN);
            if (currentVolume != volume) {
                AL10.alSourcef(index, AL10.AL_GAIN, volume);
            }

            int processed = AL10.alGetSourcei(index, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processed; i++) {
                int buffer = AL10.alSourceUnqueueBuffers(index);
                if (buffer != 0) {
                    AL10.alDeleteBuffers(buffer);
                }
            }

            return new PlaybackState(
                    AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED),
                    AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE));
        });
    }

    private boolean sourcePlaying() {
        return onSoundThread(() -> !isClose && isCurrentSource()
                && AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING);
    }

    private void stopAndClearSource() {
        runOnSoundThread(() -> {
            if (!isCurrentSource()) {
                return;
            }
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
        });
    }

    private static void clearAlError() {
        while (AL10.alGetError() != AL10.AL_NO_ERROR) {
            // alGetError clears one pending error at a time.
        }
    }

    private static void requireAlBuffer(int buffer, String operation) {
        int error = AL10.alGetError();
        if (buffer == 0 || error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException("OpenAL failed to " + operation
                    + " (buffer=" + buffer + ", error=0x"
                    + Integer.toHexString(error) + ")");
        }
    }

    private static void requireAlSuccess(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException("OpenAL failed to " + operation
                    + " (error=0x" + Integer.toHexString(error) + ")");
        }
    }

    private static final class SourceHandle {
        private final int sourceId;
        private final long generation;
        private final int error;

        private SourceHandle(int sourceId, long generation, int error) {
            this.sourceId = sourceId;
            this.generation = generation;
            this.error = error;
        }
    }

    private static final class PlaybackState {
        private final int queued;
        private final int state;

        private PlaybackState(int queued, int state) {
            this.queued = queued;
            this.state = state;
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

                prepareSource();

                PlayTaskObj task = tasks.pop();
                if (task == null || task.url == null || task.url.isEmpty()) continue;
                tasks.clear();
                nowTask = task;
                currentUrl = task.url;
                reload = false;
                isClose = false;
                try {
                    local = 0;
                    contentLength = -1;
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
                int chatCount = 0;
                boolean decoderEnded = false;

                while (true) {
                    if (!isRun) {
                        return;
                    }
                    try {
                        if (!isCurrentSource()) {
                            reload = true;
                            isClose = true;
                        }
                        if (isClose) break;

                        while (!decoderEnded && queuedBuffers() < AllMusicCore.config.queueSize) {
                            if (!isRun) {
                                return;
                            }
                            if (isClose) break;
                            BuffPack output = decoder.decodeFrame();
                            if (output == null) {
                                decoderEnded = true;
                                break;
                            }
                            if (output.len > 0) {
                                queueBuffer(output, channels, frequency);
                            }
                        }

                        if (!isCurrentSource()) {
                            reload = true;
                            isClose = true;
                        }
                        if (isClose) break;

                        float volume = AllMusicCore.bridge.getVolume();
                        if (isChat) {
                            volume *= 0.2F;
                        }

                        PlaybackState playbackState = updatePlaybackState(volume);
                        if (decoderEnded && playbackState.queued == 0
                                && playbackState.state != AL10.AL_PLAYING) {
                            break;
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

                while (!isClose && sourcePlaying()) {
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

                    stopAndClearSource();
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
    public synchronized int read() throws IOException {
        for (int retries = 0; ; retries++) {
            try {
                int value = content.read();
                if (value >= 0) {
                    local++;
                }
                return value;
            } catch (IOException e) {
                if (retries >= MAX_READ_RETRIES) {
                    throw e;
                }
                reconnectAfterReadFailure(e);
            }
        }
    }

    @Override
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }

    @Override
    public synchronized long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }

        // Consume short forward skips through the retrying read path so a
        // connection failure cannot advance the entity while leaving local
        // behind. MP4InputStream uses seek() for large structural jumps.
        byte[] buffer = new byte[(int) Math.min(8192, n)];
        long total = 0;
        while (total < n) {
            int read = read(buffer, 0, (int) Math.min(buffer.length, n - total));
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    @Override
    public synchronized int read(byte[] buf, int off, int len) throws IOException {
        for (int retries = 0; ; retries++) {
            try {
                int length = content.read(buf, off, len);
                if (length > 0) {
                    local += length;
                }
                return length;
            } catch (IOException e) {
                if (retries >= MAX_READ_RETRIES) {
                    throw e;
                }
                reconnectAfterReadFailure(e);
            }
        }
    }

    private void reconnectAfterReadFailure(IOException readFailure) throws IOException {
        try {
            connect();
        } catch (IOException reconnectFailure) {
            reconnectFailure.addSuppressed(readFailure);
            throw reconnectFailure;
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

    @Override
    public synchronized void seek(long position) throws IOException {
        if (position < 0) {
            throw new IOException("Audio offset cannot be negative: " + position);
        }
        if (contentLength >= 0 && position > contentLength) {
            throw new EOFException("Audio offset " + position + " exceeds length " + contentLength);
        }

        streamClose();
        local = position;
        if (contentLength >= 0 && position == contentLength) {
            content = emptyContent();
            return;
        }
        connect();
    }

    @Override
    public long length() {
        return contentLength;
    }

    public void setLocal(long local) throws IOException {
        seek(local);
    }

    public void setReload() {
        if (isPlay) {
            reload = true;
            isClose = true;
        }
    }
}
