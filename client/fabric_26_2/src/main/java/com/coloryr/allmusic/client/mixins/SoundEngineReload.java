package com.coloryr.allmusic.client.mixins;

import com.coloryr.allmusic.client.AllMusicClient;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public class SoundEngineReload {
    @Inject(method = "reload", at = @At("HEAD"))
    private void allmusic$beginReload(CallbackInfo info) {
        AllMusicClient.beginSoundReload();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void allmusic$finishReload(CallbackInfo info) {
        AllMusicClient.finishSoundReload();
    }
}
