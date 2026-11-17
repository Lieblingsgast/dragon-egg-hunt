package dev.dragonegghunt.mixin;

import com.mojang.authlib.GameProfile;
import dev.dragonegghunt.DragonEggHunt;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers who loaded the egg icon pack, also while joining (before the player exists in the world).
 * Runs on the network thread and again on the server thread, which doesn't matter for a set.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {

    @Shadow
    protected abstract GameProfile playerProfile();

    @Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
    private void dragonegghunt$trackPack(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        DragonEggHunt mod = DragonEggHunt.active();
        if (mod != null) {
            mod.resourcePacks().onStatus(playerProfile().id(), packet.id(), packet.action());
        }
    }
}
