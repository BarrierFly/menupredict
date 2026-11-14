package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.predict.CraftingResultPredictor;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryMenu.class)
public class InventoryMenuMixin {
	@Inject(method = "slotsChanged", at = @At("HEAD"))
	private void menupredict$onSlotsChanged(Container container, CallbackInfo ci) {
		CraftingResultPredictor.onInventoryMenuSlotsChanged((InventoryMenu) (Object) this);
	}
}
