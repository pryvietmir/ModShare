package io.github.pryvietmir.modshare.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.pryvietmir.modshare.server.ServerHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@code "modshare": {"port": ..., "url": ...}} to the server list ping, so clients find the download server
 * without any setup. Unknown fields are ignored by vanilla and NeoForge clients.
 */
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponsePacketMixin {
    @Unique
    private static final Gson MODSHARE$GSON = new Gson();

    @Shadow
    @Final
    private ServerStatus status;

    @Shadow
    @Final
    private @Nullable String cachedStatus;

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void modshare$advertise(FriendlyByteBuf buffer, CallbackInfo ci) {
        JsonObject advertisement = ServerHooks.advertisement();
        if (advertisement == null || cachedStatus != null) return;

        JsonElement json = ServerStatus.CODEC.encodeStart(JsonOps.INSTANCE, status).getOrThrow();
        if (!json.isJsonObject()) return;
        json.getAsJsonObject().add("modshare", advertisement);
        buffer.writeUtf(MODSHARE$GSON.toJson(json));
        ci.cancel();
    }
}
