package dev.dragonegghunt.mixin;

import dev.dragonegghunt.DragonEggHunt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Placing the egg (also in creative, without keeping a copy), and keeping it out of bundles and shulker boxes. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    /** Bundles and shulker box slots ask this before taking an item. */
    @Inject(method = "canFitInsideContainerItems", at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$notInsideItems(CallbackInfoReturnable<Boolean> cir) {
        if (getBlock() == Blocks.DRAGON_EGG) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void dragonegghunt$onPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (getBlock() != Blocks.DRAGON_EGG || !cir.getReturnValue().consumesAction()
                || !(context.getLevel() instanceof ServerLevel level)) {
            return;
        }
        DragonEggHunt mod = DragonEggHunt.active();
        if (mod == null) {
            return;
        }
        Player player = context.getPlayer();
        if (player != null && player.hasInfiniteMaterials()) {
            // Creative keeps the placed item, which would make a second egg. Vanilla restores the count of the used
            // stack after placing, so the hand gets a new stack with one egg less instead.
            ItemStack used = context.getItemInHand();
            player.setItemInHand(context.getHand(), used.getCount() > 1 ? used.copyWithCount(used.getCount() - 1) : ItemStack.EMPTY);
        }
        mod.onEggPlaced(level, context.getClickedPos());
    }
}
