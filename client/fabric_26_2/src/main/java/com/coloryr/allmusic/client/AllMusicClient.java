package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.AllMusicBridge;
import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.client.core.render.PictureFrameBuffer;
import com.coloryr.allmusic.client.core.render.ModernHudRender;
import com.coloryr.allmusic.client.core.render.TextFrameBuffer;
import com.coloryr.allmusic.client.core.render.TextureRender;
import com.coloryr.allmusic.client.mixins.SoundEngineAccessor;
import com.coloryr.allmusic.client.mixins.SoundManagerAccessor;
import com.coloryr.allmusic.comm.MusicCodec;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class AllMusicClient implements ClientModInitializer, AllMusicBridge {
    public static final String MODID = "allmusic_client";
    public static final Logger LOGGER = LogManager.getLogger("AllMusic Client");
    public static GuiGraphicsExtractor context;
    private static final long SOUND_TASK_TIMEOUT_SECONDS = 10;
    private static final AtomicLong SOUND_GENERATION = new AtomicLong();
    private static volatile SoundEpoch soundEpoch = SoundEpoch.initial();

    public static void update(GuiGraphicsExtractor draw) {
        context = draw;
        AllMusicCore.hudUpdate();
    }

    public int getScreenWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int getScreenHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    public int getTextWidth(String item) {
        return Minecraft.getInstance().font.width(item);
    }

    public int getFontHeight() {
        return Minecraft.getInstance().font.lineHeight;
    }

    public void sendMessage(String data) {
        data = "[AllMusic Client]" + data;
        LOGGER.warn(data);
        String finalData = data;
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player == null)
                return;
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(finalData));
        });
    }

    public float getVolume() {
        return Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.RECORDS);
    }

    @Override
    public <T> T callOnSoundThread(Supplier<T> action) {
        while (true) {
            SoundEpoch epoch = soundEpoch;
            epoch.ready.join();
            if (epoch != soundEpoch) {
                continue;
            }

            var soundManager = Minecraft.getInstance().getSoundManager();
            var soundEngine = ((SoundManagerAccessor) soundManager).allmusic$getSoundEngine();
            var executor = ((SoundEngineAccessor) soundEngine).allmusic$getExecutor();
            CompletableFuture<T> result = new CompletableFuture<>();
            executor.execute(() -> {
                if (epoch != soundEpoch) {
                    result.cancel(false);
                    return;
                }
                try {
                    result.complete(action.get());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });

            try {
                CompletableFuture.anyOf(result, epoch.invalidated)
                        .orTimeout(SOUND_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .join();
            } catch (CompletionException exception) {
                if (epoch != soundEpoch) {
                    result.cancel(false);
                    continue;
                }
                if (exception.getCause() instanceof TimeoutException) {
                    throw new IllegalStateException(
                            "Timed out waiting for the Minecraft sound executor", exception.getCause());
                }
                throw exception;
            }

            if (epoch != soundEpoch) {
                result.cancel(false);
                continue;
            }
            return result.join();
        }
    }

    @Override
    public long getSoundGeneration() {
        return soundEpoch.generation;
    }

    public static void beginSoundReload() {
        SoundEpoch previous = soundEpoch;
        long generation = SOUND_GENERATION.incrementAndGet();
        soundEpoch = new SoundEpoch(generation, new CompletableFuture<>());
        // SoundEngine.stopAll() drops queued executor tasks. Wake callers that
        // might otherwise wait forever for a task discarded during the reload.
        previous.invalidated.complete(null);
        AllMusicCore.reload();
        LOGGER.info("Suspended AllMusic for sound engine reload {}", generation);
    }

    public static void finishSoundReload() {
        soundEpoch.ready.complete(null);
        LOGGER.info("Sound engine reload complete; AllMusic may resume");
    }

    private static final class SoundEpoch {
        private final long generation;
        private final CompletableFuture<Void> ready;
        private final CompletableFuture<Void> invalidated = new CompletableFuture<>();

        private SoundEpoch(long generation, CompletableFuture<Void> ready) {
            this.generation = generation;
            this.ready = ready;
        }

        private static SoundEpoch initial() {
            return new SoundEpoch(0, CompletableFuture.completedFuture(null));
        }
    }

    @Override
    public void stopPlayMusic() {
        Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
        Minecraft.getInstance().getSoundManager().stop(null, SoundSource.RECORDS);
    }

    @Override
    public TextFrameBuffer makeTextRender(String name) {
        return new CoreRenderTarget(name);
    }

    @Override
    public TextureRender makeTextureRender(String file) {
        return new TexRender(file);
    }

    @Override
    public PictureFrameBuffer makePictureRender(int size) {
        return new PicRender(size);
    }

    @Override
    public ModernHudRender makeModernHudRender(int size) {
        return new ModernHudRenderer26(size);
    }

    @Override
    public String readText(String file) {
        try (InputStream inputStream = openBuiltInResource(file)) {
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not read built-in text resource {}", file, e);
            return null;
        }
    }

    @Override
    public InputStream readFile(String file) {
        try {
            return openBuiltInResource(file);
        } catch (Exception e) {
            LOGGER.warn("Could not read built-in resource {}", file, e);
            return null;
        }
    }

    static InputStream openBuiltInResource(String file) throws Exception {
        var manager = Minecraft.getInstance().getResourceManager();
        var resource = manager.getResource(Identifier.fromNamespaceAndPath(MODID, file));
        if (resource.isPresent()) {
            return resource.get().open();
        }

        String classpath = "assets/" + MODID + "/" + file;
        InputStream stream = AllMusicClient.class.getClassLoader().getResourceAsStream(classpath);
        if (stream == null) {
            throw new FileNotFoundException(classpath);
        }
        return stream;
    }

    @Override
    public void kick() {
        Minecraft client = Minecraft.getInstance();

        ClientPacketListener packetListener = client.getConnection();
        if (packetListener != null) {
            Connection connection = packetListener.getConnection();
            connection.disconnect(Component.nullToEmpty("Old AllMusic server"));
        }
    }

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MusicCodec.ID, (pack, handler) -> AllMusicCore.packDo(pack.pack()));

        AllMusicCore.init(FabricLoader.getInstance().getConfigDir(), this);

        ClientLifecycleEvents.CLIENT_STARTED.register((a) -> AllMusicCore.renderInit());
        ClientLifecycleEvents.CLIENT_STOPPING.register((a) -> AllMusicCore.stop());
    }
}
