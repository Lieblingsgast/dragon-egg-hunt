package dev.dragonegghunt.mixin;

import dev.dragonegghunt.DragonEggHunt;
import dev.dragonegghunt.EggRules;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory screen clicks that would put the egg into anything but the player's own inventory: a chest, an ender
 * chest, a shulker box, a crafting grid, a horse, a bundle ... The click is refused before vanilla handles it,
 * and vanilla then sends the client the real slot contents again.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Shadow
    @Final
    public NonNullList<Slot> slots;

    @Shadow
    public abstract ItemStack getCarried();

    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void dragonegghunt$keepEggOutOfContainers(int slotIndex, int button, ContainerInput input, Player player,
                                                      CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        DragonEggHunt mod = DragonEggHunt.active();
        if (mod != null && dragonegghunt$isBlocked(slotIndex, button, input, serverPlayer)) {
            ci.cancel();
            mod.onStorageBlocked(serverPlayer);
        }
    }

    @Unique
    private boolean dragonegghunt$isBlocked(int slotIndex, int button, ContainerInput input, ServerPlayer player) {
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            // e.g. clicking outside the window, which drops the item: handled where items are dropped
            return false;
        }
        Slot slot = slots.get(slotIndex);
        boolean foreign = slot.container != player.getInventory();
        ItemStack carried = getCarried();
        ItemStack current = slot.getItem();
        return switch (input) {
            // putting the egg from the cursor into a container slot, also by dragging it over slots
            case PICKUP, QUICK_CRAFT -> (foreign && EggRules.isEgg(carried))
                    // bundles
                    || (EggRules.isEgg(carried) && EggRules.isBundle(current))
                    || (EggRules.isBundle(carried) && EggRules.isEgg(current));
            // shift-clicking the egg into an open container; the player's own inventory screen only moves it
            // between hotbar and inventory
            case QUICK_MOVE -> !foreign && EggRules.isEgg(current) && !((Object) this instanceof InventoryMenu);
            // number key or offhand key over a container slot
            case SWAP -> foreign && EggRules.isEgg(player.getInventory().getItem(button));
            default -> false;
        };
    }
}
