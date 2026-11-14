package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.predict.EnchantPredictor;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnchantmentMenu.class)
public class EnchantmentMenuMixin {
	@Inject(method = "slotsChanged", at = @At("HEAD"))
	private void menupredict$onSlotsChanged(Container container, CallbackInfo ci) {
		EnchantPredictor.onSlotsChanged((EnchantmentMenu) (Object) this);
	}

	@Inject(method = "clickMenuButton", at = @At("TAIL"))
	private void menupredict$onClickMenuButton(Player player, int button, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			EnchantPredictor.onEnchantClicked((EnchantmentMenu) (Object) this, player, button);
		}
	}
}
