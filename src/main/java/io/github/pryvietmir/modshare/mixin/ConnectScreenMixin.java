package io.github.pryvietmir.modshare.mixin;

import io.github.pryvietmir.modshare.client.ModSyncGate;
import io.github.pryvietmir.modshare.client.ModSyncScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server list, direct connect and quick play all go through startConnecting: sync mods before any of them connects. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void modshare$syncModsFirst(Screen parent, Minecraft minecraft, ServerAddress serverAddress, ServerData serverData,
                                               boolean isQuickPlay, @Nullable TransferState transferState, CallbackInfo ci) {
        if (ModSyncGate.shouldIntercept(transferState)) {
            ci.cancel();
            minecraft.setScreen(new ModSyncScreen(parent, serverAddress, serverData, isQuickPlay));
        }
    }
}
