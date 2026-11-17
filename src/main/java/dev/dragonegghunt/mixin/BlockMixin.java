package dev.dragonegghunt.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explosions can't destroy the egg: it gets the blast resistance of bedrock. */
@Mixin(Block.class)
public abstract class BlockMixin {

    @Inject(method = "getExplosionResistance", at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$explosionProof(CallbackInfoReturnable<Float> cir) {
        if ((Object) this == Blocks.DRAGON_EGG) {
            cir.setReturnValue(3_600_000f);
        }
    }
}
