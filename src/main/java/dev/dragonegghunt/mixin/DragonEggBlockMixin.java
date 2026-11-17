package dev.dragonegghunt.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The egg never teleports away. Clicks are already caught by the events, this also covers sneaking with empty
 * hands and anything else that would trigger it.
 */
@Mixin(DragonEggBlock.class)
public abstract class DragonEggBlockMixin {

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$noTeleport(BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        if (level instanceof ServerLevel) {
            ci.cancel();
        }
    }
}
