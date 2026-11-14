package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.predict.CraftingResultPredictor;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingMenu.class)
public class CraftingMenuMixin {
	@Inject(method = "slotsChanged", at = @At("HEAD"))
	private void menupredict$onSlotsChanged(Container container, CallbackInfo ci) {
		CraftingResultPredictor.onCraftingMenuSlotsChanged((CraftingMenu) (Object) this);
	}
}
