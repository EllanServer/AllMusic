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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public class AllMusicClient implements ClientModInitializer, AllMusicBridge {
    public static final String MODID = "allmusic_client";
    public static final Logger LOGGER = LogManager.getLogger("AllMusic Client");
    public static GuiGraphicsExtractor context;
    private static final ReentrantReadWriteLock SOUND_RELOAD_LOCK =
            new ReentrantReadWriteLock(true);
    private static final AtomicLong SOUND_GENERATION = new AtomicLong();
    private static volatile CompletableFuture<Void> soundReady =
            CompletableFuture.completedFuture(null);

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
        Lock readLock = SOUND_RELOAD_LOCK.readLock();
        while (true) {
            CompletableFuture<Void> ready = soundReady;
            ready.join();
            readLock.lock();
            try {
                if (ready != soundReady) {
                    continue;
                }
                var soundManager = Minecraft.getInstance().getSoundManager();
                var soundEngine = ((SoundManagerAccessor) soundManager).allmusic$getSoundEngine();
                var executor = ((SoundEngineAccessor) soundEngine).allmusic$getExecutor();
                return CompletableFuture.supplyAsync(action, executor).join();
            } finally {
                readLock.unlock();
            }
        }
    }

    @Override
    public long getSoundGeneration() {
        return SOUND_GENERATION.get();
    }

    public static void beginSoundReload() {
        CompletableFuture<Void> nextReady = new CompletableFuture<>();
        soundReady = nextReady;
        AllMusicCore.reload();
        SOUND_RELOAD_LOCK.writeLock().lock();
        long generation = SOUND_GENERATION.incrementAndGet();
        LOGGER.info("Suspended AllMusic for sound engine reload {}", generation);
    }

    public static void finishSoundReload() {
        CompletableFuture<Void> ready = soundReady;
        SOUND_RELOAD_LOCK.writeLock().unlock();
        ready.complete(null);
        LOGGER.info("Sound engine reload complete; AllMusic may resume");
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
