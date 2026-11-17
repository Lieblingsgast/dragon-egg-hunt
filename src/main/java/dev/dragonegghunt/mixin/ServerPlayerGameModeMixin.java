package dev.dragonegghunt.mixin;

import dev.dragonegghunt.DragonEggHunt;
import dev.dragonegghunt.EggRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clicks on the egg never start breaking it on the server (they count as pickup clicks instead), but the client
 * still reports when it stops breaking. Vanilla would log "Mismatch in destroy block pos" for every click.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$ignoreEggBreakEnd(BlockPos pos, ServerboundPlayerActionPacket.Action action,
                                                 Direction direction, int maxBuildHeight, int sequence,
                                                 CallbackInfo ci) {
        boolean breakEnd = action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
        if (breakEnd && DragonEggHunt.active() != null && EggRules.isEgg(level.getBlockState(pos))) {
            // after holding the button long enough, the client thinks it broke the egg
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
            ci.cancel();
        }
    }
}
