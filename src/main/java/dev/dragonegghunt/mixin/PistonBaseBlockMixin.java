package dev.dragonegghunt.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pistons can't move the egg (vanilla would break it into an item). The piston just doesn't extend. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void dragonegghunt$eggIsImmovable(BlockState state, Level level, BlockPos pos, Direction direction,
                                                     boolean allowDestroy, Direction pistonFacing,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (state.is(Blocks.DRAGON_EGG)) {
            cir.setReturnValue(false);
        }
    }
}
