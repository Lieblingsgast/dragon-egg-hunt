package dev.dragonegghunt.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The egg has no gravity: it stays where it was placed, even when the block below is removed.
 * Otherwise it could be dropped onto a torch or a slab, which turns a falling block into an item.
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$noGravity(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                         CallbackInfo ci) {
        if ((Object) this instanceof DragonEggBlock) {
            ci.cancel();
        }
    }
}
