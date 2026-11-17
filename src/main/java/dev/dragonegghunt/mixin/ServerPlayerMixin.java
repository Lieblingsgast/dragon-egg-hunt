package dev.dragonegghunt.mixin;

import dev.dragonegghunt.DragonEggHunt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Death and dropping items. Fabric's death event comes after the drops, and there's no event for dropping items.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    /** Before anything drops, so the egg is placed where the player died instead (also with keepInventory). */
    @Inject(method = "die", at = @At("HEAD"))
    private void dragonegghunt$placeEggOnDeath(DamageSource source, CallbackInfo ci) {
        DragonEggHunt mod = DragonEggHunt.active();
        if (mod != null) {
            mod.onPlayerDeath((ServerPlayer) (Object) this);
        }
    }

    /** Every way of dropping an item ends here: Q, throwing from an inventory screen, clicking outside it ... */
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$keepEgg(ItemStack stack, boolean randomly, Prediction prediction,
                                       CallbackInfoReturnable<ItemEntity> cir) {
        DragonEggHunt mod = DragonEggHunt.active();
        if (mod != null && mod.onDrop((ServerPlayer) (Object) this, stack)) {
            cir.setReturnValue(null);
        }
    }
}
