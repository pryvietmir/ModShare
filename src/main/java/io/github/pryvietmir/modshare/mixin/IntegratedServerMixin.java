package io.github.pryvietmir.modshare.mixin;

import io.github.pryvietmir.modshare.server.ServerHooks;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Starts sharing mods when a singleplayer world is opened to LAN, so friends can join it like a dedicated server. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "publishServer", at = @At("RETURN"))
    private void modshare$shareModsOnLan(@Nullable GameType gameMode, boolean cheats, int port, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) ServerHooks.onPublishedToLan((IntegratedServer) (Object) this);
    }
}
